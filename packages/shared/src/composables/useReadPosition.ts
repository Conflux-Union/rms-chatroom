import { ref, watch } from 'vue'
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
  has_mention: boolean
  last_mention_message_id: number | null
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
 */
async function fetchAndMergeServerPositions(): Promise<void> {
  const auth = useAuthStore()
  if (!auth.token) return

  try {
    const resp = await axios.get<{ positions: ServerReadPosition[] }>(
      `${API_BASE}/read-positions`,
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
  const { send, onMessage, isConnected } = useGlobalWebSocket()
  const lastReadMessageId = ref<number | null>(null)
  const showContinueReading = ref(false)

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
   * Save read position locally and sync to server.
   * Debounced to avoid excessive server calls.
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
  function syncToServer(
    channelId: number,
    messageId: number,
    hasMention: boolean = false,
    lastMentionMessageId: number | null = null
  ) {
    send({
      type: 'read_position_update',
      channel_id: channelId,
      last_read_message_id: messageId,
      has_mention: hasMention,
      last_mention_message_id: lastMentionMessageId,
    })
  }

  function getReadPosition(channelId: number): number | null {
    return positions.value[channelId]?.messageId ?? null
  }

  function getReadTimestamp(channelId: number): number | null {
    const timestamp = positions.value[channelId]?.timestamp ?? null
    return timestamp
  }

  function markChannelAsRead(channelId: number) {
    const now = Date.now()
    const current = positions.value[channelId]
    const messageId = current?.messageId ?? 0

    positions.value[channelId] = {
      messageId,
      timestamp: now,
    }
    savePositionsToStorage(positions.value)

    // Sync to server with cleared mention
    if (messageId > 0) {
      syncToServer(channelId, messageId, false, null)
    }
  }

  function clearReadPosition(channelId: number) {
    delete positions.value[channelId]
    savePositionsToStorage(positions.value)
  }

  function initForChannel(channelId: number, latestMessageId: number | null) {
    const savedId = getReadPosition(channelId)

    // Show continue reading button if:
    // 1. We have a saved position
    // 2. The saved position is different from the latest message
    if (savedId && latestMessageId && savedId < latestMessageId) {
      lastReadMessageId.value = savedId
      showContinueReading.value = true
    } else {
      lastReadMessageId.value = null
      showContinueReading.value = false
    }
  }

  function dismissContinueReading() {
    showContinueReading.value = false
  }

  return {
    lastReadMessageId,
    showContinueReading,
    saveReadPosition,
    getReadPosition,
    getReadTimestamp,
    markChannelAsRead,
    clearReadPosition,
    initForChannel,
    dismissContinueReading,
    syncToServer,
    refetchFromServer: fetchAndMergeServerPositions,
  }
}
