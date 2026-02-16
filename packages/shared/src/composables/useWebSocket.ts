import { ref, computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import { createReconnectingWebSocket } from './useReconnectingWebSocket'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

export function useWebSocket(path: string) {
  const lastMessage = ref<any>(null)
  const messageHandlers: ((data: any) => void)[] = []
  const binaryHandlers: ((data: Blob) => void)[] = []

  const wsInstance = createReconnectingWebSocket({
    name: `WS:${path}`,
    getUrl: () => {
      const auth = useAuthStore()
      return auth.token ? `${WS_BASE}${path}?token=${auth.token}` : null
    },
    onMessage: (data) => {
      lastMessage.value = data
      messageHandlers.forEach((handler) => handler(data))
    },
    onBinaryMessage: (data) => {
      binaryHandlers.forEach((handler) => handler(data))
    },
  })

  function onMessage(handler: (data: any) => void) {
    messageHandlers.push(handler)
  }

  function onBinaryMessage(handler: (data: Blob) => void) {
    binaryHandlers.push(handler)
  }

  const ws = computed(() => null)

  return {
    ws,
    isConnected: wsInstance.isConnected,
    lastMessage,
    connect: wsInstance.connect,
    disconnect: wsInstance.disconnect,
    send: wsInstance.send,
    sendBinary: wsInstance.sendBinary,
    onMessage,
    onBinaryMessage,
  }
}
