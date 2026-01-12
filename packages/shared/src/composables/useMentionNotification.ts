import { ref } from 'vue'

const STORAGE_KEY = 'rms-mention-notifications'

interface MentionNotification {
  [channelId: number]: {
    hasMention: boolean
    lastMentionMessageId: number | null
    timestamp: number
  }
}

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

// Shared audio element for mention notification sound
let mentionAudio: HTMLAudioElement | null = null
let audioInitialized = false

function getMentionAudio(): HTMLAudioElement | null {
  if (!audioInitialized) {
    try {
      mentionAudio = new Audio('/mention-notification.wav')
      mentionAudio.volume = 0.5
      audioInitialized = true
      
      // Handle audio loading error gracefully
      mentionAudio.addEventListener('error', () => {
        console.warn('Mention notification sound not found. Please add mention-notification.mp3 to public folder.')
        mentionAudio = null
      })
    } catch (e) {
      console.warn('Failed to initialize mention notification sound:', e)
      mentionAudio = null
    }
    audioInitialized = true
  }
  return mentionAudio
}

export function useMentionNotification() {
  const channelMentions = ref<Map<number, boolean>>(new Map())

  function playMentionSound() {
    console.log('[MentionSound] Attempting to play mention sound')
    try {
      const audio = getMentionAudio()
      if (!audio) {
        console.warn('[MentionSound] Audio not available')
        return
      }
      console.log('[MentionSound] Audio element ready, playing...')
      audio.currentTime = 0
      audio.play()
        .then(() => {
          console.log('[MentionSound] Sound played successfully')
        })
        .catch(e => {
          console.warn('[MentionSound] Play blocked by browser:', e)
        })
    } catch (e) {
      console.error('[MentionSound] Failed to play:', e)
    }
  }

  function markChannelAsMentioned(channelId: number, messageId: number) {
    console.log('[MentionNotification] Marking channel', channelId, 'as mentioned with message', messageId)
    const mentions = getStoredMentions()
    mentions[channelId] = {
      hasMention: true,
      lastMentionMessageId: messageId,
      timestamp: Date.now(),
    }
    saveMentions(mentions)
    
    // Update local state
    channelMentions.value.set(channelId, true)
    console.log('[MentionNotification] Updated channelMentions map:', Array.from(channelMentions.value.entries()))
    console.log('[MentionNotification] Saved to localStorage:', mentions[channelId])
  }

  function clearChannelMention(channelId: number) {
    const mentions = getStoredMentions()
    if (mentions[channelId]) {
      mentions[channelId].hasMention = false
    }
    saveMentions(mentions)
    
    // Update local state
    channelMentions.value.set(channelId, false)    console.log('[MentionNotification] Updated channelMentions map:', Array.from(channelMentions.value.entries()))
    console.log('[MentionNotification] Saved to localStorage:', mentions[channelId])  }

  function hasUnreadMention(channelId: number): boolean {
    const result = channelMentions.value.get(channelId) ?? false
    console.log('[MentionCheck] Channel', channelId, 'has unread mention:', result, 'All mentions:', Array.from(channelMentions.value.entries()))
    return result
  }

  function loadChannelMentions() {
    console.log('[MentionNotification] Loading channel mentions from localStorage')
    const mentions = getStoredMentions()
    console.log('[MentionNotification] Loaded mentions:', mentions)
    const mentionMap = new Map<number, boolean>()
    
    for (const [channelIdStr, data] of Object.entries(mentions)) {
      const channelId = parseInt(channelIdStr, 10)
      mentionMap.set(channelId, data.hasMention)
      if (data.hasMention) {
        console.log('[MentionNotification] Channel', channelId, 'has unread mention')
      }
    }
    
    channelMentions.value = mentionMap
    console.log('[MentionNotification] Final channel mentions map:', Array.from(channelMentions.value.entries()))
  }

  function checkMessagesForMentions(
    messages: Array<{ id: number; mentions?: Array<{ username: string }> }>,
    currentUsername: string,
    lastReadMessageId: number | null,
    channelId: number
  ): { hasMention: boolean; lastMentionMessageId: number | null } {
    let hasMention = false
    let lastMentionMessageId: number | null = null

    for (const message of messages) {
      // Skip messages before last read position
      if (lastReadMessageId && message.id <= lastReadMessageId) {
        continue
      }

      // Check if this message mentions the current user
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
    playMentionSound,
    markChannelAsMentioned,
    clearChannelMention,
    hasUnreadMention,
    loadChannelMentions,
    checkMessagesForMentions,
  }
}
