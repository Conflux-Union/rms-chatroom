import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

// Global singleton WebSocket connection
let globalWs: WebSocket | null = null
let isGlobalConnected = ref(false)
let reconnectTimer: number | null = null
let heartbeatInterval: number | null = null
let heartbeatTimeout: number | null = null
let waitingForPong = false

// Message handlers registry
const messageHandlers: Set<(data: any) => void> = new Set()

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
    if (globalWs && globalWs.readyState === WebSocket.OPEN && !waitingForPong) {
      waitingForPong = true
      globalWs.send(JSON.stringify({ type: 'ping', data: 'tribios' }))

      // Set 3 second timeout
      heartbeatTimeout = window.setTimeout(() => {
        console.warn('[GlobalWS] Heartbeat timeout, reconnecting...')
        waitingForPong = false
        disconnectGlobal()
        connectGlobal()
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

function connectGlobal() {
  const auth = useAuthStore()
  if (!auth.token) return

  // Clear any existing reconnect timer
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  const url = `${WS_BASE}/ws/global?token=${auth.token}`
  globalWs = new WebSocket(url)

  globalWs.onopen = () => {
    console.log('[GlobalWS] Connected')
    isGlobalConnected.value = true
    startHeartbeat()
  }

  globalWs.onclose = () => {
    console.log('[GlobalWS] Disconnected')
    isGlobalConnected.value = false
    clearHeartbeat()

    // Auto-reconnect after 3 seconds
    if (!reconnectTimer) {
      reconnectTimer = window.setTimeout(() => {
        console.log('[GlobalWS] Reconnecting...')
        connectGlobal()
      }, 3000)
    }
  }

  globalWs.onerror = (e) => {
    console.error('[GlobalWS] Error:', e)
  }

  globalWs.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)

      // Handle pong response
      if (data.type === 'pong' && data.data === 'cute') {
        handlePong()
        return
      }

      // Broadcast to all registered handlers
      messageHandlers.forEach((handler) => {
        try {
          handler(data)
        } catch (e) {
          console.error('[GlobalWS] Handler error:', e)
        }
      })
    } catch {
      // Ignore non-JSON messages
    }
  }
}

function disconnectGlobal() {
  clearHeartbeat()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (globalWs) {
    globalWs.close()
    globalWs = null
  }
  isGlobalConnected.value = false
}

/**
 * Use global WebSocket for state updates (non-chat).
 * This is a singleton connection shared across all components.
 */
export function useGlobalWebSocket() {
  // Register message handler
  function onMessage(handler: (data: any) => void) {
    messageHandlers.add(handler)

    // Auto-cleanup on component unmount
    onUnmounted(() => {
      messageHandlers.delete(handler)
    })
  }

  // Connect if not already connected
  if (!globalWs || globalWs.readyState === WebSocket.CLOSED) {
    connectGlobal()
  }

  return {
    isConnected: isGlobalConnected,
    onMessage,
    connect: connectGlobal,
    disconnect: disconnectGlobal,
  }
}
