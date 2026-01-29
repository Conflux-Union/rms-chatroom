<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted, computed } from 'vue'
import type { CSSProperties } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useChatWebSocket } from '../composables/useChatWebSocket'
import { useReadPosition } from '../composables/useReadPosition'
import { useMentionNotification } from '../composables/useMentionNotification'
import { formatDateTime, parseUTCDateTime, isWithinMinutes } from '../utils/datetime'
import {
  NDropdown,
  NModal,
  NForm,
  NFormItem,
  NSelect,
  NInput,
  NInputNumber,
  NButton,
  NSpace,
  NProgress,
  useDialog,
} from 'naive-ui'
import { Paperclip, Send, Upload, X, Image, Video, Music, FileText, File, MoreVertical, ArrowUp, Reply, CornerUpLeft, SmilePlus } from 'lucide-vue-next'
import FilePreview from './FilePreview.vue'
import UserAvatar from './UserAvatar.vue'
import type { Attachment, Message, ReactionGroup } from '../types'
import axios from 'axios'

const dialog = useDialog()

const API_BASE = import.meta.env.VITE_API_BASE || ''

const chat = useChatStore()
const auth = useAuthStore()
const chatWs = useChatWebSocket()
const messageInput = ref('')
const messagesContainer = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

// File upload state
const pendingFiles = ref<File[]>([])
const uploadedAttachments = ref<Attachment[]>([])
const uploadProgress = ref<Map<string, number>>(new Map())
const isUploading = ref(false)
const isDragging = ref(false)

// Context menu state
const contextMenu = ref<{
  visible: boolean
  x: number
  y: number
  message: Message | null
}>({
  visible: false,
  x: 0,
  y: 0,
  message: null,
})

// Dropdown options for context menu
const contextMenuOptions = computed(() => {
  const msg = contextMenu.value.message
  if (!msg) return []
  const opts: Array<{ label: string; key: string }> = []
  opts.push({ label: '添加表情', key: 'reaction' })
  opts.push({ label: '回复', key: 'reply' })
  if (canEdit(msg)) opts.push({ label: '编辑', key: 'edit' })
  if (canDelete(msg)) opts.push({ label: '删除', key: 'delete' })
  if (canMute(msg)) opts.push({ label: '禁言用户', key: 'mute' })
  return opts
})

function handleContextMenuSelect(key: string) {
  const msg = contextMenu.value.message
  if (!msg) return
  const menuX = contextMenu.value.x
  const menuY = contextMenu.value.y
  hideContextMenu()
  if (key === 'reaction') {
    // Show emoji picker at context menu position
    const pickerHeight = 180
    const showBelow = menuY < pickerHeight
    emojiPickerPosition.value = {
      x: menuX,
      y: showBelow ? menuY + 10 : menuY - 10,
      showBelow,
    }
    emojiPickerMessageId.value = msg.id
    showEmojiPicker.value = true
  } else if (key === 'reply') startReply(msg)
  else if (key === 'edit') startEdit(msg)
  else if (key === 'delete') confirmDeleteMessage(msg)
  else if (key === 'mute') showMuteDialog(msg)
}

// Mute dialog options
const scopeOptions = [
  { label: '当前频道', value: 'channel' },
  { label: '当前服务器', value: 'server' },
  { label: '全局', value: 'global' },
]

const durationOptions = [
  { label: '永久', value: 'permanent' },
  { label: '10 分钟', value: '10m' },
  { label: '1 小时', value: '1h' },
  { label: '1 天', value: '1d' },
  { label: '自定义', value: 'custom' },
]

// Edit message state
const editingMessage = ref<{ id: number; content: string } | null>(null)

// Reply state
const replyingTo = ref<Message | null>(null)

// Mention autocomplete state
const mentionQuery = ref('')
const mentionStartIndex = ref(-1)
const showMentionDropdown = ref(false)
const mentionDropdownPosition = ref({ x: 0, y: 0 })
const selectedMentionIndex = ref(0)
const messageInputRef = ref<HTMLInputElement | null>(null)

// Extract unique users from messages for mention autocomplete
const channelUsers = computed(() => {
  const userMap = new Map<number, { id: number; username: string }>()
  for (const msg of chat.messages) {
    if (!userMap.has(msg.user_id)) {
      userMap.set(msg.user_id, { id: msg.user_id, username: msg.username })
    }
  }
  return Array.from(userMap.values())
})

// Filtered users for mention autocomplete
const filteredMentionUsers = computed(() => {
  if (!mentionQuery.value) return channelUsers.value.slice(0, 10)
  const query = mentionQuery.value.toLowerCase()
  return channelUsers.value
    .filter(u => u.username.toLowerCase().includes(query))
    .slice(0, 10)
})

// Mute dialog state
const muteDialog = ref<{
  visible: boolean
  userId: number | null
  username: string
  scope: 'global' | 'server' | 'channel'
  duration: 'permanent' | '10m' | '1h' | '1d' | 'custom'
  customMinutes: number
  reason: string
}>({
  visible: false,
  userId: null,
  username: '',
  scope: 'channel',
  duration: 'permanent',
  customMinutes: 60,
  reason: '',
})

// Mute status - combine local check with WebSocket error
const localMuted = ref(false)
const localMuteReason = ref('')
const isMuted = computed(() => localMuted.value || chat.isMutedByWs)
const muteReason = computed(() => localMuteReason.value || chat.muteReasonByWs)

// Reaction state
const showEmojiPicker = ref(false)
const emojiPickerMessageId = ref<number | null>(null)
const emojiPickerPosition = ref({ x: 0, y: 0, showBelow: false })
const isWheelScrolling = ref(false)
const isScrollActive = ref(false)
let wheelScrollTimeout: ReturnType<typeof setTimeout> | null = null
const hoveredMessageId = ref<number | null>(null)
const latestVisibleMessageId = ref<number | null>(null)
const messageIndexMap = new Map<number, number>()
const visibleMessageIndices = new Set<number>()
const maxVisibleIndex = ref<number | null>(null)
let messageObserver: IntersectionObserver | null = null

