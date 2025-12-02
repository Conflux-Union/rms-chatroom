<script setup lang="ts">
import { ref, watch, nextTick, onUnmounted, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useWebSocket } from '../composables/useWebSocket'
import { Paperclip, Send, Upload, X, Image, Video, Music, FileText, File } from 'lucide-vue-next'
import FilePreview from './FilePreview.vue'
import type { Attachment } from '../types'

const chat = useChatStore()
const messageInput = ref('')
const messagesContainer = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

// File upload state
const pendingFiles = ref<File[]>([])
const uploadedAttachments = ref<Attachment[]>([])
const uploadProgress = ref<Map<string, number>>(new Map())
const isUploading = ref(false)
const isDragging = ref(false)

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
      })
      scrollToBottom()
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
      await nextTick()
      scrollToBottom()
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (ws) {
    ws.disconnect()
  }
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
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
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
</script>

<template>
  <div 
    class="chat-area"
    @dragover="handleDragOver"
    @dragleave="handleDragLeave"
    @drop="handleDrop"
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
      <div v-for="msg in chat.messages" :key="msg.id" class="message">
        <div class="message-avatar">{{ msg.username.charAt(0).toUpperCase() }}</div>
        <div class="message-content">
          <div class="message-header">
            <span class="message-author">{{ msg.username }}</span>
            <span class="message-time">{{ formatTime(msg.created_at) }}</span>
          </div>
          <div v-if="msg.content" class="message-text">{{ msg.content }}</div>
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
      <button class="attach-btn" @click="triggerFileSelect" title="添加附件">
        <Paperclip :size="20" />
      </button>
      <input
        v-model="messageInput"
        :placeholder="`发送消息到 #${chat.currentChannel?.name || ''}`"
        @keyup.enter="sendMessage"
        class="message-input"
      />
      <button 
        class="send-btn" 
        @click="sendMessage" 
        :disabled="!canSend"
        :class="{ active: canSend }"
      >
        <Send :size="20" />
      </button>
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
}

.message-author {
  font-weight: 500;
  color: var(--color-text-main);
}

.message-time {
  font-size: 12px;
  color: var(--color-text-muted);
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
</style>
