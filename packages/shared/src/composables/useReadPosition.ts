import { ref } from 'vue'
import { useGlobalWebSocket } from './useGlobalWebSocket'
import { useAuthStore } from '../stores/auth'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''
const STORAGE_KEY = 'rms-discord-read-positions'

interface ReadPositionData {
  messageId: number
  timestamp: number
}

interface ReadPositions {
  [channelId: number]: ReadPositionData
}

interface ServerReadPosition {
  channel_id: number
  last_read_message_id: number
}

// Shared state across all instances
const positions = ref<ReadPositions>({})
let initialized = false
let syncDebounceTimers: Record<number, number> = {}

function getStoredPositions(): ReadPositions {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored ? JSON.parse(stored) : {}
  } catch {
    return {}
  }
}

function savePositionsToStorage(data: ReadPositions) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch {
    // localStorage might be full or disabled
  }
}

/**
 * Fetch read positions from server and merge with local storage.
 * Server positions take precedence if they have a higher message ID.
 * Exported for the reconnect resync in Main.vue (shared module state, no
 * composable instance needed).
 */
export async function fetchAndMergeServerPositions(): Promise<void> {
  const auth = useAuthStore()
  if (!auth.token) return

  try {
    const resp = await axios.get<{ positions: ServerReadPosition[] }>(
      `${API_BASE}/api/read-positions`,
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )

    const serverPositions = resp.data.positions
    const localPositions = getStoredPositions()
    const merged: ReadPositions = { ...localPositions }

    for (const pos of serverPositions) {
      const local = merged[pos.channel_id]
      // Server wins if local doesn't exist or server has higher message ID
      if (!local || pos.last_read_message_id > local.messageId) {
        merged[pos.channel_id] = {
          messageId: pos.last_read_message_id,
          timestamp: Date.now(),
        }
      }
    }

    positions.value = merged
    savePositionsToStorage(merged)
  } catch (e) {
    console.error('[ReadPosition] Failed to fetch server positions:', e)
    // Fall back to local storage
    positions.value = getStoredPositions()
  }
}

export function useReadPosition() {
  const { send, onMessage } = useGlobalWebSocket()

  // Initialize on first use
  if (!initialized) {
    initialized = true
    positions.value = getStoredPositions()
    // Fetch server positions after a short delay to allow auth to be ready
    setTimeout(() => {
      fetchAndMergeServerPositions()
    }, 100)
  }

  // Listen for read position sync from other devices
  onMessage((data: any) => {
    if (data.type === 'read_position_sync') {
      const { channel_id, last_read_message_id } = data
      const current = positions.value[channel_id]

      // Only update if server position is newer
      if (!current || last_read_message_id > current.messageId) {
        positions.value[channel_id] = {
          messageId: last_read_message_id,
          timestamp: Date.now(),
        }
        savePositionsToStorage(positions.value)
      }
    }
  })

  /**
   * Record that the viewport has reached a message, and sync to the server
   * (debounced). The server derives unread counts and mention flags from the
   * resulting position.
   */
  function saveReadPosition(channelId: number, messageId: number) {
    const current = positions.value[channelId]

    // Only update if new position is greater
    if (current && messageId <= current.messageId) {
      return
    }

    positions.value[channelId] = {
      messageId,
      timestamp: Date.now(),
    }
    savePositionsToStorage(positions.value)

    // Debounce server sync (500ms)
    if (syncDebounceTimers[channelId]) {
      clearTimeout(syncDebounceTimers[channelId])
    }
    syncDebounceTimers[channelId] = window.setTimeout(() => {
      syncToServer(channelId, messageId)
      delete syncDebounceTimers[channelId]
    }, 500)
  }

  /**
   * Sync read position to server via WebSocket.
   */
  function syncToServer(channelId: number, messageId: number) {
    send({
      type: 'read_position_update',
      channel_id: channelId,
      last_read_message_id: messageId,
    })
  }

  function getReadPosition(channelId: number): number | null {
    return positions.value[channelId]?.messageId ?? null
  }

  return {
    saveReadPosition,
    getReadPosition,
    syncToServer,
    refetchFromServer: fetchAndMergeServerPositions,
  }
}