// Common emojis for quick reactions
const commonEmojis = ['👍', '❤️', '😂', '😮', '😢', '🎉', '🔥', '👀']

// Read position tracking
const {
  lastReadMessageId,
  showContinueReading,
  saveReadPosition,
  markChannelAsRead,
  initForChannel,
  dismissContinueReading,
} = useReadPosition()
let scrollSaveTimeout: ReturnType<typeof setTimeout> | null = null

// Mention notification tracking
const { clearChannelMention } = useMentionNotification()

watch(
  () => chat.currentChannel,
  async (channel) => {
    if (channel && channel.type === 'text') {
      await chat.fetchMessages(channel.id)
      await checkMuteStatus()

      // Initialize read position tracking
      const latestMessageId = chat.messages.length > 0
        ? chat.messages[chat.messages.length - 1].id
        : null
      initForChannel(channel.id, latestMessageId)

      await nextTick()
      scrollToBottom()
      refreshMessageObserver()

      // Mark as read after user has stayed on channel for 2 seconds
      // This prevents immediate clearing of mention badges
      setTimeout(() => {
        if (chat.currentChannel?.id === channel.id) {
          markChannelAsRead(channel.id)
          clearChannelMention(channel.id)
        }
      }, 2000)
    }
  },
  { immediate: true }
)

// Auto-scroll when new messages arrive
watch(
  () => chat.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
    refreshMessageObserver()
  }
)

onMounted(() => {
  // Close context menu on click outside
  document.addEventListener('click', hideContextMenu)
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

// Save read position when user stops scrolling (debounced)
function handleMessagesScroll() {
  if (!chat.currentChannel || chat.messages.length === 0) return

  isScrollActive.value = true

  // Debounce: only save after user stops scrolling for 500ms
  if (scrollSaveTimeout) {
    clearTimeout(scrollSaveTimeout)
  }

  scrollSaveTimeout = setTimeout(() => {
    isScrollActive.value = false
    if (latestVisibleMessageId.value && chat.currentChannel) {
      saveReadPosition(chat.currentChannel.id, latestVisibleMessageId.value)
      // Update read timestamp and clear mention notification when user scrolls
      markChannelAsRead(chat.currentChannel.id)
      clearChannelMention(chat.currentChannel.id)
      console.log('[ChatArea] Updated read timestamp on scroll')
    }
  }, 120)
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
}

// Scroll to a specific message by ID
function scrollToMessage(messageId: number) {
  const container = messagesContainer.value
  if (!container) return

  const messageEl = container.querySelector(`[data-message-id="${messageId}"]`)
  if (messageEl) {
    messageEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
    // Highlight the message briefly
    messageEl.classList.add('message-highlight')
    setTimeout(() => {
      messageEl.classList.remove('message-highlight')
    }, 2000)
  }
  dismissContinueReading()
}

// File handling
function triggerFileSelect() {
  fileInput.value?.click()
}

function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files) {
    pendingFiles.value = [...pendingFiles.value, ...Array.from(target.files)]
    target.value = '' // Reset for same file selection
  }
}

function handleDragOver(event: DragEvent) {
  event.preventDefault()
  isDragging.value = true
}

function handleDragLeave(event: DragEvent) {
  event.preventDefault()
  isDragging.value = false
}

function handleDrop(event: DragEvent) {
  event.preventDefault()
  isDragging.value = false
  
  if (event.dataTransfer?.files) {
    pendingFiles.value = [...pendingFiles.value, ...Array.from(event.dataTransfer.files)]
  }
}

function removePendingFile(index: number) {
  pendingFiles.value.splice(index, 1)
}

function removeUploadedAttachment(index: number) {
  uploadedAttachments.value.splice(index, 1)
}

async function uploadFiles() {
  if (!chat.currentChannel || pendingFiles.value.length === 0) return

  isUploading.value = true
  const channelId = chat.currentChannel.id

  for (const file of pendingFiles.value) {
    const attachment = await chat.uploadFile(channelId, file, (progress) => {
      uploadProgress.value.set(file.name, progress)
    })
    if (attachment) {
      uploadedAttachments.value.push(attachment)
    }
    uploadProgress.value.delete(file.name)
  }

  pendingFiles.value = []
  isUploading.value = false
}

const canSend = computed(() => {
  return (messageInput.value.trim() || uploadedAttachments.value.length > 0) && !isUploading.value
})

async function sendMessage() {
  if (!canSend.value) return

  // Upload pending files first
  if (pendingFiles.value.length > 0) {
    await uploadFiles()
  }

  const attachmentIds = uploadedAttachments.value.map(a => a.id)
  const content = messageInput.value.trim()

  // Must have content or attachments
  if (!content && attachmentIds.length === 0) return

  if (!chat.currentChannel?.id) return

  chatWs.send({
    type: 'message',
    channel_id: chat.currentChannel.id,
    content: content,
    attachment_ids: attachmentIds,
    reply_to_id: replyingTo.value?.id || null,
  })

  messageInput.value = ''
  uploadedAttachments.value = []
  replyingTo.value = null
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function getFileIconComponent(file: File) {
  if (file.type.startsWith('image/')) return Image
  if (file.type.startsWith('video/')) return Video
  if (file.type.startsWith('audio/')) return Music
  if (file.type === 'application/pdf') return FileText
  return File
}

function getAttachmentIconComponent(att: Attachment) {
  if (att.content_type.startsWith('image/')) return Image
  if (att.content_type.startsWith('video/')) return Video
  if (att.content_type.startsWith('audio/')) return Music
  if (att.content_type === 'application/pdf') return FileText
  return File
}

// Context menu functions
function showContextMenu(event: MouseEvent, message: Message) {
  event.preventDefault()
  event.stopPropagation() // Prevent event from bubbling to document click listener
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    message,
  }
}

function hideContextMenu() {
  contextMenu.value.visible = false
  contextMenu.value.message = null
}

