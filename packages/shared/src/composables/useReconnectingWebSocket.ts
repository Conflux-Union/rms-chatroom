import { ref, type Ref } from 'vue'

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'

interface ReconnectingWebSocketOptions {
  name: string
  getUrl: () => string | null
  onConnected?: () => void
  onDisconnected?: () => void
  onMessage?: (data: any) => void
  onBinaryMessage?: (data: Blob) => void
}

interface ReconnectingWebSocket {
  connect: () => void
  disconnect: () => void
  send: (data: any) => void
  sendBinary: (data: ArrayBuffer | Blob) => void
  state: Ref<ConnectionState>
  isConnected: Ref<boolean>
  resetRetries: () => void
}

const MAX_RECONNECT_ATTEMPTS = 10
const HEARTBEAT_INTERVAL = 5000
const HEARTBEAT_TIMEOUT = 3000

export function createReconnectingWebSocket(
  options: ReconnectingWebSocketOptions
): ReconnectingWebSocket {
  const { name, getUrl, onConnected, onDisconnected, onMessage, onBinaryMessage } = options

  let ws: WebSocket | null = null
  const state = ref<ConnectionState>('disconnected')
  const isConnected = ref(false)

  let reconnectAttempts = 0
  let reconnectTimer: number | null = null
  let heartbeatInterval: number | null = null
  let heartbeatTimeout: number | null = null
  let waitingForPong = false
  let manualDisconnect = false

  function clearAllTimers() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
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
    clearAllTimers()

    heartbeatInterval = window.setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN && !waitingForPong) {
        waitingForPong = true
        ws.send(JSON.stringify({ type: 'ping', data: 'tribios' }))

        heartbeatTimeout = window.setTimeout(() => {
          console.warn(`[${name}] Heartbeat timeout, reconnecting...`)
          waitingForPong = false
          disconnect()
          connect()
        }, HEARTBEAT_TIMEOUT)
      }
    }, HEARTBEAT_INTERVAL)
  }

  function handlePong() {
    waitingForPong = false
    if (heartbeatTimeout) {
      clearTimeout(heartbeatTimeout)
      heartbeatTimeout = null
    }
  }

  function scheduleReconnect() {
    if (manualDisconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        console.error(`[${name}] Max reconnect attempts (${MAX_RECONNECT_ATTEMPTS}) reached`)
      }
      return
    }

    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)
    console.log(`[${name}] Reconnecting in ${delay}ms (attempt ${reconnectAttempts + 1}/${MAX_RECONNECT_ATTEMPTS})`)

    state.value = 'reconnecting'
    reconnectTimer = window.setTimeout(() => {
      reconnectAttempts++
      connect()
    }, delay)
  }

  function connect() {
    const url = getUrl()
    if (!url) {
      console.warn(`[${name}] Cannot connect: no URL available`)
      return
    }

    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }

    manualDisconnect = false
    state.value = 'connecting'
    console.log(`[${name}] Connecting to ${url}`)

    ws = new WebSocket(url)

    ws.onopen = () => {
      console.log(`[${name}] Connected`)
      state.value = 'connected'
      isConnected.value = true
      reconnectAttempts = 0
      startHeartbeat()
      onConnected?.()
    }

    ws.onclose = () => {
      console.log(`[${name}] Disconnected`)
      state.value = 'disconnected'
      isConnected.value = false
      clearAllTimers()
      onDisconnected?.()

      if (!manualDisconnect) {
        scheduleReconnect()
      }
    }

    ws.onerror = (e) => {
      console.error(`[${name}] Error:`, e)
    }

    ws.onmessage = (event) => {
      if (event.data instanceof Blob) {
        onBinaryMessage?.(event.data)
        return
      }

      try {
        const data = JSON.parse(event.data)

        if (data.type === 'pong' && data.data === 'cute') {
          handlePong()
          return
        }

        onMessage?.(data)
      } catch {
        // Ignore non-JSON messages
      }
    }
  }

  function disconnect() {
    manualDisconnect = true
    clearAllTimers()

    if (ws) {
      ws.close()
      ws = null
    }

    state.value = 'disconnected'
    isConnected.value = false
  }

  function send(data: any) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(data))
    }
  }

  function sendBinary(data: ArrayBuffer | Blob) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(data)
    }
  }

  function resetRetries() {
    reconnectAttempts = 0
  }

  return {
    connect,
    disconnect,
    send,
    sendBinary,
    state,
    isConnected,
    resetRetries,
  }
}
