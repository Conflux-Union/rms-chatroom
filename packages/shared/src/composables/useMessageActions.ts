import { ref } from 'vue'
import axios from 'axios'
import type { Message } from '../types'
import { dialog } from '../components/ui'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'

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