function isOwnMessage(message: Message) {
  return message.user_id === auth.user?.id
}

function canEdit(message: Message) {
  return isOwnMessage(message) && !message.is_deleted
}

function canDelete(message: Message) {
  if (message.is_deleted) return false
  if (auth.isAdmin) return true
  if (!isOwnMessage(message)) return false

  // Check 2-minute time limit for non-admin users
  return isWithinMinutes(message.created_at, 2)
}

function canMute(message: Message) {
  return auth.isAdmin && !isOwnMessage(message)
}

// Message grouping: Discord-style consecutive message merging
const MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES = 1
const MESSAGE_GROUP_TOTAL_THRESHOLD_MINUTES = 7

function shouldGroupWithPrevious(index: number): boolean {
  if (index === 0) return false

  const currentMsg = chat.messages[index]
  const prevMsg = chat.messages[index - 1]

  // Different user, don't group
  if (currentMsg.user_id !== prevMsg.user_id) return false

  // Previous message is deleted, don't group (keep visual separation)
  if (prevMsg.is_deleted) return false

  const currentTime = parseUTCDateTime(currentMsg.created_at).getTime()
  const prevTime = parseUTCDateTime(prevMsg.created_at).getTime()
  const diffMinutes = (currentTime - prevTime) / 1000 / 60

  // Adjacent messages must be within 1 minute
  if (diffMinutes > MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES) return false

  // Find the first message in this group (walk backwards)
  let firstMsgIndex = index - 1
  while (firstMsgIndex > 0) {
    const msg = chat.messages[firstMsgIndex]
    const prevMsgInChain = chat.messages[firstMsgIndex - 1]

    // Different user breaks the chain
    if (msg.user_id !== prevMsgInChain.user_id) break
    // Deleted message breaks the chain
    if (prevMsgInChain.is_deleted) break

    const msgTime = parseUTCDateTime(msg.created_at).getTime()
    const prevMsgTime = parseUTCDateTime(prevMsgInChain.created_at).getTime()
    const chainDiff = (msgTime - prevMsgTime) / 1000 / 60

    // Gap > 1 minute breaks the chain
    if (chainDiff > MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES) break

    firstMsgIndex--
  }

  // Check total time from first message in group
  const firstMsgTime = parseUTCDateTime(chat.messages[firstMsgIndex].created_at).getTime()
  const totalDiffMinutes = (currentTime - firstMsgTime) / 1000 / 60

  return totalDiffMinutes <= MESSAGE_GROUP_TOTAL_THRESHOLD_MINUTES
}

// Get the latest edited_at timestamp from a message group (for header display)
function getGroupLatestEditedAt(index: number): string | undefined {
  // Find the first message in this group (the one with header)
  let firstMsgIndex = index
  while (firstMsgIndex > 0 && shouldGroupWithPrevious(firstMsgIndex)) {
    firstMsgIndex--
  }

  // Find the last message in this group
  let lastMsgIndex = index
  while (lastMsgIndex < chat.messages.length - 1 && shouldGroupWithPrevious(lastMsgIndex + 1)) {
    lastMsgIndex++
  }

  // Find the latest edited_at in the group
  let latestEditedAt: string | undefined
  for (let i = firstMsgIndex; i <= lastMsgIndex; i++) {
    const msg = chat.messages[i]
    if (msg.edited_at) {
      if (!latestEditedAt || new Date(msg.edited_at) > new Date(latestEditedAt)) {
        latestEditedAt = msg.edited_at
      }
    }
  }

  return latestEditedAt
}

// Edit message functions
function startEdit(message: Message) {
  editingMessage.value = {
    id: message.id,
    content: message.content,
  }
  hideContextMenu()
}

function cancelEdit() {
  editingMessage.value = null
}

// Reply functions
function startReply(message: Message) {
  replyingTo.value = message
  hideContextMenu()
}

function cancelReply() {
  replyingTo.value = null
}

// Mention autocomplete functions
function handleInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  const value = input.value
  const cursorPos = input.selectionStart || 0

  // Find if we're in a mention context (after @)
  const textBeforeCursor = value.slice(0, cursorPos)
  const lastAtIndex = textBeforeCursor.lastIndexOf('@')

  if (lastAtIndex !== -1) {
    // Check if there's a space between @ and cursor (would end the mention)
    const textAfterAt = textBeforeCursor.slice(lastAtIndex + 1)
    if (!textAfterAt.includes(' ')) {
      mentionStartIndex.value = lastAtIndex
      mentionQuery.value = textAfterAt
      showMentionDropdown.value = true
      selectedMentionIndex.value = 0
      return
    }
  }

  // Not in mention context
  showMentionDropdown.value = false
  mentionQuery.value = ''
  mentionStartIndex.value = -1
}

function handleInputKeydown(event: KeyboardEvent) {
  if (showMentionDropdown.value && filteredMentionUsers.value.length > 0) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      selectedMentionIndex.value = Math.min(
        selectedMentionIndex.value + 1,
        filteredMentionUsers.value.length - 1
      )
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      selectedMentionIndex.value = Math.max(selectedMentionIndex.value - 1, 0)
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      const selectedUser = filteredMentionUsers.value[selectedMentionIndex.value]
      if (selectedUser) {
        selectMention(selectedUser)
      }
    } else if (event.key === 'Escape') {
      event.preventDefault()
      showMentionDropdown.value = false
    }
  } else if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function selectMention(user: { id: number; username: string }) {
  if (mentionStartIndex.value === -1) return

  const input = messageInputRef.value
  if (!input) return

  const value = messageInput.value
  const beforeMention = value.slice(0, mentionStartIndex.value)
  const afterCursor = value.slice(input.selectionStart || mentionStartIndex.value + mentionQuery.value.length + 1)

  // Insert @username with a space after
  messageInput.value = `${beforeMention}@${user.username} ${afterCursor}`

  // Reset mention state
  showMentionDropdown.value = false
  mentionQuery.value = ''
  mentionStartIndex.value = -1

  // Focus back on input and set cursor position
  nextTick(() => {
    if (input) {
      const newCursorPos = beforeMention.length + user.username.length + 2 // +2 for @ and space
      input.focus()
      input.setSelectionRange(newCursorPos, newCursorPos)
    }
  })
}

