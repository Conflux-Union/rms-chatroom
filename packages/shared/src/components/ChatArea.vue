<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { parseMessagePermalink, splitMessagePermalinks } from '../utils/messagePermalink'
import { renderMessageHtml } from '../utils/markdown'
import { formatDateTime, isWithinMinutes } from '../utils/datetime'
import {
  shouldGroupWithPrevious as shouldGroupWithPreviousIn,
  getGroupLatestEditedAt as getGroupLatestEditedAtIn,
} from '../utils/messageGrouping'
import {
  ZmDropdown,
  ZmModal,
  ZmForm,
  ZmFormItem,
  ZmSelect,
  ZmInput,
  ZmInputNumber,
  ZmButton,
  ZmSpace,
  ZmProgress,
  dialog,
} from './ui'
import { Paperclip, Send, Upload, X, Image, MoreVertical, Reply, CornerUpLeft, SmilePlus, Radio } from 'lucide-vue-next'
import FilePreview from './FilePreview.vue'
import ForwardQuoteCard from './ForwardQuoteCard.vue'
import SourceBadge from './SourceBadge.vue'
import type { Message } from '../types'
import { isTauri } from '../index'
import { useMessageViewport } from '../composables/useMessageViewport'
import { useMessageAttachments } from '../composables/useMessageAttachments'
import { useMessageComposer } from '../composables/useMessageComposer'
import { useMessageActions } from '../composables/useMessageActions'
import { useMessageReactions } from '../composables/useMessageReactions'
import { useMuteDialog } from '../composables/useMuteDialog'
import { useReplyThumbnails } from '../composables/useReplyThumbnails'

const chat = useChatStore()
const auth = useAuthStore()
const router = useRouter()

const messagesContainer = ref<HTMLElement | null>(null)

// FORWARD channels are bridged to the game network via ChatBridge: users can
// chat both ways, the type only swaps the header hash for a sync icon.
const isForwardChannel = computed(() => chat.currentChannel?.type === 'FORWARD')

// Feature composables, composed here; destructuring below keeps the template's
// original flat names.
const attachments = useMessageAttachments()
const replyThumbnails = useReplyThumbnails()
const actions = useMessageActions()
const mute = useMuteDialog()
const viewport = useMessageViewport({
  messagesContainer,
  onChannelEntered: mute.checkMuteStatus,
})
const reactions = useMessageReactions({ isWheelScrolling: viewport.isWheelScrolling })
const composer = useMessageComposer({ attachments, replyingTo: actions.replyingTo })

const {
  isScrollActive,
  firstUnreadId,
  handleMessagesScroll,
  handleWheelScroll,
  scrollToMessage,
} = viewport
const {
  fileInput,
  pendingFiles,
  uploadedAttachments,
  uploadProgress,
  isUploading,
  isDragging,
  triggerFileSelect,
  handleFileSelect,
  handleDragOver,
  handleDragLeave,
  handleDrop,
  removePendingFile,
  removeUploadedAttachment,
  formatFileSize,
  getFileIconComponent,
  getAttachmentIconComponent,
} = attachments
const {
  messageInput,
  messageInputRef,
  canSend,
  sendMessage,
  showMentionDropdown,
  filteredMentionUsers,
  selectedMentionIndex,
  handleInputChange,
  handleInputKeydown,
  selectMention,
} = composer
const {
  editingMessage,
  replyingTo,
  startEdit,
  cancelEdit,
  startReply,
  cancelReply,
  saveEdit,
  confirmDeleteMessage,
} = actions
const {
  commonEmojis,
  showEmojiPicker,
  emojiPickerMessageId,
  emojiPickerPosition,
  openPickerAt,
  showReactionPicker,
  hideReactionPicker,
  handleMessageMouseEnter,
  handleMessageMouseLeave,
  getEmptyReactionsStyle,
  addReaction,
  toggleReaction,
  hasUserReacted,
  getReactionTooltip,
} = reactions
const {
  muteDialog,
  scopeOptions,
  durationOptions,
  isMuted,
  muteReason,
  showMuteDialog,
  hideMuteDialog,
  confirmMute,
} = mute
const {
  getReplyOriginalMessage,
  getReplyThumbnailUrl,
} = replyThumbnails

