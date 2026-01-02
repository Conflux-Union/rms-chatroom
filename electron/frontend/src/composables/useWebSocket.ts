import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'

const WS_BASE = import.meta.env.VITE_WS_BASE || 'wss://preview-chatroom.rms.net.cn'

export function useWebSocket(path: string) {
  const ws = ref<WebSocket | null>(null)
  const isConnected = ref(false)
  const lastMessage = ref<any>(null)

  const messageHandlers: ((data: any) => void)[] = []

  function connect() {
    const auth = useAuthStore()
    if (!auth.token) return

    const url = `${WS_BASE}${path}?token=${auth.token}`
    ws.value = new WebSocket(url)

    ws.value.onopen = () => {
      isConnected.value = true
    }

    ws.value.onclose = () => {
      isConnected.value = false
    }

    ws.value.onerror = (e) => {
      console.error('WebSocket error:', e)
    }

    ws.value.onmessage = (event) => {
      // Handle binary data
      if (event.data instanceof Blob) {
        binaryHandlers.forEach((handler) => handler(event.data))
        return
      }
      // Handle text (JSON) data
      try {
        const data = JSON.parse(event.data)
        lastMessage.value = data
        messageHandlers.forEach((handler) => handler(data))
      } catch {
        // Ignore non-JSON messages
      }
    }
  }

  function disconnect() {
    if (ws.value) {
      ws.value.close()
      ws.value = null
    }
    isConnected.value = false
  }

  function send(data: any) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify(data))
    }
  }

  function sendBinary(data: ArrayBuffer | Blob) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(data)
    }
  }

  function onMessage(handler: (data: any) => void) {
    messageHandlers.push(handler)
  }

  const binaryHandlers: ((data: Blob) => void)[] = []

  function onBinaryMessage(handler: (data: Blob) => void) {
    binaryHandlers.push(handler)
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    ws,
    isConnected,
    lastMessage,
    connect,
    disconnect,
    send,
    sendBinary,
    onMessage,
    onBinaryMessage,
  }
}