// Render message content with @mention highlighting
function renderMessageContent(content: string): string {
  // Escape HTML to prevent XSS
  const escapeHtml = (text: string) => {
    const div = document.createElement('div')
    div.textContent = text
    return div.innerHTML
  }

  // First escape the content
  const escaped = escapeHtml(content)

  // Then highlight @mentions
  // Match @username (word characters only)
  // IMPORTANT: Also escape the captured username to prevent XSS via escaped entities
  const mentionRegex = /@(\w+)/g
  return escaped.replace(mentionRegex, (_match, username) => {
    return `<span class="mention-highlight">@${escapeHtml(username)}</span>`
  })
}

async function saveEdit() {
  if (!editingMessage.value || !chat.currentChannel) return

  const content = editingMessage.value.content.trim()
  if (!content) {
    dialog.warning({ title: '警告', content: '消息内容不能为空' })
    return
  }

  try {
    await axios.patch(
      `${API_BASE}/api/channels/${chat.currentChannel.id}/messages/${editingMessage.value.id}`,
      { content },
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )
    editingMessage.value = null
  } catch (error: any) {
    dialog.error({
      title: '错误',
      content: error.response?.data?.detail || '编辑消息失败',
    })
  }
}

// Delete message function
function confirmDeleteMessage(message: Message) {
  dialog.warning({
    title: '删除消息',
    content: '确定要删除这条消息吗？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => deleteMessage(message),
  })
}

async function deleteMessage(message: Message) {
  if (!chat.currentChannel) return

  try {
    await axios.delete(
      `${API_BASE}/api/channels/${chat.currentChannel.id}/messages/${message.id}`,
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )
  } catch (error: any) {
    dialog.error({
      title: '错误',
      content: error.response?.data?.detail || '删除消息失败',
    })
  }
}

// Mute functions
function showMuteDialog(message: Message) {
  muteDialog.value = {
    visible: true,
    userId: message.user_id,
    username: message.username,
    scope: 'channel',
    duration: 'permanent',
    customMinutes: 60,
    reason: '',
  }
  hideContextMenu()
}

function hideMuteDialog() {
  muteDialog.value.visible = false
  muteDialog.value.userId = null
}

