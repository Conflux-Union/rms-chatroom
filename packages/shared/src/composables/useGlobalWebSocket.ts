import { onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { createReconnectingWebSocket } from './useReconnectingWebSocket'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

const messageHandlers: Set<(data: any) => void> = new Set()

const globalWsInstance = createReconnectingWebSocket({
  name: 'GlobalWS',
  getUrl: () => {
    const auth = useAuthStore()
    return auth.token ? `${WS_BASE}/ws/global?token=${auth.token}` : null
  },
  onMessage: (data) => {
    messageHandlers.forEach((handler) => {
      try {
        handler(data)
      } catch (e) {
        console.error('[GlobalWS] Handler error:', e)
      }
    })
  },
})

/**
 * Use global WebSocket for state updates (non-chat).
 * This is a singleton connection shared across all components.
 */
export function useGlobalWebSocket() {
  function onMessage(handler: (data: any) => void) {
    messageHandlers.add(handler)

    onUnmounted(() => {
      messageHandlers.delete(handler)
    })
  }

  if (!globalWsInstance.isConnected.value && globalWsInstance.state.value === 'disconnected') {
    globalWsInstance.connect()
  }

  return {
    isConnected: globalWsInstance.isConnected,
    onMessage,
    send: globalWsInstance.send,
    connect: globalWsInstance.connect,
    disconnect: globalWsInstance.disconnect,
  }
}
