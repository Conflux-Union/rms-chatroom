import { ref, triggerRef } from 'vue'

const STORAGE_KEY = 'rms-mention-notifications'

interface MentionNotification {
  [channelId: number]: {
    hasMention: boolean
    lastMentionMessageId: number | null
    timestamp: number
  }
}

// 模块级别的共享状态，所有组件实例共享同一个 ref
// 这样当 ChatArea 更新状态时，ChannelList 也能响应式更新
const sharedChannelMentions = ref<Record<number, boolean>>({})

// 未读消息计数的共享状态
const sharedUnreadCounts = ref<Record<number, number>>({})

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
let audioUnlocked = false

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

// Unlock audio on first user interaction
function unlockAudio() {
  if (audioUnlocked) return
  
  const audio = getMentionAudio()
  if (audio) {
    // Play and immediately pause to unlock audio
    audio.play().then(() => {
      audio.pause()
      audio.currentTime = 0
      audioUnlocked = true
      console.log('[MentionSound] Audio unlocked')
    }).catch(() => {
      // Will try again on next interaction
    })
  }
}

// Listen for user interactions to unlock audio
if (typeof window !== 'undefined') {
  const events = ['click', 'touchstart', 'keydown']
  const unlockHandler = () => {
    unlockAudio()
    // Remove listeners after first unlock
    if (audioUnlocked) {
      events.forEach(event => {
        document.removeEventListener(event, unlockHandler)
      })
    }
  }
  events.forEach(event => {
    document.addEventListener(event, unlockHandler, { once: false })
  })
}

// 模块级别的状态，防止重复播放（所有组件实例共享）
const playedSounds = new Set<string>() // key: "channelId-messageId"
let lastSoundPlayTime = 0
const SOUND_COOLDOWN_MS = 10000 // 10 seconds cooldown between sounds

export function useMentionNotification() {
  // 使用模块级别的共享状态，而不是每次调用都创建新的 ref
  // 这样所有组件实例共享同一个响应式状态
  const channelMentions = sharedChannelMentions
  const unreadCounts = sharedUnreadCounts

  /**
   * 播放@提及提示音，每10秒最多响一次，每条mention只响一次
   * @param channelId 频道ID
   * @param messageId 消息ID
   */
  function playMentionSound(channelId: number, messageId: number) {
    const now = Date.now()
    const soundKey = `${channelId}-${messageId}`

    // 每条mention只响一次（无论冷却期如何，只要响过就不再响）
    if (playedSounds.has(soundKey)) {
      console.log('[MentionSound] Already played sound for message', messageId, 'in channel', channelId)
      return
    }

    // 冷却期内不响
    if (now - lastSoundPlayTime < SOUND_COOLDOWN_MS) {
      console.log('[MentionSound] In cooldown period, skipping sound')
      return
    }

    // 立即标记为已播放，彻底防止并发多次播放
    playedSounds.add(soundKey)

    console.log('[MentionSound] Attempting to play mention sound, audioUnlocked:', audioUnlocked)

    // 尝试解锁音频
    if (!audioUnlocked) {
      console.log('[MentionSound] Audio not unlocked yet, attempting unlock...')
      unlockAudio()
    }

    try {
      const audio = getMentionAudio()
      if (!audio) {
        console.warn('[MentionSound] Audio not available')
        return
      }
      console.log('[MentionSound] Audio element ready, playing...')
      audio.currentTime = 0
      const playPromise = audio.play()

      if (playPromise !== undefined) {
        playPromise
          .then(() => {
            console.log('[MentionSound] Sound played successfully for message', messageId)
            audioUnlocked = true
            lastSoundPlayTime = now

            // 清理老的记录，防止内存泄漏
            if (playedSounds.size > 100) {
              const entries = Array.from(playedSounds)
              playedSounds.clear()
              entries.slice(-50).forEach(key => playedSounds.add(key))
            }
          })
          .catch(e => {
            console.warn('[MentionSound] Play blocked by browser:', e.message)
            console.warn('[MentionSound] User interaction required. Click anywhere on the page first.')
          })
      }
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
    
    // Update local state - set property directly and trigger ref manually
    channelMentions.value[channelId] = true
    triggerRef(channelMentions)
    
    console.log('[MentionNotification] Updated channelMentions:', JSON.stringify(channelMentions.value))
    console.log('[MentionNotification] Saved to localStorage:', mentions[channelId])
  }

  function clearChannelMention(channelId: number) {
    const mentions = getStoredMentions()
    if (mentions[channelId]) {
      mentions[channelId].hasMention = false
    }
    saveMentions(mentions)
    
    // Update local state - set property directly and trigger ref manually
    channelMentions.value[channelId] = false
    triggerRef(channelMentions)
    
    // 同时清除未读计数
    unreadCounts.value[channelId] = 0
    triggerRef(unreadCounts)
    
    console.log('[MentionNotification] Cleared channel', channelId, 'mention and unread count')
  }

  function setUnreadCount(channelId: number, count: number) {
    unreadCounts.value[channelId] = count
    triggerRef(unreadCounts)
    console.log('[UnreadCount] Set channel', channelId, 'unread count to', count)
  }

  function getUnreadCount(channelId: number): number {
    return unreadCounts.value[channelId] ?? 0
  }

  function clearUnreadCount(channelId: number) {
    unreadCounts.value[channelId] = 0
    triggerRef(unreadCounts)
    console.log('[UnreadCount] Cleared channel', channelId, 'unread count')
  }

  function hasUnreadMention(channelId: number): boolean {
    const result = channelMentions.value[channelId] ?? false
    console.log('[MentionCheck] Channel', channelId, 'has unread mention:', result, 'All mentions:', channelMentions.value)
    return result
  }

  function loadChannelMentions() {
    console.log('[MentionNotification] Loading channel mentions from localStorage')
    const mentions = getStoredMentions()
    console.log('[MentionNotification] Loaded mentions:', mentions)
    const mentionMap: Record<number, boolean> = {}
    
    for (const [channelIdStr, data] of Object.entries(mentions)) {
      const channelId = parseInt(channelIdStr, 10)
      mentionMap[channelId] = data.hasMention
      if (data.hasMention) {
        console.log('[MentionNotification] Channel', channelId, 'has unread mention')
      }
    }
    
    channelMentions.value = mentionMap
    console.log('[MentionNotification] Final channel mentions:', channelMentions.value)
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
  }
}