async function confirmMute() {
  if (!muteDialog.value.userId || !chat.currentChannel) return

  let mutedUntil: string | null = null
  if (muteDialog.value.duration !== 'permanent') {
    let minutes = 0
    switch (muteDialog.value.duration) {
      case '10m': minutes = 10; break
      case '1h': minutes = 60; break
      case '1d': minutes = 1440; break
      case 'custom': minutes = muteDialog.value.customMinutes; break
    }
    const until = new Date()
    until.setMinutes(until.getMinutes() + minutes)
    mutedUntil = until.toISOString()
  }

  const payload: any = {
    user_id: muteDialog.value.userId,
    scope: muteDialog.value.scope,
    reason: muteDialog.value.reason || null,
    muted_until: mutedUntil,
  }

  // Add server_id or channel_id based on scope
  if (muteDialog.value.scope === 'server' && chat.currentChannel.server_id) {
    payload.server_id = chat.currentChannel.server_id
  } else if (muteDialog.value.scope === 'channel') {
    payload.channel_id = chat.currentChannel.id
  }

  try {
    await axios.post(`${API_BASE}/api/mute`, payload, {
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    dialog.success({ title: '成功', content: '用户已被禁言' })
    hideMuteDialog()
  } catch (error: any) {
    dialog.error({
      title: '错误',
      content: error.response?.data?.detail || '禁言用户失败',
    })
  }
}

// Check mute status
async function checkMuteStatus() {
  if (!auth.user) return

  try {
    const resp = await axios.get(`${API_BASE}/api/mute/user/${auth.user.id}`, {
      headers: { Authorization: `Bearer ${auth.token}` },
    })

    const mutes = resp.data
    const currentChannel = chat.currentChannel
    if (!currentChannel) return

    // Check if user is muted in current context
    const activeMute = mutes.find((mute: any) => {
      if (mute.scope === 'global') return true
      if (mute.scope === 'server' && mute.server_id === currentChannel.server_id) return true
      if (mute.scope === 'channel' && mute.channel_id === currentChannel.id) return true
      return false
    })

    if (activeMute) {
      localMuted.value = true
      localMuteReason.value = activeMute.reason || '你已被禁言'
    } else {
      localMuted.value = false
      localMuteReason.value = ''
      // Also clear WebSocket mute status when API says not muted
      chat.setMutedByWs(false, '')
    }
  } catch (error) {
    console.error('Failed to check mute status:', error)
  }
}

// Close context menu when clicking outside
function handleClickOutside(event: MouseEvent) {
  if (contextMenu.value.visible) {
    hideContextMenu()
  }
  // Close emoji picker when clicking outside
  if (showEmojiPicker.value) {
    showEmojiPicker.value = false
    emojiPickerMessageId.value = null
  }
}

// Reaction functions
function showReactionPicker(event: MouseEvent, messageId: number) {
  if (isWheelScrolling.value) return
  event.stopPropagation()
  const rect = (event.target as HTMLElement).getBoundingClientRect()
  // Emoji picker height is approximately 180px (4 rows * 36px + padding)
  const pickerHeight = 180
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

// Get the original message for reply reference (to access attachments)
function getReplyOriginalMessage(replyToId: number): Message | undefined {
  return chat.messages.find(m => m.id === replyToId)
}

// Cache for reply thumbnail blob URLs (attachment id -> blob url)
const replyThumbnailCache = ref<Map<number, string>>(new Map())

// Load reply thumbnail with auth header
async function loadReplyThumbnail(attachmentId: number, attachmentUrl: string): Promise<string | null> {
  // Check cache first
  if (replyThumbnailCache.value.has(attachmentId)) {
    return replyThumbnailCache.value.get(attachmentId)!
  }

  try {
    const res = await fetch(`${API_BASE}${attachmentUrl}?inline=1`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    if (res.ok) {
      const blob = await res.blob()
      const blobUrl = URL.createObjectURL(blob)
      replyThumbnailCache.value.set(attachmentId, blobUrl)
      return blobUrl
    }
  } catch (e) {
    console.error('Failed to load reply thumbnail:', e)
  }
  return null
}

// Get cached thumbnail or trigger load
function getReplyThumbnailUrl(attachmentId: number, attachmentUrl: string): string | null {
  const cached = replyThumbnailCache.value.get(attachmentId)
  if (cached) return cached

  // Trigger async load (will update cache and re-render)
  loadReplyThumbnail(attachmentId, attachmentUrl)
  return null
}

// Cleanup blob URLs on unmount
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
  document.removeEventListener('click', hideContextMenu)
  // Revoke all cached blob URLs
  for (const url of replyThumbnailCache.value.values()) {
    URL.revokeObjectURL(url)
  }
})
</script>

<template>
  <div
    class="chat-area"
    @dragover="handleDragOver"
    @dragleave="handleDragLeave"
    @drop="handleDrop"
    @click="handleClickOutside"
  >
    <!-- Drag overlay -->
    <div v-if="isDragging" class="drag-overlay">
      <div class="drag-content">
        <Upload class="drag-icon" :size="48" />
        <span class="drag-text">拖放文件到这里上传</span>
      </div>
    </div>

    <div class="chat-header">
      <span class="channel-hash">#</span>
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>

      <!-- Continue reading button -->
      <Transition name="continue-reading">
        <button
          v-if="showContinueReading && lastReadMessageId"
          class="continue-reading-btn"
          @click="scrollToMessage(lastReadMessageId)"
        >
          <ArrowUp :size="16" />
          <span>回到上次阅读位置</span>
        </button>
      </Transition>
    </div>

    <div
      class="messages"
      :class="{ 'is-scroll-active': isScrollActive }"
      ref="messagesContainer"
      @scroll="handleMessagesScroll"
      @wheel.passive="handleWheelScroll"
      @touchmove.passive="handleWheelScroll"
    >
      <div
        v-for="(msg, index) in chat.messages"
        :key="msg.id"
        :data-message-id="msg.id"
        class="message"
        :class="{ 'message-grouped': shouldGroupWithPrevious(index) }"
        @contextmenu="!msg.is_deleted && showContextMenu($event, msg)"
        @mouseenter="handleMessageMouseEnter(msg.id)"
        @mouseleave="handleMessageMouseLeave(msg.id)"
      >
        <!-- Avatar: hidden placeholder for grouped messages to maintain alignment -->
        <div class="message-avatar" :class="{ 'avatar-hidden': shouldGroupWithPrevious(index) }">
          <template v-if="!shouldGroupWithPrevious(index)">
            <UserAvatar :username="msg.username" :size="40" />
          </template>
        </div>
        <div class="message-content">
          <!-- Header: hidden for grouped messages -->
          <div v-if="!shouldGroupWithPrevious(index)" class="message-header">
            <span class="message-author">{{ msg.username }}</span>
            <span class="message-time">
              {{ formatDateTime(msg.created_at) }}
              <span v-if="getGroupLatestEditedAt(index)" class="edited-indicator">(已编辑于 {{ formatDateTime(getGroupLatestEditedAt(index)!) }})</span>
            </span>
            <button
              v-if="!msg.is_deleted"
              class="message-menu-btn"
              @click="showContextMenu($event, msg)"
              title="更多选项"
            >
              <MoreVertical :size="16" />
            </button>
          </div>
          <!-- Grouped message: show menu button on hover -->
          <button
            v-else-if="!msg.is_deleted"
            class="message-menu-btn grouped-menu-btn"
            @click="showContextMenu($event, msg)"
            title="更多选项"
          >
            <MoreVertical :size="16" />
          </button>

          <!-- Deleted message placeholder -->
          <div v-if="msg.is_deleted" class="message-deleted">
            <span v-if="msg.deleted_by === auth.user?.id">你撤回了一条消息</span>
            <span v-else-if="msg.deleted_by_username">{{ msg.deleted_by_username }}撤回了一条消息</span>
            <span v-else>管理员撤回了一条消息</span>
          </div>

          <!-- Edit mode -->
          <div v-else-if="editingMessage && editingMessage.id === msg.id" class="message-edit">
            <NInput
              v-model:value="editingMessage.content"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 5 }"
              @keydown.enter.exact.prevent="saveEdit"
              @keydown.esc="cancelEdit"
            />
            <NSpace size="small" style="margin-top: 8px">
              <NButton size="small" type="primary" @click="saveEdit">保存</NButton>
              <NButton size="small" @click="cancelEdit">取消</NButton>
              <span class="edit-hint">回车保存，Esc 取消</span>
            </NSpace>
          </div>

          <!-- Normal message display -->
          <div v-else class="message-body">
            <!-- Reply reference -->
            <div
              v-if="msg.reply_to"
              class="message-reply-ref"
              @click="scrollToMessage(msg.reply_to.id)"
            >
              <CornerUpLeft :size="14" class="reply-icon" />
              <span class="reply-author">{{ msg.reply_to.username }}</span>
              <template v-if="msg.reply_to.content">
                <span class="reply-content">{{ msg.reply_to.content }}</span>
              </template>
              <template v-else>
                <!-- No text content, check for attachments in original message -->
                <template v-if="getReplyOriginalMessage(msg.reply_to.id)?.attachments?.length">
                  <img
                    v-if="getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].content_type.startsWith('image/') && getReplyThumbnailUrl(getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].id, getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].url)"
                    :src="getReplyThumbnailUrl(getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].id, getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].url)!"
                    class="reply-thumbnail"
                    alt="image"
                  />
                  <span v-else-if="getReplyOriginalMessage(msg.reply_to.id)!.attachments![0].content_type.startsWith('image/')" class="reply-content">[图片]</span>
                  <span v-else class="reply-content">[附件]</span>
                </template>
                <span v-else class="reply-content">[附件]</span>
              </template>
            </div>
            <div v-if="msg.content" class="message-text" v-html="renderMessageContent(msg.content)"></div>
            <!-- Attachments -->
            <div v-if="msg.attachments?.length" class="message-attachments">
              <FilePreview
                v-for="att in msg.attachments"
                :key="att.id"
                :attachment="att"
              />
            </div>
            <!-- Reactions -->
            <div v-if="msg.reactions?.length" class="message-reactions">
              <button
                v-for="reaction in msg.reactions"
                :key="reaction.emoji"
                class="reaction-badge"
                :class="{ 'reaction-active': hasUserReacted(msg.reactions, reaction.emoji) }"
                :title="getReactionTooltip(reaction)"
                @click="toggleReaction(msg.id, reaction.emoji)"
              >
                <span class="reaction-emoji">{{ reaction.emoji }}</span>
                <span class="reaction-count">{{ reaction.count }}</span>
              </button>
              <button
                class="reaction-add-btn"
                @click="showReactionPicker($event, msg.id)"
                title="添加表情"
              >
                <SmilePlus :size="16" />
              </button>
            </div>
            <!-- Add reaction button when no reactions yet -->
            <div v-else class="message-reactions-empty" :style="getEmptyReactionsStyle(msg)">
              <button
                class="reaction-add-btn"
                @click="showReactionPicker($event, msg.id)"
                title="添加表情"
              >
                <SmilePlus :size="16" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Pending files preview -->
    <div v-if="pendingFiles.length > 0 || uploadedAttachments.length > 0" class="pending-files">
      <!-- Uploading files -->
      <div v-for="(file, index) in pendingFiles" :key="'pending-' + file.name" class="pending-file">
        <component :is="getFileIconComponent(file)" class="file-icon-svg" :size="18" />
        <div class="file-info">
          <span class="file-name">{{ file.name }}</span>
          <span class="file-size">{{ formatFileSize(file.size) }}</span>
          <NProgress
            v-if="uploadProgress.get(file.name)"
            type="line"
            :percentage="uploadProgress.get(file.name)"
            :show-indicator="false"
            :height="3"
            style="margin-top: 4px"
          />
        </div>
        <button class="remove-btn" @click="removePendingFile(index)" :disabled="isUploading">
          <X :size="16" />
        </button>
      </div>
      <!-- Uploaded attachments -->
      <div v-for="(att, index) in uploadedAttachments" :key="'uploaded-' + att.id" class="pending-file uploaded">
        <component :is="getAttachmentIconComponent(att)" class="file-icon-svg" :size="18" />
        <div class="file-info">
          <span class="file-name">{{ att.filename }}</span>
          <span class="file-size">{{ formatFileSize(att.size) }}</span>
        </div>
        <button class="remove-btn" @click="removeUploadedAttachment(index)">
          <X :size="16" />
        </button>
      </div>
    </div>

    <!-- Reply preview bar -->
    <div v-if="replyingTo" class="reply-preview-bar">
      <div class="reply-preview-content">
        <Reply :size="16" class="reply-preview-icon" />
        <span class="reply-preview-label">回复</span>
        <span class="reply-preview-author">{{ replyingTo.username }}</span>
        <span class="reply-preview-text">
          <template v-if="replyingTo.content">{{ replyingTo.content.slice(0, 100) }}{{ replyingTo.content.length > 100 ? '...' : '' }}</template>
          <template v-else-if="replyingTo.attachments?.length">
            <Image v-if="replyingTo.attachments[0].content_type.startsWith('image/')" :size="14" style="vertical-align: middle; margin-right: 4px;" />
            <span>[{{ replyingTo.attachments[0].content_type.startsWith('image/') ? '图片' : '附件' }}]</span>
          </template>
        </span>
      </div>
      <button class="reply-preview-close" @click="cancelReply">
        <X :size="16" />
      </button>
    </div>

    <div class="chat-input">
      <input type="file" ref="fileInput" @change="handleFileSelect" multiple hidden />
      <button class="attach-btn" @click="triggerFileSelect" title="添加附件" :disabled="isMuted">
        <Paperclip :size="20" />
      </button>
      <div class="input-wrapper">
        <input
          ref="messageInputRef"
          v-model="messageInput"
          :placeholder="isMuted ? muteReason : `发送消息到 #${chat.currentChannel?.name || ''}`"
          @keydown="handleInputKeydown"
          @input="handleInputChange"
          class="message-input"
          :disabled="isMuted"
        />
        <!-- Mention autocomplete dropdown -->
        <div
          v-if="showMentionDropdown && filteredMentionUsers.length > 0"
          class="mention-dropdown"
        >
          <div
            v-for="(user, index) in filteredMentionUsers"
            :key="user.id"
            class="mention-item"
            :class="{ 'mention-item-selected': index === selectedMentionIndex }"
            @click="selectMention(user)"
            @mouseenter="selectedMentionIndex = index"
          >
            <UserAvatar :username="user.username" :size="24" class="mention-avatar" />
            <span class="mention-username">{{ user.username }}</span>
          </div>
        </div>
      </div>
      <button
        class="send-btn"
        @click="sendMessage"
        :disabled="!canSend || isMuted"
        :class="{ active: canSend && !isMuted }"
      >
        <Send :size="20" />
      </button>
    </div>

    <!-- Context Menu (NDropdown) -->
    <NDropdown
      trigger="manual"
      placement="bottom-start"
      :show="contextMenu.visible && contextMenuOptions.length > 0"
      :options="contextMenuOptions"
      :x="contextMenu.x"
      :y="contextMenu.y"
      @select="handleContextMenuSelect"
      @clickoutside="hideContextMenu"
    />

    <!-- Emoji Picker Popup -->
    <Teleport to="body">
      <div
        v-if="showEmojiPicker && emojiPickerMessageId"
        class="emoji-picker-overlay"
        @click="hideReactionPicker"
      >
        <div
          class="emoji-picker"
          :class="{ 'emoji-picker-below': emojiPickerPosition.showBelow }"
          :style="{ left: emojiPickerPosition.x + 'px', top: emojiPickerPosition.y + 'px' }"
          @click.stop
        >
          <div class="emoji-picker-grid">
            <button
              v-for="emoji in commonEmojis"
              :key="emoji"
              class="emoji-btn"
              @click="addReaction(emojiPickerMessageId!, emoji)"
            >
              {{ emoji }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Mute Dialog -->
    <NModal
      v-model:show="muteDialog.visible"
      preset="card"
      :title="`禁言用户: ${muteDialog.username}`"
      :bordered="false"
      style="width: 420px; max-width: 90vw"
    >
      <NForm label-placement="top">
        <NFormItem label="范围">
          <NSelect v-model:value="muteDialog.scope" :options="scopeOptions" />
        </NFormItem>

        <NFormItem label="时长">
          <NSelect v-model:value="muteDialog.duration" :options="durationOptions" />
        </NFormItem>

        <NFormItem v-if="muteDialog.duration === 'custom'" label="自定义时长（分钟）">
          <NInputNumber
            v-model:value="muteDialog.customMinutes"
            :min="1"
          />
        </NFormItem>

        <NFormItem label="原因（可选）">
          <NInput
            v-model:value="muteDialog.reason"
            type="textarea"
            placeholder="输入禁言原因..."
            :rows="3"
          />
        </NFormItem>
      </NForm>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="hideMuteDialog">取消</NButton>
          <NButton type="primary" @click="confirmMute">确认</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  position: relative;
}

.drag-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.drag-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 32px 48px;
  background: var(--surface-glass);
  border: 2px dashed var(--color-accent);
  border-radius: var(--radius-lg);
}

