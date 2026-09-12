import { ref } from 'vue'
import type { CSSProperties, Ref } from 'vue'
import axios from 'axios'
import type { Message, ReactionGroup } from '../types'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'

const API_BASE = import.meta.env.VITE_API_BASE || ''

// Common emojis for quick reactions
const commonEmojis = ['👍', '❤️', '😂', '😮', '😢', '🎉', '🔥', '👀']

/**
 * Message reactions: emoji-picker popup state (anchored above/below the trigger
 * or a context-menu point), hover state for the empty-reactions affordance, and
 * the REST toggle calls.
 */
export function useMessageReactions(options: {
  // Hover affordances stay hidden while the pane is scrolling fast.
  isWheelScrolling: Ref<boolean>
}) {
  const { isWheelScrolling } = options
  const chat = useChatStore()
  const auth = useAuthStore()

  const showEmojiPicker = ref(false)
  const emojiPickerMessageId = ref<number | null>(null)
  const emojiPickerPosition = ref({ x: 0, y: 0, showBelow: false })
  const hoveredMessageId = ref<number | null>(null)

  // Emoji picker height is approximately 180px (4 rows * 36px + padding)
  const pickerHeight = 180

  function openPickerAt(x: number, y: number, messageId: number) {
    const showBelow = y < pickerHeight
    emojiPickerPosition.value = {
      x,
      y: showBelow ? y + 10 : y - 10,
      showBelow,
    }
    emojiPickerMessageId.value = messageId
    showEmojiPicker.value = true
  }

  function showReactionPicker(event: MouseEvent, messageId: number) {
    if (isWheelScrolling.value) return
    event.stopPropagation()
    const rect = (event.target as HTMLElement).getBoundingClientRect()
    const spaceAbove = rect.top
    const showBelow = spaceAbove < pickerHeight

    emojiPickerPosition.value = {
      x: rect.left,
      y: showBelow ? rect.bottom + 10 : rect.top - 10,
      showBelow,
    }
    emojiPickerMessageId.value = messageId
    showEmojiPicker.value = true
  }

  function hideReactionPicker() {
    showEmojiPicker.value = false
    emojiPickerMessageId.value = null
  }

  function handleMessageMouseEnter(messageId: number) {
    hoveredMessageId.value = messageId
  }

  function handleMessageMouseLeave(messageId: number) {
    if (hoveredMessageId.value === messageId) {
      hoveredMessageId.value = null
    }
  }

  function shouldShowEmptyReactions(message: Message): boolean {
    return !message.reactions?.length && hoveredMessageId.value === message.id && !isWheelScrolling.value
  }

  function getEmptyReactionsStyle(message: Message): CSSProperties {
    const show = shouldShowEmptyReactions(message)
    return {
      maxHeight: show ? '40px' : '0',
      opacity: show ? 1 : 0,
      marginTop: show ? '6px' : '0',
      // pointerEvents typed strictly in CSSProperties, cast to satisfy TS
      pointerEvents: (show ? 'auto' : 'none') as CSSProperties['pointerEvents'],
    } as CSSProperties
  }

  async function addReaction(messageId: number, emoji: string) {
    if (!chat.currentChannel) return

    try {
      await axios.post(
        `${API_BASE}/api/messages/${messageId}/reactions`,
        { emoji },
        { headers: { Authorization: `Bearer ${auth.token}` } }
      )
    } catch (error: any) {
      console.error('Failed to add reaction:', error)
    }

    hideReactionPicker()
  }

  async function toggleReaction(messageId: number, emoji: string) {
    if (!chat.currentChannel || !auth.user) return

    const message = chat.messages.find(m => m.id === messageId)
    if (!message) return

    const existingGroup = message.reactions?.find(r => r.emoji === emoji)
    const hasReacted = existingGroup?.users.some(u => u.id === auth.user!.id)

    try {
      if (hasReacted) {
        // Remove reaction
        await axios.delete(
          `${API_BASE}/api/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`,
          { headers: { Authorization: `Bearer ${auth.token}` } }
        )
      } else {
        // Add reaction
        await axios.post(
          `${API_BASE}/api/messages/${messageId}/reactions`,
          { emoji },
          { headers: { Authorization: `Bearer ${auth.token}` } }
        )
      }
    } catch (error: any) {
      console.error('Failed to toggle reaction:', error)
    }
  }

  function hasUserReacted(reactions: ReactionGroup[] | undefined, emoji: string): boolean {
    if (!reactions || !auth.user) return false
    const group = reactions.find(r => r.emoji === emoji)
    return group?.users.some(u => u.id === auth.user!.id) ?? false
  }

  function getReactionTooltip(reaction: ReactionGroup): string {
    const names = reaction.users.map(u => u.username).slice(0, 5)
    if (reaction.users.length > 5) {
      names.push(`还有 ${reaction.users.length - 5} 人`)
    }
    return names.join(', ')
  }

  return {
    commonEmojis,
    showEmojiPicker,
    emojiPickerMessageId,
    emojiPickerPosition,
    openPickerAt,
    showReactionPicker,
    hideReactionPicker,
    handleMessageMouseEnter,
    handleMessageMouseLeave,
    shouldShowEmptyReactions,
    getEmptyReactionsStyle,
    addReaction,
    toggleReaction,
    hasUserReacted,
    getReactionTooltip,
  }
}
