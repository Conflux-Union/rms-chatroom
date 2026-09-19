import { ref } from 'vue'
import axios from 'axios'
import type { Message } from '../types'
import { dialog } from '../components/ui'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { t } from '../i18n'

const API_BASE = import.meta.env.VITE_API_BASE || ''

/**
 * Message authoring actions: edit-in-place state, reply state, and message
 * deletion. Context-menu dispatch stays in ChatArea; these are the operations
 * it forwards to.
 */
export function useMessageActions() {
  const chat = useChatStore()
  const auth = useAuthStore()

  // Edit message state
  const editingMessage = ref<{ id: number; content: string } | null>(null)

  // Reply state
  const replyingTo = ref<Message | null>(null)

  function startEdit(message: Message) {
    editingMessage.value = {
      id: message.id,
      content: message.content,
    }
  }

  function cancelEdit() {
    editingMessage.value = null
  }

  function startReply(message: Message) {
    replyingTo.value = message
  }

  function cancelReply() {
    replyingTo.value = null
  }

  async function saveEdit() {
    if (!editingMessage.value || !chat.currentChannel) return

    const content = editingMessage.value.content.trim()
    if (!content) {
      dialog.warning({ title: t('chat.warning'), content: t('chat.emptyMessage') })
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
        title: t('chat.error'),
        content: error.response?.data?.detail || t('chat.editFailed'),
      })
    }
  }

  function confirmDeleteMessage(message: Message) {
    dialog.warning({
      title: t('chat.deleteMessageTitle'),
      content: t('chat.deleteMessageConfirm'),
      positiveText: t('common.delete'),
      negativeText: t('common.cancel'),
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
        title: t('chat.error'),
        content: error.response?.data?.detail || t('chat.deleteFailed'),
      })
    }
  }

  return {
    editingMessage,
    replyingTo,
    startEdit,
    cancelEdit,
    startReply,
    cancelReply,
    saveEdit,
    confirmDeleteMessage,
    deleteMessage,
  }
}