.drag-icon {
  color: var(--color-accent);
}

.drag-text {
  font-size: 16px;
  color: var(--color-text-main);
  font-weight: 500;
}

.chat-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
}

.channel-hash {
  color: var(--color-text-muted);
  font-size: 24px;
  margin-right: 8px;
}

.channel-name {
  font-weight: 600;
  color: var(--color-text-main);
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.message {
  display: flex;
  padding: 4px 0;
}

/* Non-grouped messages have larger top margin */
.message:not(.message-grouped) {
  margin-top: 16px;
}

/* First message doesn't need top margin */
.message:first-child {
  margin-top: 0;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--color-gradient-primary);
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: 600;
  color: #fff;
  margin-right: 16px;
  flex-shrink: 0;
  overflow: hidden;
}

.message-avatar .avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.message-avatar .avatar-fallback {
  font-size: 16px;
}

.message-content {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.message-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
  position: relative;
}

.message-menu-btn {
  position: absolute;
  right: 0;
  top: 0;
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px;
  display: none;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.message:hover .message-menu-btn {
  display: flex;
}

.message-menu-btn:hover {
  background: var(--surface-glass-input);
  color: var(--color-text-main);
}

/* Grouped messages (Discord-style consecutive message merging) */
.message-grouped {
  margin-top: 2px;
  padding-top: 0;
}

.message-grouped .message-content {
  position: relative;
}

.avatar-hidden {
  visibility: hidden;
  width: 40px;
  height: 0;
  margin-right: 16px;
}

.grouped-menu-btn {
  position: absolute;
  right: 0;
  top: 0;
}

.message-author {
  font-weight: 500;
  color: var(--color-text-main);
}

.message-time {
  font-size: 12px;
  color: var(--color-text-muted);
}

.edited-indicator {
  font-size: 11px;
  color: var(--color-text-muted);
  font-style: italic;
  margin-left: 4px;
}

.message-text {
  color: var(--color-text-main);
  line-height: 1.4;
  word-wrap: break-word;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.message-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.pending-files {
  padding: 8px 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  background: var(--surface-glass);
  border-top: 1px solid rgba(128, 128, 128, 0.2);
}

.pending-file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--surface-glass-input);
  border-radius: var(--radius-md);
  max-width: 250px;
}

