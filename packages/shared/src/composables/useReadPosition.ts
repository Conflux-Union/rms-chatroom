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
// Highest position per channel known to be delivered to (or confirmed by) the
// server. Distinct from `positions`: the local cache advances optimistically
// for entry positioning and unread dividers, while this watermark gates server
// syncs so a cache that got ahead after a dropped sync still gets pushed up.
let syncedPositions: Record<number, number> = {}
let initialized = false
let syncDebounceTimers: Record<number, number> = {}
let pendingSyncIds: Record<number, number> = {}

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

// Assigned on first useReadPosition() so module-level heals can send without
// importing the socket at module load time (that would connect before the
// auth token is restored).
let wsSend: ((data: any) => void) | null = null

/**
 * Debounced, watermark-gated push of the read position to the server.
 * Fire-and-forget on purpose (the server applies GREATEST, so re-sends are
 * idempotent); divergence from a dropped send is re-detected on the next
 * server merge (startup, global-WS reconnect) or read_position_sync push.
 */
function scheduleServerSync(channelId: number, messageId: number) {
  if (messageId <= (syncedPositions[channelId] ?? 0)) return

  pendingSyncIds[channelId] = Math.max(messageId, pendingSyncIds[channelId] ?? 0)
  if (syncDebounceTimers[channelId]) {
    clearTimeout(syncDebounceTimers[channelId])
  }
  syncDebounceTimers[channelId] = window.setTimeout(() => {
    const pending = pendingSyncIds[channelId]
    delete syncDebounceTimers[channelId]
    delete pendingSyncIds[channelId]
    if (pending == null || !wsSend) return
    syncedPositions[channelId] = pending
    wsSend({
      type: 'read_position_update',
      channel_id: channelId,
      last_read_message_id: pending,
    })
  }, 500)
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
      if (!local || pos.last_read_message_id > local.messageId) {
        // Server is ahead (another device read further): adopt it, and no
        // need to re-send what the server already holds.
        merged[pos.channel_id] = {
          messageId: pos.last_read_message_id,
          timestamp: Date.now(),
        }
        syncedPositions[pos.channel_id] = pos.last_read_message_id
      } else if (local.messageId > pos.last_read_message_id) {
        // Local cache is ahead: a previous optimistic save's sync was dropped
        // (debounce lost to a dead socket or a reload). Push it back up —
        // the server applies GREATEST, so the heal is idempotent.
        scheduleServerSync(pos.channel_id, local.messageId)
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
  wsSend = send

  // Initialize on first use
  if (!initialized) {
    initialized = true
    positions.value = getStoredPositions()
    // Fetch server positions after a short delay to allow auth to be ready
    setTimeout(() => {
      fetchAndMergeServerPositions()
    }, 100)
  }

  // Listen for read position sync from other devices (and, since the server
  // echoes our own updates, as the ack for them).
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

      // The push confirms the server holds at least this position. A local
      // cache still above it means an earlier sync was dropped — heal it.
      if (last_read_message_id > (syncedPositions[channel_id] ?? 0)) {
        syncedPositions[channel_id] = last_read_message_id
      }
      const local = positions.value[channel_id]
      if (local && local.messageId > (syncedPositions[channel_id] ?? 0)) {
        scheduleServerSync(channel_id, local.messageId)
      }
    }
  })

  /**
   * Record that the viewport has reached a message: advance the local cache
   * (used for entry positioning and unread dividers) and schedule the
   * watermark-gated sync to the server, which derives unread counts and
   * mention flags from the resulting position.
   */
  function saveReadPosition(channelId: number, messageId: number) {
    const current = positions.value[channelId]

    // Advance the local cache only forward; the server sync below is gated
    // separately so a cache ahead of the server still gets reconciled.
    if (!current || messageId > current.messageId) {
      positions.value[channelId] = {
        messageId,
        timestamp: Date.now(),
      }
      savePositionsToStorage(positions.value)
    }

    scheduleServerSync(channelId, messageId)
  }

  function getReadPosition(channelId: number): number | null {
    return positions.value[channelId]?.messageId ?? null
  }

  return {
    saveReadPosition,
    getReadPosition,
    refetchFromServer: fetchAndMergeServerPositions,
  }
}