// Thin local wrappers keeping the template's original one-argument calls.
function shouldGroupWithPrevious(index: number): boolean {
  return shouldGroupWithPreviousIn(chat.messages, index)
}

function getGroupLatestEditedAt(index: number): string | undefined {
  return getGroupLatestEditedAtIn(chat.messages, index)
}

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
  if (!msg.is_deleted) opts.push({ label: '复制消息链接', key: 'copy_link' })
  if (canEdit(msg)) opts.push({ label: '编辑', key: 'edit' })
  if (canDelete(msg)) opts.push({ label: '删除', key: 'delete' })
  if (canMute(msg)) opts.push({ label: '禁言用户', key: 'mute' })
  return opts
})

function handleContextMenuSelect(key: string | number) {
  const msg = contextMenu.value.message
  if (!msg) return
  const menuX = contextMenu.value.x
  const menuY = contextMenu.value.y
  hideContextMenu()
  if (key === 'reaction') openPickerAt(menuX, menuY, msg.id)
  else if (key === 'reply') startReply(msg)
  else if (key === 'copy_link') copyMessageLink(msg.id)
  else if (key === 'edit') startEdit(msg)
  else if (key === 'delete') confirmDeleteMessage(msg)
  else if (key === 'mute') showMuteDialog(msg)
}

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

// Permalink for a message: /server/channel/message, resolved against the
// current origin (the hash router on desktop yields origin/path#/...).
async function copyMessageLink(messageId: number) {
  const serverId = chat.currentServer?.id
  const channelId = chat.currentChannel?.id
  if (!serverId || !channelId) return
  const href = router.resolve({
    name: 'MessagePath',
    params: {
      serverId: String(serverId),
      channelId: String(channelId),
      messageId: String(messageId),
    },
  }).href
  const url = new URL(href, window.location.origin).toString()
  try {
    await navigator.clipboard.writeText(url)
    showLinkCopiedToast()
  } catch (e) {
    console.warn('[chat] copy message link failed:', e)
  }
}

// Brief confirmation for the copied permalink.
const showLinkCopied = ref(false)
let linkCopiedTimer: ReturnType<typeof setTimeout> | null = null
function showLinkCopiedToast() {
  showLinkCopied.value = true
  if (linkCopiedTimer) clearTimeout(linkCopiedTimer)
  linkCopiedTimer = setTimeout(() => {
    showLinkCopied.value = false
  }, 1500)
}

// Markdown links open in a new tab on the web; the desktop webview blocks
// target=_blank, so route clicks through the shell opener instead. Our own
// message permalinks navigate in-app through the deep-link route.
async function handleMessageLinkClick(event: MouseEvent) {
  const anchor = (event.target as HTMLElement | null)?.closest?.('a')
  if (!anchor) return
  const href = anchor.getAttribute('href')
  if (!href || href.startsWith('#')) return
  const link = parseMessagePermalink(href, window.location.origin)
  if (link) {
    event.preventDefault()
    router.push({
      name: 'MessagePath',
      params: {
        serverId: String(link.serverId),
        channelId: String(link.channelId),
        messageId: String(link.messageId),
      },
    })
    return
  }
  if (!isTauri) return
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('open_external', { url: href })
  } catch {
    dialog.error({ title: '错误', content: '打开链接失败' })
  }
}

// Close context menu and reaction picker when clicking outside
function handleClickOutside() {
  if (contextMenu.value.visible) {
    hideContextMenu()
  }
  if (showEmojiPicker.value) {
    hideReactionPicker()
  }
}

onMounted(() => {
  // Close context menu on click outside
  document.addEventListener('click', hideContextMenu)
})

