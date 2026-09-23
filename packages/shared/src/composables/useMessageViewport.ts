import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import type { Ref } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { useReadPosition } from './useReadPosition'
import { useMentionNotification } from './useMentionNotification'

/**
 * Message pane scrolling and read-position tracking: bidirectional infinite
 * pagination, viewport-driven read saves (IntersectionObserver), the unread
 * divider, entry positioning (saved position / message deep link), and
 * scroll-motion flags consumed by hover UI.
 */
export function useMessageViewport(options: {
  messagesContainer: Ref<HTMLElement | null>
  // Called after messages are fetched on a channel switch, before the entry
  // viewport is positioned (e.g. mute-status refresh).
  onChannelEntered?: () => Promise<void> | void
}) {
  const { messagesContainer, onChannelEntered } = options
  const route = useRoute()
  const chat = useChatStore()

  // True while prepending older messages (scrolling up). Suppresses the
  // length-watch auto-scroll (which would yank the user back to the bottom) and
  // guards re-entrancy.
  const isFetchingOlder = ref(false)

  // True while appending newer messages (scrolling down a pane that starts
  // mid-history after a message deep link). Same suppression, mirrored.
  const isFetchingNewer = ref(false)

  const isWheelScrolling = ref(false)
  const isScrollActive = ref(false)
  let wheelScrollTimeout: ReturnType<typeof setTimeout> | null = null
  // True while the viewport sits away from the tail (or the loaded window
  // itself is mid-history): shows the jump-to-latest pill and gates the
  // new-message auto-follow.
  const isFarFromLatest = ref(false)
  const latestVisibleMessageId = ref<number | null>(null)
  const messageIndexMap = new Map<number, number>()
  const visibleMessageIndices = new Set<number>()
  const maxVisibleIndex = ref<number | null>(null)
  let messageObserver: IntersectionObserver | null = null

  // Read position tracking (viewport semantics: what enters the viewport is read)
  const {
    saveReadPosition,
    getReadPosition,
  } = useReadPosition()
  let scrollSaveTimeout: ReturnType<typeof setTimeout> | null = null

  // Mention notification tracking
  const {
    clearChannelMention,
    getChannelMention,
  } = useMentionNotification()

  // First message the user has not seen in the current channel; rendered as an
  // in-list divider until the viewport catches up to the tail as of entry.
  const firstUnreadId = ref<number | null>(null)
  const entryTailId = ref<number | null>(null)
  // Entry positioning (older-page pull + jump to first unread) must not let the
  // bottom page flash advance the read position before the jump happens.
  let entryPositioning = false
  // Bumps on every channel entry; stale settle timers from the previous entry
  // must not release the new entry's suppression early.
  let entryToken = 0

  // Keyed on the channel id, not the object reference: re-selecting the same
  // channel (or the server refetch swapping in fresh channel objects) must not
  // reload the pane, while a real channel switch always does.
  watch(
    () => chat.currentChannel?.id,
    async () => {
      const channel = chat.currentChannel
      if (channel && (channel.type === 'TEXT' || channel.type === 'FORWARD')) {
        // Clear any in-flight "load older" lock from the previous channel so the
        // new channel can paginate immediately.
        isFetchingOlder.value = false
        const token = ++entryToken
        entryPositioning = true
        // Hold the new-message auto-follow until entry positioning is done, so
        // the bottom page never flashes into the viewport before the jump.
        isFetchingOlder.value = true

        try {
          const linkedMessageId = readLinkedMessageId(channel.id)
          // A message deep link loads a window around the target instead of the
          // newest page, so the target message is actually reachable.
          await chat.fetchMessages(channel.id, undefined, linkedMessageId ?? undefined)
          await onChannelEntered?.()

          const tailId = chat.messages.length > 0
            ? chat.messages[chat.messages.length - 1].id
            : null
          entryTailId.value = tailId
          const savedId = getReadPosition(channel.id)

          if (linkedMessageId != null) {
            // Landed via a message deep link: position on the linked message,
            // not on the unread divider or the bottom.
            firstUnreadId.value = null
            await nextTick()
            refreshMessageObserver()
            scrollToLinkedMessage(linkedMessageId)
          } else if (savedId != null && tailId != null && savedId < tailId) {
            // Gap: pull older pages until the saved position is inside the
            // window so the first unread message is actually reachable. Cap the
            // pull — beyond it, land on the window top (still behind the tail).
            for (
              let i = 0;
              i < 5 && chat.hasMore && chat.messages.length > 0 && chat.messages[0].id > savedId;
              i++
            ) {
              await chat.fetchMessages(channel.id, chat.messages[0].id)
            }
            firstUnreadId.value = chat.messages.find((m) => m.id > savedId)?.id ?? null
            await nextTick()
            refreshMessageObserver()
            if (firstUnreadId.value != null) {
              scrollToMessage(firstUnreadId.value, 'auto')
            } else {
              scrollToBottom()
            }
          } else {
            firstUnreadId.value = null
            await nextTick()
            scrollToBottom()
            refreshMessageObserver()
          }
        } finally {
          // Let the IntersectionObserver settle on the post-jump viewport before
          // viewport-driven saves resume, then flush once for the jumped-to view.
          setTimeout(() => {
            if (token !== entryToken) return
            entryPositioning = false
            isFetchingOlder.value = false
            flushPositionSave()
            updateLatestVisibility()
          }, 250)
        }
      }
    },
    { immediate: true }
  )

  // Message-link navigation within the already-open channel (browser back/
  // forward between message links, or a link opened while inside the channel):
  // the entry watcher above is keyed on channel id and does not fire.
  watch(
    () => route.params.messageId,
    async () => {
      if (route.name !== 'MessagePath') return
      const channelId = Number(route.params.channelId)
      const messageId = Number(route.params.messageId)
      if (!Number.isInteger(messageId) || chat.currentChannel?.id !== channelId) return
      // A fresh channel entry resolves its own linked-message positioning.
      if (entryPositioning) return

      if (chat.messages.some((m) => m.id === messageId)) {
        scrollToMessage(messageId)
        return
      }
      // Outside the loaded window: reload the pane as a window around the target.
      isFetchingOlder.value = true
      try {
        await chat.fetchMessages(channelId, undefined, messageId)
        await nextTick()
        refreshMessageObserver()
      } finally {
        isFetchingOlder.value = false
      }
      scrollToLinkedMessage(messageId)
    }
  )

  // Auto-scroll when new messages arrive.
  // Skip when prepending older (isFetchingOlder) or appending newer
  // (isFetchingNewer) — loadOlderMessages/loadNewerMessages restore the scroll
  // position themselves instead of jumping.
  // Skip while hasMoreNewer: a pane that starts mid-history (message deep link)
  // must not yank the user to the bottom on every incoming message; follow
  // resumes once the pane is caught up to the tail.
  // Also skip when the user has scrolled away from the tail: a tail append
  // grows content below the viewport without firing a scroll event, so
  // re-measure before deciding; the jump-to-latest pill covers catching up.
  watch(
    () => chat.messages.length,
    async () => {
      if (isFetchingOlder.value || isFetchingNewer.value || chat.hasMoreNewer) return
      updateLatestVisibility()
      if (isFarFromLatest.value) {
        refreshMessageObserver()
        return
      }
      await nextTick()
      scrollToBottom()
      refreshMessageObserver()
    }
  )

  onMounted(() => {
    nextTick(() => {
      setupMessageObserver()
    })
  })

  function scrollToBottom() {
    nextTick(() => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      }
    })
  }

  // Whether the jump-to-latest pill should show: either the viewport sits away
  // from the bottom of the loaded window, or the window itself starts
  // mid-history (message deep link) with newer pages below it.
  function updateLatestVisibility() {
    const container = messagesContainer.value
    if (!container || chat.messages.length === 0) {
      isFarFromLatest.value = false
      return
    }
    const distance = container.scrollHeight - container.scrollTop - container.clientHeight
    isFarFromLatest.value = chat.hasMoreNewer || distance > 100
  }

  // One-click catch-up: land on the newest message and mark everything above
  // it read, regardless of what the viewport observer has settled on so far.
  async function jumpToLatest() {
    const channel = chat.currentChannel
    if (!channel) return

    if (chat.hasMoreNewer) {
      // The pane starts mid-history; scrolling can only page through what is
      // loaded. Reload the newest page instead of paging down one by one.
      isFetchingNewer.value = true
      try {
        await chat.fetchMessages(channel.id)
      } finally {
        isFetchingNewer.value = false
      }
    }

    await nextTick()
    scrollToBottom()
    refreshMessageObserver()

    const tailId = chat.messages.length > 0 ? chat.messages[chat.messages.length - 1].id : null
    if (tailId != null) {
      // A mention above the tail is passed by the jump, so clear it here
      // instead of waiting for the viewport to physically scroll past it.
      if (getChannelMention(channel.id)?.hasMention) {
        clearChannelMention(channel.id)
      }
      saveReadPosition(channel.id, tailId)
    }
    firstUnreadId.value = null
    entryTailId.value = null
    updateLatestVisibility()
  }

  // Load one page of older messages and keep the user's viewport steady.
  // The store prepends; we anchor on the current oldest message and restore its
  // viewport position so the view does not jump. Using the element's rect (not a
  // scrollHeight delta) keeps the restore exact even if a WebSocket message lands
  // during the fetch.
  async function loadOlderMessages() {
    if (isFetchingOlder.value) return
    if (!chat.currentChannel || !chat.hasMore || chat.messages.length === 0) return
    const container = messagesContainer.value
    if (!container) return

    isFetchingOlder.value = true
    const anchorId = chat.messages[0].id
    const containerTop = container.getBoundingClientRect().top
    const anchorEl = container.querySelector<HTMLElement>(`[data-message-id="${anchorId}"]`)
    const anchorRelTop = anchorEl ? anchorEl.getBoundingClientRect().top - containerTop : 0

    await chat.fetchMessages(chat.currentChannel.id, anchorId)

    await nextTick()
    const newAnchorEl = container.querySelector<HTMLElement>(`[data-message-id="${anchorId}"]`)
    if (newAnchorEl) {
      const newRelTop = newAnchorEl.getBoundingClientRect().top - container.getBoundingClientRect().top
      container.scrollTop += newRelTop - anchorRelTop
    }

    refreshMessageObserver()
    isFetchingOlder.value = false
  }

  // Load one page of newer messages and keep the user's viewport steady — the
  // mirror of loadOlderMessages, for panes that start mid-history after a
  // message deep link (or a back/forward jump between message links).
  async function loadNewerMessages() {
    if (isFetchingNewer.value) return
    if (!chat.currentChannel || !chat.hasMoreNewer || chat.messages.length === 0) return
    const container = messagesContainer.value
    if (!container) return

    isFetchingNewer.value = true
    const anchorId = chat.messages[chat.messages.length - 1].id
    const containerTop = container.getBoundingClientRect().top
    const anchorEl = container.querySelector<HTMLElement>(`[data-message-id="${anchorId}"]`)
    const anchorRelTop = anchorEl ? anchorEl.getBoundingClientRect().top - containerTop : 0

    await chat.fetchMessages(chat.currentChannel.id, undefined, undefined, anchorId)

    await nextTick()
    const newAnchorEl = container.querySelector<HTMLElement>(`[data-message-id="${anchorId}"]`)
    if (newAnchorEl) {
      const newRelTop = newAnchorEl.getBoundingClientRect().top - container.getBoundingClientRect().top
      container.scrollTop += newRelTop - anchorRelTop
    }

    refreshMessageObserver()
    isFetchingNewer.value = false
  }

  // Viewport-driven read position: save when the visible set settles, whether
  // the movement came from user scrolling or programmatic jumps — anything that
  // put messages on screen counts as read.
  function schedulePositionSave() {
    if (entryPositioning) return
    isScrollActive.value = true

    // Debounce: only save after the viewport stops moving for 120ms
    if (scrollSaveTimeout) {
      clearTimeout(scrollSaveTimeout)
    }
    scrollSaveTimeout = setTimeout(() => {
      isScrollActive.value = false
      flushPositionSave()
    }, 120)
  }

  function flushPositionSave() {
    const channel = chat.currentChannel
    if (!channel || chat.messages.length === 0) return
    const visibleId = latestVisibleMessageId.value
    if (!visibleId) return

    // Optimistically drop the @ badge once the viewport passes the mention
    // message; the server re-derives the flag from the new position anyway.
    const mention = getChannelMention(channel.id)
    if (mention?.hasMention && mention.lastMentionMessageId != null && visibleId >= mention.lastMentionMessageId) {
      clearChannelMention(channel.id)
    }
    saveReadPosition(channel.id, visibleId)

    // Caught up to the tail as of entry: the unread divider served its purpose.
    if (entryTailId.value != null && visibleId >= entryTailId.value) {
      firstUnreadId.value = null
    }
  }

  function handleMessagesScroll() {
    if (!chat.currentChannel || chat.messages.length === 0) return

    // Scrolled near the top: load one more page of older history.
    if (
      chat.hasMore &&
      !isFetchingOlder.value &&
      messagesContainer.value &&
      messagesContainer.value.scrollTop < 100
    ) {
      loadOlderMessages()
    }

    // Scrolled near the bottom: load one more page of newer history (panes that
    // start mid-history after a message deep link). On a pane loaded from the
    // newest page hasMoreNewer is false and this stays inert.
    if (
      chat.hasMoreNewer &&
      !isFetchingNewer.value &&
      messagesContainer.value &&
      messagesContainer.value.scrollHeight - messagesContainer.value.scrollTop - messagesContainer.value.clientHeight < 100
    ) {
      loadNewerMessages()
    }

    schedulePositionSave()
    updateLatestVisibility()
  }

  function handleWheelScroll() {
    isWheelScrolling.value = true
    if (wheelScrollTimeout) {
      clearTimeout(wheelScrollTimeout)
    }
    wheelScrollTimeout = setTimeout(() => {
      isWheelScrolling.value = false
    }, 200)
  }

  function setupMessageObserver() {
    if (!messagesContainer.value) return
    messageObserver = new IntersectionObserver(handleMessageIntersections, {
      root: messagesContainer.value,
      threshold: 0.01,
    })
    observeAllMessages()
  }

  function refreshMessageObserver() {
    if (!messageObserver) {
      setupMessageObserver()
      return
    }
    observeAllMessages()
  }

  function observeAllMessages() {
    if (!messageObserver || !messagesContainer.value) return
    messageObserver.disconnect()
    visibleMessageIndices.clear()
    maxVisibleIndex.value = null
    latestVisibleMessageId.value = null
    messageIndexMap.clear()
    chat.messages.forEach((msg, idx) => {
      messageIndexMap.set(msg.id, idx)
    })

    const elements = messagesContainer.value.querySelectorAll('.message[data-message-id]')
    elements.forEach((el) => messageObserver!.observe(el))
  }

  function updateLatestVisibleFromIndex(startIndex: number) {
    for (let i = startIndex; i >= 0; i--) {
      if (visibleMessageIndices.has(i)) {
        maxVisibleIndex.value = i
        latestVisibleMessageId.value = chat.messages[i]?.id ?? null
        return
      }
    }
    maxVisibleIndex.value = null
    latestVisibleMessageId.value = null
  }

  function handleMessageIntersections(entries: IntersectionObserverEntry[]) {
    for (const entry of entries) {
      const el = entry.target as HTMLElement
      const id = parseInt(el.getAttribute('data-message-id') || '0', 10)
      if (!id) continue
      const index = messageIndexMap.get(id)
      if (index === undefined) continue

      if (entry.isIntersecting) {
        visibleMessageIndices.add(index)
        if (maxVisibleIndex.value === null || index > maxVisibleIndex.value) {
          maxVisibleIndex.value = index
          latestVisibleMessageId.value = chat.messages[index]?.id ?? null
        }
      } else {
        visibleMessageIndices.delete(index)
        if (maxVisibleIndex.value === index) {
          updateLatestVisibleFromIndex(index - 1)
        }
      }
    }
    schedulePositionSave()
  }

  // Scroll to a specific message by ID
  function scrollToMessage(messageId: number, behavior: ScrollBehavior = 'smooth') {
    const container = messagesContainer.value
    if (!container) return

    const messageEl = container.querySelector(`[data-message-id="${messageId}"]`)
    if (messageEl) {
      messageEl.scrollIntoView({ behavior, block: 'center' })
      // Highlight the message briefly
      messageEl.classList.add('message-highlight')
      setTimeout(() => {
        messageEl.classList.remove('message-highlight')
      }, 2000)
    }
  }

  // Message deep-link target when the current route points into `channelId`.
  function readLinkedMessageId(channelId: number): number | null {
    if (route.name !== 'MessagePath') return null
    if (Number(route.params.channelId) !== channelId) return null
    const id = Number(route.params.messageId)
    return Number.isInteger(id) && id > 0 ? id : null
  }

  // Land on the linked message; if it was deleted (or is otherwise missing from
  // the loaded window), fall back to its nearest older neighbor so the location
  // is still roughly right — without highlighting the wrong message.
  function scrollToLinkedMessage(messageId: number) {
    if (chat.messages.some((m) => m.id === messageId)) {
      scrollToMessage(messageId, 'auto')
      return
    }
    const nearestOlder = [...chat.messages].reverse().find((m) => m.id < messageId)
    const el = nearestOlder
      ? messagesContainer.value?.querySelector(`[data-message-id="${nearestOlder.id}"]`)
      : null
    if (el) {
      el.scrollIntoView({ behavior: 'auto', block: 'center' })
    } else {
      scrollToBottom()
    }
  }

  onUnmounted(() => {
    if (scrollSaveTimeout) {
      clearTimeout(scrollSaveTimeout)
    }
    if (wheelScrollTimeout) {
      clearTimeout(wheelScrollTimeout)
    }
    if (messageObserver) {
      messageObserver.disconnect()
    }
  })

  return {
    isScrollActive,
    isWheelScrolling,
    isFarFromLatest,
    firstUnreadId,
    handleMessagesScroll,
    handleWheelScroll,
    scrollToMessage,
    jumpToLatest,
  }
}
