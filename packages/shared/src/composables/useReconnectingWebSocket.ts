import { ref, type Ref } from 'vue'
import { reportTelemetryEvent } from '../utils/telemetry'

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'

interface ReconnectingWebSocketOptions {
  name: string
  getUrl: () => string | null
  // Called before each connection attempt (including reconnects).
  // Use this to refresh auth tokens before the URL is fetched.
  onBeforeConnect?: () => Promise<void>
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
// One RTT report every N pongs (~5 min at the 5s heartbeat interval).
const RTT_REPORT_EVERY = 60

export function createReconnectingWebSocket(
  options: ReconnectingWebSocketOptions
): ReconnectingWebSocket {
  const { name, getUrl, onBeforeConnect, onConnected, onDisconnected, onMessage, onBinaryMessage } = options

  let ws: WebSocket | null = null
  const state = ref<ConnectionState>('disconnected')
  const isConnected = ref(false)

  let reconnectAttempts = 0
  let reconnectTimer: number | null = null
  let heartbeatInterval: number | null = null
  let heartbeatTimeout: number | null = null
  let waitingForPong = false
  let manualDisconnect = false
  let generation = 0

  // Connection-quality telemetry accumulators
  let lastCloseCode: number | null = null
  let lastCloseReason = ''
  let pingSentAt = 0
  let rttSum = 0
  let rttMax = 0
  let rttSamples = 0

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
        pingSentAt = Date.now()
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

    if (pingSentAt > 0) {
      const rtt = Date.now() - pingSentAt
      pingSentAt = 0
      rttSum += rtt
      rttMax = Math.max(rttMax, rtt)
      rttSamples++
      if (rttSamples >= RTT_REPORT_EVERY) {
        reportTelemetryEvent('ws_heartbeat_rtt', name, {
          meta: {
            ws: name,
            rtt_avg_ms: Math.round(rttSum / rttSamples),
            rtt_max_ms: rttMax,
            samples: rttSamples,
          },
        })
        rttSum = 0
        rttMax = 0
        rttSamples = 0
      }
    }
  }

  function scheduleReconnect() {
    if (manualDisconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        console.error(`[${name}] Max reconnect attempts (${MAX_RECONNECT_ATTEMPTS}) reached`)
        reportTelemetryEvent('ws_reconnect_exhausted', name, {
          meta: { ws: name, attempts: reconnectAttempts, close_code: lastCloseCode, close_reason: lastCloseReason },
        })
      }
      return
    }

    // Report the start of each outage, not every retry of it.
    if (reconnectAttempts === 0) {
      reportTelemetryEvent('ws_reconnect', name, {
        meta: { ws: name, close_code: lastCloseCode, close_reason: lastCloseReason },
      })
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
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }

    manualDisconnect = false
    generation++
    const gen = generation
    state.value = 'connecting'

    const openSocket = () => {
      if (gen !== generation) return

      const url = getUrl()
      if (!url) {
        console.warn(`[${name}] Cannot connect: no URL available`)
        state.value = 'disconnected'
        return
      }

      console.log(`[${name}] Connecting to ${url} (gen=${gen})`)
      ws = new WebSocket(url)

      ws.onopen = () => {
        if (gen !== generation) return
        console.log(`[${name}] Connected`)
        state.value = 'connected'
        isConnected.value = true
        reconnectAttempts = 0
        startHeartbeat()
        onConnected?.()
      }

      ws.onclose = (e: CloseEvent) => {
        if (gen !== generation) return
        lastCloseCode = e.code
        lastCloseReason = e.reason
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
        if (gen !== generation) return
        console.error(`[${name}] Error:`, e)
      }

      ws.onmessage = (event) => {
        if (gen !== generation) return

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

    if (onBeforeConnect) {
      onBeforeConnect().catch(() => {}).then(openSocket)
    } else {
      openSocket()
    }
  }

  function disconnect() {
    manualDisconnect = true
    generation++
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
