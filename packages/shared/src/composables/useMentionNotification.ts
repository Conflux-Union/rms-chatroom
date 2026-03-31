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
  has_mention: boolean
  last_mention_message_id: number | null
}

// Shared reactive state across all component instances
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
 * Fetch mention states from server and merge with local storage.
 */
async function fetchAndMergeServerMentions(): Promise<void> {
  const auth = useAuthStore()
  if (!auth.token) return

  try {
    const resp = await axios.get<{ positions: ServerReadPosition[] }>(
      `${API_BASE}/api/read-positions`,
      { headers: { Authorization: `Bearer ${auth.token}` } }
    )

    const serverPositions = resp.data.positions
    const localMentions = getStoredMentions()

    for (const pos of serverPositions) {
      // Server mention state takes precedence
      if (pos.has_mention) {
        localMentions[pos.channel_id] = {
          hasMention: true,
          lastMentionMessageId: pos.last_mention_message_id,
          timestamp: Date.now(),
        }
        sharedChannelMentions.value[pos.channel_id] = true
      } else if (localMentions[pos.channel_id]?.hasMention) {
        // If server says no mention but local says yes, clear it
        localMentions[pos.channel_id].hasMention = false
        sharedChannelMentions.value[pos.channel_id] = false
      }
    }

    saveMentions(localMentions)
    triggerRef(sharedChannelMentions)
  } catch (e) {
    console.error('[MentionNotification] Failed to fetch server mentions:', e)
  }
}

export function useMentionNotification() {
  const { send, onMessage } = useGlobalWebSocket()
  const channelMentions = sharedChannelMentions
  const unreadCounts = sharedUnreadCounts

  // Initialize on first use
  if (!initialized) {
    initialized = true
    loadChannelMentions()
    // Fetch server mentions after a short delay
    setTimeout(() => {
      fetchAndMergeServerMentions()
    }, 150)
  }

  // Listen for read position sync from other devices (includes mention state)
  onMessage((data: any) => {
    if (data.type === 'read_position_sync') {
      const { channel_id, has_mention, last_mention_message_id } = data

      const mentions = getStoredMentions()
      mentions[channel_id] = {
        hasMention: has_mention,
        lastMentionMessageId: last_mention_message_id,
        timestamp: Date.now(),
      }
      saveMentions(mentions)

      channelMentions.value[channel_id] = has_mention
      triggerRef(channelMentions)
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

  function markChannelAsMentioned(channelId: number, messageId: number) {
    const mentions = getStoredMentions()
    mentions[channelId] = {
      hasMention: true,
      lastMentionMessageId: messageId,
      timestamp: Date.now(),
    }
    saveMentions(mentions)
    
    channelMentions.value[channelId] = true
    triggerRef(channelMentions)

    // Sync to server
    syncMentionToServer(channelId, messageId, true)
  }

  function clearChannelMention(channelId: number) {
    const mentions = getStoredMentions()
    const lastMentionId = mentions[channelId]?.lastMentionMessageId ?? null
    if (mentions[channelId]) {
      mentions[channelId].hasMention = false
    }
    saveMentions(mentions)
    
    channelMentions.value[channelId] = false
    triggerRef(channelMentions)
    
    unreadCounts.value[channelId] = 0
    triggerRef(unreadCounts)

    // Sync cleared mention to server (will be handled by useReadPosition)
  }

  /**
   * Sync mention state to server via WebSocket.
   */
  function syncMentionToServer(
    channelId: number,
    lastMentionMessageId: number | null,
    hasMention: boolean
  ) {
    // Get current read position to include in sync
    const mentions = getStoredMentions()
    const readPositions = JSON.parse(localStorage.getItem('rms-discord-read-positions') || '{}')
    const lastReadMessageId = readPositions[channelId]?.messageId ?? lastMentionMessageId ?? 0

    send({
      type: 'read_position_update',
      channel_id: channelId,
      last_read_message_id: lastReadMessageId,
      has_mention: hasMention,
      last_mention_message_id: lastMentionMessageId,
    })
  }

  function setUnreadCount(channelId: number, count: number) {
    unreadCounts.value[channelId] = count
    triggerRef(unreadCounts)
  }

  function getUnreadCount(channelId: number): number {
    return unreadCounts.value[channelId] ?? 0
  }

  function clearUnreadCount(channelId: number) {
    unreadCounts.value[channelId] = 0
    triggerRef(unreadCounts)
  }

  function hasUnreadMention(channelId: number): boolean {
    return channelMentions.value[channelId] ?? false
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

  function checkMessagesForMentions(
    messages: Array<{ id: number; mentions?: Array<{ username: string }> }>,
    currentUsername: string,
    lastReadMessageId: number | null,
    channelId: number,
    currentUserId?: number
  ): { hasMention: boolean; lastMentionMessageId: number | null } {
    let hasMention = false
    let lastMentionMessageId: number | null = null

    for (const message of messages) {
      if (lastReadMessageId && message.id <= lastReadMessageId) {
        continue
      }

      if (message.mentions && message.mentions.length > 0) {
        const isMentioned = message.mentions.some(
          mention => mention.username === currentUsername
        )
        if (isMentioned) {
          hasMention = true
          lastMentionMessageId = message.id
        }
      }
    }

    return { hasMention, lastMentionMessageId }
  }

  return {
    channelMentions,
    unreadCounts,
    playMentionSound,
    markChannelAsMentioned,
    clearChannelMention,
    hasUnreadMention,
    loadChannelMentions,
    checkMessagesForMentions,
    setUnreadCount,
    getUnreadCount,
    clearUnreadCount,
    refetchFromServer: fetchAndMergeServerMentions,
  }
}
