import { ref, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import type { Message } from '../types'

const API_BASE = import.meta.env.VITE_API_BASE || ''

/**
 * Reply-reference thumbnails: attachments are behind auth, so thumbnails are
 * fetched as blobs and cached by attachment id for the lifetime of the pane.
 */
export function useReplyThumbnails() {
  const chat = useChatStore()
  const auth = useAuthStore()

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
    for (const url of replyThumbnailCache.value.values()) {
      URL.revokeObjectURL(url)
    }
  })

  return {
    getReplyOriginalMessage,
    getReplyThumbnailUrl,
  }
}
