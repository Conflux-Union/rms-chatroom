<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useWebSocket } from '../composables/useWebSocket'
import { Paperclip, Send, Upload, X, Image, Video, Music, FileText, File, MoreVertical } from 'lucide-vue-next'
import FilePreview from './FilePreview.vue'
import type { Attachment, Message } from '../types'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const chat = useChatStore()
const auth = useAuthStore()
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

// Edit message state
const editingMessage = ref<{ id: number; content: string } | null>(null)

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

// Mute status
const isMuted = ref(false)
const muteReason = ref('')

let ws: ReturnType<typeof useWebSocket> | null = null

function connectWebSocket(channelId: number) {
  if (ws) {
    ws.disconnect()
  }

  ws = useWebSocket(`/ws/chat/${channelId}`)

  ws.onMessage((data) => {
    if (data.type === 'message') {
      chat.addMessage({
        id: data.id,
        channel_id: data.channel_id || channelId,
        user_id: data.user_id,
        username: data.username,
        content: data.content,
        created_at: data.created_at,
        attachments: data.attachments || [],
        is_deleted: false,
        deleted_by: undefined,
        deleted_by_username: undefined,
        edited_at: undefined,
      })
      scrollToBottom()
    } else if (data.type === 'message_deleted') {
      // Update message to show as deleted
      const message = chat.messages.find(m => m.id === data.message_id)
      if (message) {
        message.is_deleted = true
        message.deleted_by = data.deleted_by
        message.deleted_by_username = data.deleted_by_username
      }
    } else if (data.type === 'message_edited') {
      // Update message content
      const message = chat.messages.find(m => m.id === data.message_id)
      if (message) {
        message.content = data.content
        message.edited_at = data.edited_at
      }
    } else if (data.type === 'error' && data.code === 'muted') {
      // User is muted
      isMuted.value = true
      muteReason.value = data.message || 'You are muted'
    }
  })

  ws.connect()
}

watch(
  () => chat.currentChannel,
  async (channel) => {
    if (channel && channel.type === 'text') {
      await chat.fetchMessages(channel.id)
      connectWebSocket(channel.id)
      await checkMuteStatus()
      await nextTick()
      scrollToBottom()
    }
  },
  { immediate: true }
)

onMounted(() => {
  // Close context menu on click outside
  document.addEventListener('click', hideContextMenu)
})

onUnmounted(() => {
  if (ws) {
    ws.disconnect()
  }
  document.removeEventListener('click', hideContextMenu)
})

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
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
  if (!canSend.value || !ws) return

  // Upload pending files first
  if (pendingFiles.value.length > 0) {
    await uploadFiles()
  }

  const attachmentIds = uploadedAttachments.value.map(a => a.id)
  const content = messageInput.value.trim()

  // Must have content or attachments
  if (!content && attachmentIds.length === 0) return
  
  ws.send({
    type: 'message',
    content: content,
    attachment_ids: attachmentIds,
  })
  
  messageInput.value = ''
  uploadedAttachments.value = []
}