onUnmounted(() => {
  document.removeEventListener('click', hideContextMenu)
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
      <span v-if="!isForwardChannel" class="channel-hash">#</span>
      <Radio v-else class="channel-hash channel-hash-icon" :size="16" />
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
    </div>

    <div
      class="messages"
      :class="{ 'is-scroll-active': isScrollActive }"
      ref="messagesContainer"
      @scroll="handleMessagesScroll"
      @wheel.passive="handleWheelScroll"
      @touchmove.passive="handleWheelScroll"
    >
      <div v-if="chat.isLoadingOlder" class="loading-more-indicator">
        加载更早的消息…
      </div>
      <template v-for="(msg, index) in chat.messages" :key="msg.id">
        <div v-if="msg.id === firstUnreadId" class="unread-divider">
          <span class="unread-divider-line"></span>
          <span class="unread-divider-label">新消息</span>
          <span class="unread-divider-line"></span>
        </div>
        <div
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
            <img
              v-if="msg.avatar_url"
              :src="msg.avatar_url"
              :alt="msg.username"
              class="avatar-img"
              @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
            />
            <span v-else class="avatar-fallback">{{ msg.username.charAt(0).toUpperCase() }}</span>
          </template>
        </div>
        <div class="message-content">
          <!-- Header: hidden for grouped messages -->
          <div v-if="!shouldGroupWithPrevious(index)" class="message-header">
            <span class="message-author">{{ msg.username }}</span>
            <SourceBadge v-if="msg.source_platform" :msg="msg" />
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
            <ZmInput
              v-model:value="editingMessage.content"
              type="textarea"
              :rows="3"
              @keydown.enter.exact.prevent="saveEdit"
              @keydown.esc="cancelEdit"
            />
            <ZmSpace size="small" style="margin-top: 8px">
              <ZmButton size="small" type="primary" @click="saveEdit">保存</ZmButton>
              <ZmButton size="small" @click="cancelEdit">取消</ZmButton>
              <span class="edit-hint">回车保存，Esc 取消</span>
            </ZmSpace>
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
            <!-- Degraded quote: the quoted source message was never forwarded
                 to this channel, so only the bot-provided preview is shown. -->
            <div
              v-else-if="msg.forward_meta?.quote"
              class="message-reply-ref message-reply-static"
            >
              <CornerUpLeft :size="14" class="reply-icon" />
              <span class="reply-author">{{ msg.forward_meta.quote.nickname }}</span>
              <span v-if="msg.forward_meta.quote.content" class="reply-content">{{ msg.forward_meta.quote.content }}</span>
            </div>
            <!-- Message permalinks (standalone or embedded) render as quote
                 cards splitting the markdown text around them -->
            <template v-if="msg.content && splitMessagePermalinks(msg.content).length">
              <template v-for="(seg, segIndex) in splitMessagePermalinks(msg.content)" :key="segIndex">
                <div
                  v-if="seg.type === 'text'"
                  class="message-text"
                  @click="handleMessageLinkClick"
                  v-html="renderMessageHtml(seg.text)"
                ></div>
                <ForwardQuoteCard v-else :link="seg.link" />
              </template>
            </template>
            <div v-else-if="msg.content" class="message-text" @click="handleMessageLinkClick" v-html="renderMessageHtml(msg.content)"></div>
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
      </template>
    </div>

    <!-- Pending files preview -->
    <div v-if="pendingFiles.length > 0 || uploadedAttachments.length > 0" class="pending-files">
      <!-- Uploading files -->
      <div v-for="(file, index) in pendingFiles" :key="'pending-' + file.name" class="pending-file">
        <component :is="getFileIconComponent(file)" class="file-icon-svg" :size="18" />
        <div class="file-info">
          <span class="file-name">{{ file.name }}</span>
          <span class="file-size">{{ formatFileSize(file.size) }}</span>
          <ZmProgress
            v-if="uploadProgress.get(file.name)"
            :percentage="uploadProgress.get(file.name)"
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
        <textarea
          ref="messageInputRef"
          v-model="messageInput"
          rows="1"
          :placeholder="isMuted ? muteReason : `发送消息到 #${chat.currentChannel?.name || ''}`"
          @keydown="handleInputKeydown"
          @input="handleInputChange"
          class="message-input"
          :disabled="isMuted"
        ></textarea>
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
            <div class="mention-avatar">{{ user.username.charAt(0).toUpperCase() }}</div>
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

    <!-- Context Menu -->
    <ZmDropdown
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
    <ZmModal
      v-model:show="muteDialog.visible"
      :title="`禁言用户: ${muteDialog.username}`"
      style="width: 420px; max-width: 90vw"
    >
      <ZmForm>
        <ZmFormItem label="范围">
          <ZmSelect v-model:value="muteDialog.scope" :options="scopeOptions" />
        </ZmFormItem>

        <ZmFormItem label="时长">
          <ZmSelect v-model:value="muteDialog.duration" :options="durationOptions" />
        </ZmFormItem>

        <ZmFormItem v-if="muteDialog.duration === 'custom'" label="自定义时长（分钟）">
          <ZmInputNumber
            v-model:value="muteDialog.customMinutes"
            :min="1"
          />
        </ZmFormItem>

        <ZmFormItem label="原因（可选）">
          <ZmInput
            v-model:value="muteDialog.reason"
            type="textarea"
            placeholder="输入禁言原因..."
            :rows="3"
          />
        </ZmFormItem>
      </ZmForm>

      <template #footer>
        <ZmSpace justify="end">
          <ZmButton @click="hideMuteDialog">取消</ZmButton>
          <ZmButton type="primary" @click="confirmMute">确认</ZmButton>
        </ZmSpace>
      </template>
    </ZmModal>

    <!-- Copied-permalink confirmation -->
    <Transition name="copy-toast">
      <div v-if="showLinkCopied" class="copy-link-toast">消息链接已复制</div>
    </Transition>
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
  /* Frosted band so the channel name reads over the vivid ink canvas. */
  background: var(--zhimo-surface-bg);
  backdrop-filter: var(--zhimo-surface-blur);
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
  position: relative;
}

/* Loading indicator shown while prepending older messages.
   Absolutely positioned so it does not affect scrollHeight, which keeps
   scroll-position restoration in loadOlderMessages exact. */
.loading-more-indicator {
  position: absolute;
  top: 8px;
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 14px;
  font-size: 13px;
  color: var(--text-muted, #b9bbbe);
  background: var(--background-secondary, #2f3136);
  border-radius: 8px;
  z-index: 5;
  pointer-events: none;
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
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-seal);
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
  /* Each message floats as a frosted bubble on the vivid ink canvas: the ink
     stays sharp in the gutters/gaps while text reads against frosted paper. */
  padding: 6px 12px;
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-surface-bg);
  backdrop-filter: var(--zhimo-surface-blur);
}

.message-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  /* row gap keeps the timestamp readable when it wraps below the author */
  gap: 2px 8px;
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
  /* very long usernames wrap instead of overflowing horizontally */
  overflow-wrap: anywhere;
}