.pending-file.uploaded {
  background: rgba(34, 197, 94, 0.2);
}

.pending-file .file-icon-svg {
  color: var(--color-accent);
  flex-shrink: 0;
}

.pending-file .file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.pending-file .file-name {
  font-size: 13px;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pending-file .file-size {
  font-size: 11px;
  color: var(--color-text-muted);
}

.remove-btn {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.remove-btn:hover {
  color: var(--color-text-main);
}

.remove-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.chat-input {
  padding: 0 16px 24px;
  display: flex;
  gap: 8px;
  align-items: center;
}

.attach-btn,
.send-btn {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--surface-glass-input);
  color: var(--color-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.attach-btn:hover,
.send-btn:hover {
  background: var(--surface-glass-input-focus);
  color: var(--color-text-main);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-btn.active {
  background: var(--color-accent);
  color: white;
}

.message-input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  font-size: 14px;
  box-sizing: border-box;
  transition: all var(--transition-fast);
}

.message-input::placeholder {
  color: var(--color-text-muted);
}

.message-input:focus {
  outline: none;
  background: var(--surface-glass-input-focus);
  border-color: rgba(255, 255, 255, 0.5);
  box-shadow: var(--shadow-md);
}

/* Mobile Responsive */
@media (max-width: 768px) {
  .chat-header {
    height: 44px;
    padding: 0 12px;
  }

  .channel-hash {
    font-size: 20px;
  }

  .channel-name {
    font-size: 15px;
  }

  .messages {
    padding: 12px;
  }

  .message {
    margin-bottom: 12px;
  }

  .message-avatar {
    width: 32px;
    height: 32px;
    margin-right: 10px;
    font-size: 14px;
  }

  .message-author {
    font-size: 14px;
  }

  .message-text {
    font-size: 14px;
  }

  .chat-input {
    padding: 0 12px 16px;
  }

  .chat-input input {
    padding: 10px 14px;
    font-size: 15px;
  }
}

/* Message Deleted */
.message-deleted {
  color: var(--color-text-muted);
  font-style: italic;
  font-size: 13px;
}

/* Message Edit */
.message-edit {
  display: flex;
  flex-direction: column;
}

.edit-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-left: auto;
}

/* Continue Reading Button */
.continue-reading-btn {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--color-gradient-primary);
  color: white;
  border: none;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: var(--shadow-glow);
  animation: btn-pulse 2s ease-in-out infinite;
}

.continue-reading-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 25px rgba(252, 121, 97, 0.4);
}

