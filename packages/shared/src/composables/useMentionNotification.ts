import { ref, triggerRef } from 'vue'
import { useGlobalWebSocket } from './useGlobalWebSocket'
import { useAuthStore } from '../stores/auth'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''
const STORAGE_KEY = 'rms-mention-notifications'

interface MentionNotification {
  [channelId: number]: {
    hasMention: boolean
    lastMentionMessageId: number | null
    timestamp: number
  }
}

interface ServerReadPosition {
  channel_id: number
  last_read_message_id: number
  unread_count: number
  has_mention: boolean
  last_mention_message_id: number | null
}

// Shared reactive state across all component instances. Unread counts and
// mention flags are server-derived: they are only ever written from server
// pushes (unread_update / read_position_sync) or the /api/read-positions
// fetch, never accumulated locally.
const sharedChannelMentions = ref<Record<number, boolean>>({})
const sharedUnreadCounts = ref<Record<number, number>>({})
let initialized = false

function getStoredMentions(): MentionNotification {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored ? JSON.parse(stored) : {}
  } catch {
    return {}
  }
}

function saveMentions(mentions: MentionNotification) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(mentions))
  } catch {
    // localStorage might be full or disabled
  }
}

// Audio element management - create a real DOM element for better browser compatibility
let audioElement: HTMLAudioElement | null = null

function getAudioElement(): HTMLAudioElement | null {
  if (typeof window === 'undefined') return null

  if (!audioElement) {
    // Check if element already exists in DOM
    audioElement = document.getElementById('mention-sound') as HTMLAudioElement

    if (!audioElement) {
      // Create and append to DOM
      audioElement = document.createElement('audio')
      audioElement.id = 'mention-sound'
      audioElement.src = '/mention-notification.wav'
      audioElement.volume = 0.5
      audioElement.preload = 'auto'
      document.body.appendChild(audioElement)
    }
  }
  return audioElement
}

// Initialize audio element on module load
if (typeof window !== 'undefined') {
  // Wait for DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => getAudioElement())
  } else {
    getAudioElement()
  }
}

// Sound deduplication state
const playedSounds = new Set<string>()
let lastSoundPlayTime = 0
const SOUND_COOLDOWN_MS = 10000 // 10 seconds

/**
 * Apply server-derived read state (unread count + mention flag) for one
 * channel. The only writer of badge state besides the direct WS handlers.
 */
function applyServerReadState(
  channelId: number,
  unreadCount: number,
  hasMention: boolean,
  lastMentionMessageId: number | null
) {
  sharedUnreadCounts.value[channelId] = unreadCount
  triggerRef(sharedUnreadCounts)

  const mentions = getStoredMentions()
  mentions[channelId] = {
    hasMention,
    lastMentionMessageId,
    timestamp: Date.now(),
  }
  saveMentions(mentions)

  sharedChannelMentions.value[channelId] = hasMention
  triggerRef(sharedChannelMentions)
}

/**
 * Fetch server-derived unread/mention state for all channels.
 */
async function fetchServerUnreadState(): Promise<void> {
  const auth = useAuthStore()
  if (!auth.token) return

  try {
    const resp = await axios.get<{ positions: ServerReadPosition[] }>(
      `${API_BASE}/api/read-positions`,
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )

    for (const pos of resp.data.positions) {
      applyServerReadState(
        pos.channel_id,
        pos.unread_count ?? 0,
        pos.has_mention ?? false,
        pos.last_mention_message_id ?? null
      )
    }
  } catch (e) {
    console.error('[MentionNotification] Failed to fetch server unread state:', e)
  }
}

export function useMentionNotification() {
  const { onMessage } = useGlobalWebSocket()
  const channelMentions = sharedChannelMentions
  const unreadCounts = sharedUnreadCounts

  // Initialize on first use
  if (!initialized) {
    initialized = true
    loadChannelMentions()
    // Fetch server state after a short delay
    setTimeout(() => {
      fetchServerUnreadState()
    }, 150)
  }

  // Live badge updates: unread_update arrives with every new message;
  // read_position_sync whenever any device advances the read cursor.
  onMessage((data: any) => {
    if (data.type === 'unread_update') {
      applyServerReadState(
        data.channel_id,
        data.unread_count ?? 0,
        data.has_mention ?? false,
        data.last_mention_message_id ?? null
      )
      return
    }

    if (data.type === 'read_position_sync') {
      if (typeof data.unread_count === 'number') {
        applyServerReadState(
          data.channel_id,
          data.unread_count,
          data.has_mention ?? false,
          data.last_mention_message_id ?? null
        )
      }
    }
  })

  /**
   * Play mention notification sound
   * - Each message only plays once
   * - 10 second cooldown between sounds
   */
  function playMentionSound(channelId: number, messageId: number) {
    const now = Date.now()
    const soundKey = `${channelId}-${messageId}`

    // Skip if already played this message
    if (playedSounds.has(soundKey)) {
      return
    }

    // Skip if in cooldown
    if (now - lastSoundPlayTime < SOUND_COOLDOWN_MS) {
      return
    }

    // Mark as played immediately
    playedSounds.add(soundKey)

    const audio = getAudioElement()
    if (!audio) return

    audio.currentTime = 0
    audio.play()
      .then(() => {
        lastSoundPlayTime = now
        // Cleanup old entries
        if (playedSounds.size > 100) {
          const entries = Array.from(playedSounds)
          playedSounds.clear()
          entries.slice(-50).forEach(key => playedSounds.add(key))
        }
      })
      .catch(e => {
        // Browser blocked autoplay - this is expected before user interaction
        console.warn('[MentionSound] Autoplay blocked:', e.message)
      })
  }

  /**
   * Optimistically clear the local @ badge once the viewport passes the
   * mention message; the server-derived flag arrives with the next sync.
   */
  function clearChannelMention(channelId: number) {
    const mentions = getStoredMentions()
    if (mentions[channelId]) {
      mentions[channelId].hasMention = false
    }
    saveMentions(mentions)

    channelMentions.value[channelId] = false
    triggerRef(channelMentions)
  }

  function hasUnreadMention(channelId: number): boolean {
    return channelMentions.value[channelId] ?? false
  }

  /**
   * Current mention state for a channel. hasMention comes from the reactive
   * flag; the mention message id from persisted storage.
   */
  function getChannelMention(
    channelId: number
  ): { hasMention: boolean; lastMentionMessageId: number | null } | null {
    if (!channelMentions.value[channelId]) return null
    const stored = getStoredMentions()[channelId]
    return {
      hasMention: true,
      lastMentionMessageId: stored?.lastMentionMessageId ?? null,
    }
  }

  function loadChannelMentions() {
    const mentions = getStoredMentions()
    const mentionMap: Record<number, boolean> = {}

    for (const [channelIdStr, data] of Object.entries(mentions)) {
      const channelId = parseInt(channelIdStr, 10)
      mentionMap[channelId] = data.hasMention
    }

    channelMentions.value = mentionMap
  }

  return {
    channelMentions,
    unreadCounts,
    playMentionSound,
    clearChannelMention,
    hasUnreadMention,
    getChannelMention,
    loadChannelMentions,
    refetchFromServer: fetchServerUnreadState,
  }
}
