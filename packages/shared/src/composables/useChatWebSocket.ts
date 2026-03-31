import { onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { createReconnectingWebSocket } from './useReconnectingWebSocket'
import { refreshTokenIfExpired } from '../utils/tokenRefresh'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

const messageHandlers: Set<(data: any) => void> = new Set()

const chatWsInstance = createReconnectingWebSocket({
  name: 'ChatWS',
  onBeforeConnect: () => refreshTokenIfExpired(useAuthStore()),
  getUrl: () => {
    const auth = useAuthStore()
    return auth.token ? `${WS_BASE}/ws/chat?token=${auth.token}` : null
  },
  onMessage: (data) => {
    messageHandlers.forEach((handler) => {
      try {
        handler(data)
      } catch (e) {
        console.error('[ChatWS] Handler error:', e)
      }
    })
  },
})

/**
 * Global chat WebSocket singleton.
 * This connection persists across component mounts/unmounts.
 * Use this for real-time chat messages, reactions, etc.
 */
export function useChatWebSocket() {
  function onMessage(handler: (data: any) => void) {
    messageHandlers.add(handler)

    onUnmounted(() => {
      messageHandlers.delete(handler)
    })
  }

  return {
    isConnected: chatWsInstance.isConnected,
    onMessage,
    send: chatWsInstance.send,
    connect: chatWsInstance.connect,
    disconnect: chatWsInstance.disconnect,
  }
}