@keyframes btn-pulse {
  0%, 100% {
    box-shadow: 0 4px 15px rgba(252, 121, 97, 0.3);
  }
  50% {
    box-shadow: 0 4px 25px rgba(252, 121, 97, 0.5);
  }
}

/* Continue reading transition */
.continue-reading-enter-active,
.continue-reading-leave-active {
  transition: all 0.3s ease;
}

.continue-reading-enter-from,
.continue-reading-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

/* Message highlight animation */
.message-highlight {
  animation: highlight-pulse 2s ease-out;
}

@keyframes highlight-pulse {
  0% {
    background: rgba(var(--color-accent-rgb, 99, 102, 241), 0.3);
  }
  100% {
    background: transparent;
  }
}

/* Reply Reference in Message */
.message-reply-ref {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  margin-bottom: 4px;
  background: var(--surface-glass);
  border-left: 2px solid var(--color-accent);
  border-radius: var(--radius-sm);
  font-size: 12px;
  cursor: pointer;
  transition: background var(--transition-fast);
  max-width: 100%;
  overflow: hidden;
}

.message-reply-ref:hover {
  background: var(--surface-glass-input);
}

.message-reply-ref .reply-icon {
  color: var(--color-text-muted);
  flex-shrink: 0;
}

.message-reply-ref .reply-author {
  color: var(--color-accent);
  font-weight: 500;
  flex-shrink: 0;
}

.message-reply-ref .reply-content {
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.message-reply-ref .reply-thumbnail {
  height: 36px;
  max-width: 60px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

/* Reply Preview Bar */
.reply-preview-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: var(--surface-glass);
  border-top: 1px solid rgba(128, 128, 128, 0.2);
  border-left: 3px solid var(--color-accent);
}

.reply-preview-content {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.reply-preview-icon {
  color: var(--color-accent);
  flex-shrink: 0;
}

.reply-preview-label {
  color: var(--color-text-muted);
  font-size: 12px;
  flex-shrink: 0;
}

.reply-preview-author {
  color: var(--color-accent);
  font-weight: 500;
  font-size: 13px;
  flex-shrink: 0;
}

.reply-preview-text {
  color: var(--color-text-muted);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reply-preview-close {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.reply-preview-close:hover {
  background: var(--surface-glass-input);
  color: var(--color-text-main);
}

/* Input wrapper for mention dropdown positioning */
.input-wrapper {
  flex: 1;
  position: relative;
}

/* Mention Autocomplete Dropdown */
.mention-dropdown {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  margin-bottom: 8px;
  background: var(--surface-glass);
  border: 1px solid rgba(128, 128, 128, 0.3);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  max-height: 200px;
  overflow-y: auto;
  z-index: 100;
  backdrop-filter: blur(20px);
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.mention-item:hover,
.mention-item-selected {
  background: var(--surface-glass-input);
}

.mention-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-gradient-primary);
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: 600;
  font-size: 12px;
  color: #fff;
  flex-shrink: 0;
}

.mention-username {
  color: var(--color-text-main);
  font-size: 14px;
  font-weight: 500;
}

/* Mention highlight in message content */
.message-text :deep(.mention-highlight) {
  color: var(--color-accent);
  background: rgba(var(--color-accent-rgb, 99, 102, 241), 0.15);
  padding: 0 4px;
  border-radius: 4px;
  font-weight: 500;
  cursor: pointer;
}

.message-text :deep(.mention-highlight:hover) {
  background: rgba(var(--color-accent-rgb, 99, 102, 241), 0.25);
}

/* Reactions */
.message-reactions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

/* Empty reactions container - collapses when not hovered */
.message-reactions-empty {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 0;
  overflow: hidden;
  opacity: 0;
  margin-top: 0;
  transition: max-height 0.2s ease, opacity 0.2s ease, margin-top 0.2s ease;
}


.reaction-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: var(--surface-glass-input);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  transition: all var(--transition-fast);
  font-size: 14px;
}

.reaction-badge:hover {
  background: var(--surface-glass-input-focus);
}

.reaction-badge.reaction-active {
  background: rgba(var(--color-accent-rgb, 99, 102, 241), 0.2);
  border-color: var(--color-accent);
}

.reaction-emoji {
  font-size: 16px;
  line-height: 1;
}

.reaction-count {
  font-size: 12px;
  color: var(--color-text-muted);
  font-weight: 500;
}

.reaction-active .reaction-count {
  color: var(--color-accent);
}

.reaction-add-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: var(--surface-glass-input);
  border: 1px dashed rgba(128, 128, 128, 0.3);
  border-radius: 12px;
  cursor: pointer;
  color: var(--color-text-muted);
  transition: all var(--transition-fast);
}

.reaction-add-btn:hover {
  background: var(--surface-glass-input-focus);
  color: var(--color-text-main);
  border-style: solid;
}

/* Emoji Picker */
.emoji-picker-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
}

.emoji-picker {
  position: fixed;
  transform: translateY(-100%);
  background: var(--surface-glass);
  border: 1px solid rgba(128, 128, 128, 0.3);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  padding: 8px;
  backdrop-filter: blur(20px);
  z-index: 1001;
}

.emoji-picker.emoji-picker-below {
  transform: translateY(0);
}

.emoji-picker-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
}

.emoji-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.emoji-btn:hover {
  background: var(--surface-glass-input);
}
</style>