.message-time {
  font-size: 12px;
  color: var(--color-text-muted);
  /* never shrink into a vertical strip; wrap to the next line instead */
  flex-shrink: 0;
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

/* Markdown-rendered body: keep blocks compact for chat density */
.message-text :deep(p) {
  margin: 0 0 4px;
}

.message-text :deep(p:last-child) {
  margin-bottom: 0;
}

.message-text :deep(a) {
  color: var(--color-accent);
  text-decoration: underline;
}

.message-text :deep(code) {
  padding: 1px 5px;
  background: var(--surface-glass-hover);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
}

.message-text :deep(pre) {
  margin: 4px 0;
  padding: 10px 12px;
  background: var(--surface-glass-hover);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow-x: auto;
}

.message-text :deep(pre code) {
  padding: 0;
  background: none;
  border: none;
}

.message-text :deep(blockquote) {
  margin: 4px 0;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--color-border-strong);
  color: var(--color-text-muted);
}

.message-text :deep(blockquote p:last-child) {
  margin-bottom: 0;
}

.message-text :deep(ul),
.message-text :deep(ol) {
  margin: 4px 0;
  padding-left: 22px;
}

.message-text :deep(h1),
.message-text :deep(h2),
.message-text :deep(h3),
.message-text :deep(h4),
.message-text :deep(h5),
.message-text :deep(h6) {
  margin: 8px 0 4px;
  line-height: 1.3;
}

