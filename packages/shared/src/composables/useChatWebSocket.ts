import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'

const WS_BASE = import.meta.env.VITE_WS_BASE || ''

// Global singleton WebSocket connection for chat
let chatWs: WebSocket | null = null
let isChatConnected = ref(false)
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
    if (chatWs && chatWs.readyState === WebSocket.OPEN && !waitingForPong) {
      waitingForPong = true
      chatWs.send(JSON.stringify({ type: 'ping', data: 'tribios' }))

      // Set 3 second timeout
      heartbeatTimeout = window.setTimeout(() => {
        console.warn('[ChatWS] Heartbeat timeout, reconnecting...')
        waitingForPong = false
        disconnectChat()
        connectChat()
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

function connectChat() {
  const auth = useAuthStore()
  if (!auth.token) return

  // Already connected or connecting
  if (chatWs && (chatWs.readyState === WebSocket.OPEN || chatWs.readyState === WebSocket.CONNECTING)) {
    return
  }

  // Clear any existing reconnect timer
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  const url = `${WS_BASE}/ws/chat?token=${auth.token}`
  console.log('[ChatWS] Connecting to', url)
  chatWs = new WebSocket(url)

  chatWs.onopen = () => {
    console.log('[ChatWS] Connected')
    isChatConnected.value = true
    startHeartbeat()
  }

  chatWs.onclose = () => {
    console.log('[ChatWS] Disconnected')
    isChatConnected.value = false
    clearHeartbeat()

    // Auto-reconnect after 3 seconds
    if (!reconnectTimer) {
      reconnectTimer = window.setTimeout(() => {
        console.log('[ChatWS] Reconnecting...')
        connectChat()
      }, 3000)
    }
  }

  chatWs.onerror = (e) => {
    console.error('[ChatWS] Error:', e)
  }

  chatWs.onmessage = (event) => {
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
          console.error('[ChatWS] Handler error:', e)
        }
      })
    } catch {
      // Ignore non-JSON messages
    }
  }
}

function disconnectChat() {
  clearHeartbeat()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (chatWs) {
    chatWs.close()
    chatWs = null
  }
  isChatConnected.value = false
}

function sendChatMessage(data: any) {
  if (chatWs && chatWs.readyState === WebSocket.OPEN) {
    chatWs.send(JSON.stringify(data))
  }
}

/**
 * Global chat WebSocket singleton.
 * This connection persists across component mounts/unmounts.
 * Use this for real-time chat messages, reactions, etc.
 */
export function useChatWebSocket() {
  // Register message handler
  function onMessage(handler: (data: any) => void) {
    messageHandlers.add(handler)

    // Auto-cleanup on component unmount
    onUnmounted(() => {
      messageHandlers.delete(handler)
    })
  }

  return {
    isConnected: isChatConnected,
    onMessage,
    send: sendChatMessage,
    connect: connectChat,
    disconnect: disconnectChat,
  }
}
