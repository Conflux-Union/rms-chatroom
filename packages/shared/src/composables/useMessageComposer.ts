import { ref, computed, watch, nextTick } from 'vue'
import type { Ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { useChatWebSocket } from './useChatWebSocket'
import { useMentionAutocomplete } from './useMentionAutocomplete'
import type { Message } from '../types'
import type { useMessageAttachments } from './useMessageAttachments'

type Attachments = ReturnType<typeof useMessageAttachments>

/**
 * Message input + send pipeline: input state, send-gating (canSend), the send
 * flow (uploads pending files first), input autosize, and mention autocomplete
 * wired onto the same textarea.
 */
export function useMessageComposer(options: {
  attachments: Attachments
  replyingTo: Ref<Message | null>
}) {
  const { attachments, replyingTo } = options
  const chat = useChatStore()
  const chatWs = useChatWebSocket()

  const messageInput = ref('')
  const messageInputRef = ref<HTMLTextAreaElement | null>(null)

  const canSend = computed(() => {
    // pendingFiles count too: attachments upload only when send is clicked
    return (messageInput.value.trim() || attachments.uploadedAttachments.value.length > 0 || attachments.pendingFiles.value.length > 0) && !attachments.isUploading.value
  })

  async function sendMessage() {
    if (!canSend.value) return

    // Upload pending files first
    if (attachments.pendingFiles.value.length > 0) {
      await attachments.uploadFiles()
    }

    const attachmentIds = attachments.uploadedAttachments.value.map(a => a.id)
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
    attachments.uploadedAttachments.value = []
    replyingTo.value = null
  }

  const {
    showMentionDropdown,
    filteredMentionUsers,
    selectedMentionIndex,
    handleInputChange,
    handleInputKeydown,
    selectMention,
  } = useMentionAutocomplete({ messageInput, messageInputRef, onSend: sendMessage })

  // Auto-grow the multiline input with its content; the 160px cap matches the
  // CSS max-height, past which the box scrolls
  function autosizeMessageInput() {
    const el = messageInputRef.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`
  }

  watch(messageInput, () => nextTick(autosizeMessageInput))

  return {
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
  }
}