/* Headings stay chat-sized, only the top level stands out */
.message-text :deep(h1) {
  font-size: 1.2em;
}

.message-text :deep(h2) {
  font-size: 1.1em;
}

.message-text :deep(h3),
.message-text :deep(h4),
.message-text :deep(h5),
.message-text :deep(h6) {
  font-size: 1em;
}

.message-text :deep(h1:first-child),
.message-text :deep(h2:first-child),
.message-text :deep(h3:first-child),
.message-text :deep(h4:first-child),
.message-text :deep(h5:first-child),
.message-text :deep(h6:first-child) {
  margin-top: 0;
}

.message-text :deep(hr) {
  margin: 8px 0;
  border: none;
  border-top: 1px solid var(--color-border);
}

.message-text :deep(table) {
  /* Block keeps wide tables inside the message column and scrollable */
  display: block;
  overflow-x: auto;
  margin: 4px 0;
  border-collapse: collapse;
  font-size: 0.95em;
}

.message-text :deep(th),
.message-text :deep(td) {
  padding: 3px 8px;
  border: 1px solid var(--color-border);
  text-align: left;
}

.message-text :deep(th) {
  background: var(--surface-glass-hover);
}

.message-text :deep(input[type='checkbox']) {
  margin-right: 4px;
  vertical-align: middle;
  accent-color: var(--color-accent);
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
  /* Bottom-align so attach/send stay put while the textarea grows */
  align-items: flex-end;
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
  font-family: inherit;
  line-height: 1.4;
  box-sizing: border-box;
  /* Multiline input: grows with content via autosizeMessageInput, then scrolls */
  resize: none;
  max-height: 160px;
  overflow-y: auto;
  transition: background var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.message-input::placeholder {
  color: var(--color-text-muted);
}

.message-input:focus {
  outline: none;
  background: var(--surface-glass-input-focus);
  border-color: var(--zhimo-seal);
  box-shadow: var(--zhimo-focus-ring);
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

  .chat-input textarea {
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

/* Unread divider */
.unread-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  margin: 4px 0;
}

.unread-divider-line {
  flex: 1;
  height: 1px;
  background: var(--zhimo-seal-hover);
}

.unread-divider-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--zhimo-seal);
  white-space: nowrap;
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

/* Degraded forward quote: same layout as reply-ref but not clickable */
.message-reply-static {
  cursor: default;
}

.message-reply-static:hover {
  background: var(--surface-glass);
}

/* Forwarded-message quote cards live in ForwardQuoteCard.vue (their styles
   must scope to the recursive component, not to ChatArea). */

/* Source badges for forwarded messages live in SourceBadge.vue (their styles
   must scope to the badge component, not to ChatArea). */

/* Sync channel header icon (replaces the # hash in FORWARD channels) */
.channel-hash-icon {
  display: flex;
  align-items: center;
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
  border-radius: var(--zhimo-radius-sm);
  background: var(--zhimo-seal);
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

/* Copied-permalink toast */
.copy-link-toast {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 300;
  padding: 8px 18px;
  border-radius: var(--radius-md);
  background: var(--zhimo-surface-bg);
  border: 1px solid var(--zhimo-border-strong);
  box-shadow: var(--zhimo-shadow-sm);
  color: var(--color-text-main);
  font-size: 0.85rem;
}

.copy-toast-enter-active,
.copy-toast-leave-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.copy-toast-enter-from,
.copy-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-6px);
}
</style>
