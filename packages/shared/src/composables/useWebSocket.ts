import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

export function useWebSocket(path: string) {
  const ws = ref<WebSocket | null>(null)
  const isConnected = ref(false)
  const lastMessage = ref<any>(null)

  const messageHandlers: ((data: any) => void)[] = []

  // Heartbeat state
  let heartbeatInterval: number | null = null
  let heartbeatTimeout: number | null = null
  let waitingForPong = false

  function clearHeartbeat() {
    if (heartbeatInterval) {
      clearInterval(heartbeatInterval)
      heartbeatInterval = null
    }
    if (heartbeatTimeout) {
      clearTimeout(heartbeatTimeout)
      heartbeatTimeout = null
    }
    waitingForPong = false
  }

  function startHeartbeat() {
    clearHeartbeat()

    // Send heartbeat every 5 seconds
    heartbeatInterval = window.setInterval(() => {
      if (ws.value && ws.value.readyState === WebSocket.OPEN && !waitingForPong) {
        waitingForPong = true
        ws.value.send(JSON.stringify({ type: 'ping', data: 'tribios' }))

        // Set 3 second timeout
        heartbeatTimeout = window.setTimeout(() => {
          console.warn('Heartbeat timeout, reconnecting...')
          waitingForPong = false
          disconnect()
          connect()
        }, 3000)
      }
    }, 5000)
  }

  function handlePong() {
    waitingForPong = false
    if (heartbeatTimeout) {
      clearTimeout(heartbeatTimeout)
      heartbeatTimeout = null
    }
  }

  function connect() {
    const auth = useAuthStore()
    if (!auth.token) return

    const url = `${WS_BASE}${path}?token=${auth.token}`
    ws.value = new WebSocket(url)

    ws.value.onopen = () => {
      isConnected.value = true
      startHeartbeat()
    }

    ws.value.onclose = () => {
      isConnected.value = false
      clearHeartbeat()
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

        // Handle pong response
        if (data.type === 'pong' && data.data === 'cute') {
          handlePong()
          return
        }

        lastMessage.value = data
        messageHandlers.forEach((handler) => handler(data))
      } catch {
        // Ignore non-JSON messages
      }
    }
  }

  function disconnect() {
    clearHeartbeat()
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

  // Don't use onUnmounted here - let the caller manage lifecycle
  // onUnmounted(() => {
  //   disconnect()
  // })

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