function formatTime(dateStr: string) {
  const utcStr = dateStr.endsWith('Z') ? dateStr : dateStr + 'Z'
  const date = new Date(utcStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  const y = date.getFullYear()
  const m = pad(date.getMonth() + 1)
  const d = pad(date.getDate())
  const hh = pad(date.getHours())
  const mm = pad(date.getMinutes())
  return `${y}-${m}-${d} ${hh}:${mm}`
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
  const messageTime = new Date(message.created_at.endsWith('Z') ? message.created_at : message.created_at + 'Z')
  const now = new Date()
  const diffMinutes = (now.getTime() - messageTime.getTime()) / 1000 / 60
  return diffMinutes <= 2
}

function canMute(message: Message) {
  return auth.isAdmin && !isOwnMessage(message)
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

async function saveEdit() {
  if (!editingMessage.value || !chat.currentChannel) return

  const content = editingMessage.value.content.trim()
  if (!content) {
    alert('消息内容不能为空')
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
    alert(error.response?.data?.detail || '编辑消息失败')
  }
}

// Delete message function
async function deleteMessage(message: Message) {
  if (!chat.currentChannel) return

  if (!confirm('确定要撤回这条消息吗？')) return

  try {
    await axios.delete(
      `${API_BASE}/api/channels/${chat.currentChannel.id}/messages/${message.id}`,
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )
    hideContextMenu()
  } catch (error: any) {
    alert(error.response?.data?.detail || '撤回消息失败')
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
    alert('禁言成功')
    hideMuteDialog()
  } catch (error: any) {
    alert(error.response?.data?.detail || '禁言失败')
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
      isMuted.value = true
      muteReason.value = activeMute.reason || '你已被禁言'
    } else {
      isMuted.value = false
      muteReason.value = ''
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
}

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
    </div>

    <div class="messages" ref="messagesContainer">
      <div
        v-for="msg in chat.messages"
        :key="msg.id"
        class="message"
        @contextmenu="!msg.is_deleted && showContextMenu($event, msg)"
      >
        <div class="message-avatar">{{ msg.username.charAt(0).toUpperCase() }}</div>
        <div class="message-content">
          <div class="message-header">
            <span class="message-author">{{ msg.username }}</span>
            <span class="message-time">
              {{ formatTime(msg.created_at) }}
              <span v-if="msg.edited_at" class="edited-indicator">(已编辑于 {{ formatTime(msg.edited_at) }})</span>
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

          <!-- Deleted message placeholder -->
          <div v-if="msg.is_deleted" class="message-deleted">
            <span v-if="msg.deleted_by === auth.user?.id">你撤回了一条消息</span>
            <span v-else-if="msg.deleted_by_username">{{ msg.deleted_by_username }}撤回了一条消息</span>
            <span v-else>管理员撤回了一条消息</span>
          </div>

          <!-- Edit mode -->
          <div v-else-if="editingMessage && editingMessage.id === msg.id" class="message-edit">
            <textarea
              v-model="editingMessage.content"
              class="edit-textarea"
              @keydown.enter.exact.prevent="saveEdit"
              @keydown.esc="cancelEdit"
            ></textarea>
            <div class="edit-actions">
              <button class="edit-btn save" @click="saveEdit">保存</button>
              <button class="edit-btn cancel" @click="cancelEdit">取消</button>
              <span class="edit-hint">Enter 保存 • Esc 取消</span>
            </div>
          </div>

          <!-- Normal message display -->
          <div v-else>
            <div v-if="msg.content" class="message-text">
              {{ msg.content }}
            </div>
            <!-- Attachments -->
            <div v-if="msg.attachments?.length" class="message-attachments">
              <FilePreview
                v-for="att in msg.attachments"
                :key="att.id"
                :attachment="att"
              />
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
          <div v-if="uploadProgress.get(file.name)" class="progress-bar">
            <div class="progress-fill" :style="{ width: uploadProgress.get(file.name) + '%' }"></div>
          </div>
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

    <div class="chat-input">
      <input type="file" ref="fileInput" @change="handleFileSelect" multiple hidden />
      <button class="attach-btn" @click="triggerFileSelect" title="添加附件" :disabled="isMuted">
        <Paperclip :size="20" />
      </button>
      <input
        v-model="messageInput"
        :placeholder="isMuted ? muteReason : `发送消息到 #${chat.currentChannel?.name || ''}`"
        @keyup.enter="sendMessage"
        class="message-input"
        :disabled="isMuted"
      />
      <button
        class="send-btn"
        @click="sendMessage"
        :disabled="!canSend || isMuted"
        :class="{ active: canSend && !isMuted }"
      >
        <Send :size="20" />
      </button>
    </div>

    <!-- Context Menu -->
    <div
      v-if="contextMenu.visible && contextMenu.message"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <button
        v-if="canEdit(contextMenu.message)"
        class="context-menu-item"
        @click="startEdit(contextMenu.message)"
      >
        编辑消息
      </button>
      <button
        v-if="canDelete(contextMenu.message)"
        class="context-menu-item"
        @click="deleteMessage(contextMenu.message)"
      >
        撤回消息
      </button>
      <button
        v-if="canMute(contextMenu.message)"
        class="context-menu-item"
        @click="showMuteDialog(contextMenu.message)"
      >
        禁言用户
      </button>
    </div>

    <!-- Mute Dialog -->
    <div v-if="muteDialog.visible" class="modal-overlay" @click="hideMuteDialog">
      <div class="modal-content" @click.stop>
        <h3 class="modal-title">禁言用户: {{ muteDialog.username }}</h3>

        <div class="form-group">
          <label>禁言范围</label>
          <select v-model="muteDialog.scope" class="form-select">
            <option value="channel">当前频道</option>
            <option value="server">当前服务器</option>
            <option value="global">全局禁言</option>
          </select>
        </div>

        <div class="form-group">
          <label>禁言时长</label>
          <select v-model="muteDialog.duration" class="form-select">
            <option value="permanent">永久</option>
            <option value="10m">10分钟</option>
            <option value="1h">1小时</option>
            <option value="1d">1天</option>
            <option value="custom">自定义</option>
          </select>
        </div>

        <div v-if="muteDialog.duration === 'custom'" class="form-group">
          <label>自定义时长（分钟）</label>
          <input
            v-model.number="muteDialog.customMinutes"
            type="number"
            min="1"
            class="form-input"
          />
        </div>

        <div class="form-group">
          <label>禁言原因（可选）</label>
          <textarea
            v-model="muteDialog.reason"
            class="form-textarea"
            placeholder="输入禁言原因..."
          ></textarea>
        </div>

        <div class="modal-actions">
          <button class="modal-btn cancel" @click="hideMuteDialog">取消</button>
          <button class="modal-btn confirm" @click="confirmMute">确认禁言</button>
        </div>
      </div>
    </div>
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
  margin-bottom: 16px;
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

.progress-bar {
  height: 3px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 4px;
}

.progress-fill {
  height: 100%;
  background: var(--color-accent);
  transition: width 0.2s;
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
  flex: 1;
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

/* Context Menu */
.context-menu {
  position: fixed;
  background: var(--surface-glass);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-md);
  padding: 4px;
  min-width: 150px;
  box-shadow: var(--shadow-lg);
  z-index: 1000;
}

.context-menu-item {
  width: 100%;
  padding: 8px 12px;
  background: none;
  border: none;
  color: var(--color-text-main);
  text-align: left;
  cursor: pointer;
  border-radius: var(--radius-sm);
  font-size: 14px;
  transition: all var(--transition-fast);
}

.context-menu-item:hover {
  background: var(--surface-glass-input);
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
  gap: 8px;
}

.edit-textarea {
  width: 100%;
  min-height: 60px;
  padding: 8px 12px;
  background: var(--surface-glass-input);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--radius-md);
  color: var(--color-text-main);
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}

.edit-textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.edit-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.edit-btn {
  padding: 6px 12px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.edit-btn.save {
  background: var(--color-accent);
  color: white;
}

.edit-btn.save:hover {
  opacity: 0.9;
}

.edit-btn.cancel {
  background: var(--surface-glass-input);
  color: var(--color-text-main);
}

.edit-btn.cancel:hover {
  background: var(--surface-glass-input-focus);
}

.edit-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-left: auto;
}

/* Edited Badge */
.edited-badge {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-left: 4px;
}

/* Modal Overlay */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.modal-content {
  background: var(--surface-glass);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-lg);
  padding: 24px;
  min-width: 400px;
  max-width: 90vw;
  box-shadow: var(--shadow-xl);
}

.modal-title {
  margin: 0 0 20px 0;
  color: var(--color-text-main);
  font-size: 18px;
  font-weight: 600;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  color: var(--color-text-main);
  font-size: 14px;
  font-weight: 500;
}

.form-select,
.form-input,
.form-textarea {
  width: 100%;
  padding: 8px 12px;
  background: var(--surface-glass-input);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--radius-md);
  color: var(--color-text-main);
  font-size: 14px;
  font-family: inherit;
}

.form-select:focus,
.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.form-textarea {
  min-height: 80px;
  resize: vertical;
}

.modal-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 20px;
}

.modal-btn {
  padding: 8px 16px;
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.modal-btn.cancel {
  background: var(--surface-glass-input);
  color: var(--color-text-main);
}

.modal-btn.cancel:hover {
  background: var(--surface-glass-input-focus);
}

.modal-btn.confirm {
  background: var(--color-accent);
  color: white;
}

.modal-btn.confirm:hover {
  opacity: 0.9;
}

@media (max-width: 768px) {
  .modal-content {
    min-width: 0;
    width: 90vw;
  }
}
</style>
