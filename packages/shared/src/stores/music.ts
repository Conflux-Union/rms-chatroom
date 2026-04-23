import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from './auth'
import { useVoiceStore } from './voice'
import { authFetch } from '../utils/authFetch'

const API_BASE = import.meta.env.VITE_API_BASE || 'https://chatroom.rms.net.cn'
const WS_BASE = import.meta.env.VITE_WS_BASE || 'wss://chatroom.rms.net.cn'


export type MusicPlatform = 'qq' | 'netease' | 'all'

export interface Song {
  mid: string
  name: string
  artist: string
  album: string
  duration: number
  cover: string
  platform: 'qq' | 'netease'
}

export interface PlatformLoginStatus {
  qq: { logged_in: boolean }
  netease: { logged_in: boolean }
}

export interface QueueItem {
  song: Song
  requested_by: string
}

export const useMusicStore = defineStore('music', () => {
  const auth = useAuthStore()
  
  // Login state (per platform)
  const platformLoginStatus = ref<PlatformLoginStatus>({
    qq: { logged_in: false },
    netease: { logged_in: false }
  })
  const isLoggedIn = ref(false)  // Legacy: true if any platform logged in
  const qrCodeUrl = ref<string | null>(null)
  const loginStatus = ref<string>('idle')
  const loginPlatform = ref<'qq' | 'netease'>('qq')  // Current login platform
  const loginType = ref<'qq' | 'wx'>('qq')  // QQ Music login method (QQ or WeChat)
  
  // Search state
  const searchPlatform = ref<MusicPlatform>('all')
  const searchQuery = ref('')
  const searchResults = ref<Song[]>([])
  const isSearching = ref(false)
  
  // Playback state
  const isPlaying = ref(false)
  const currentSong = ref<Song | null>(null)
  const currentIndex = ref(0)
  const queue = ref<QueueItem[]>([])
  const playbackState = ref<string>('idle')  // idle, loading, playing, paused, stopped
  const positionMs = ref(0)
  const durationMs = ref(0)
  
  // Current song URL for audio playback
  const currentSongUrl = ref<string | null>(null)

  // Bot state
  const botConnected = ref(false)
  const botRoom = ref<string | null>(null)

  // WebSocket and audio state (managed by store, not component)
  let musicWs: WebSocket | null = null
  let currentWsRoom: string | null = null
  let audioElement: HTMLAudioElement | null = null
  const volume = ref(parseFloat(localStorage.getItem('musicVolume') || '1.0'))
  const wsConnected = ref(false)

  const jsonHeaders = { 'Content-Type': 'application/json' }

  // --- Login functions ---
  
  async function checkAllLoginStatus() {
    try {
      const res = await authFetch(`${API_BASE}/api/music/login/check/all`)
      const data = await res.json() as PlatformLoginStatus
      platformLoginStatus.value = data
      isLoggedIn.value = data.qq.logged_in || data.netease.logged_in
      return data
    } catch {
      platformLoginStatus.value = { qq: { logged_in: false }, netease: { logged_in: false } }
      isLoggedIn.value = false
      return platformLoginStatus.value
    }
  }
  
  async function checkLoginStatus(platform: 'qq' | 'netease' = 'qq') {
    try {
      const res = await authFetch(`${API_BASE}/api/music/login/check?platform=${platform}`)
      const data = await res.json()
      if (platform === 'qq') {
        platformLoginStatus.value.qq.logged_in = data.logged_in
      } else {
        platformLoginStatus.value.netease.logged_in = data.logged_in
      }
      isLoggedIn.value = platformLoginStatus.value.qq.logged_in || platformLoginStatus.value.netease.logged_in
      return data.logged_in
    } catch {
      return false
    }
  }
  
  async function getQRCode(platform: 'qq' | 'netease' = 'qq', qqLoginType: 'qq' | 'wx' = 'qq') {
    try {
      loginStatus.value = 'loading'
      loginPlatform.value = platform
      loginType.value = qqLoginType
      let url = `${API_BASE}/api/music/login/qrcode?platform=${platform}`
      if (platform === 'qq') {
        url += `&login_type=${qqLoginType}`
      }
      const res = await fetch(url)
      const data = await res.json()
      qrCodeUrl.value = data.qrcode
      loginStatus.value = 'waiting'
      return data.qrcode
    } catch (e) {
      loginStatus.value = 'error'
      console.error('Failed to get QR code:', e)
      return null
    }
  }
  
  async function pollLoginStatus(): Promise<boolean> {
    try {
      const res = await fetch(`${API_BASE}/api/music/login/status?platform=${loginPlatform.value}`)
      const data = await res.json()
      loginStatus.value = data.status
      
      if (data.status === 'success') {
        if (loginPlatform.value === 'qq') {
          platformLoginStatus.value.qq.logged_in = true
        } else {
          platformLoginStatus.value.netease.logged_in = true
        }
        isLoggedIn.value = true
        qrCodeUrl.value = null
        return true
      }
      
      if (data.status === 'expired' || data.status === 'refused') {
        if (data.status === 'refused') {
          qrCodeUrl.value = null
        }
        return false
      }
      
      return false
    } catch {
      return false
    }
  }
  
  async function logout(platform: 'qq' | 'netease' = 'qq') {
    try {
      await authFetch(`${API_BASE}/api/music/login/logout?platform=${platform}`, {
        method: 'POST',
      })
      if (platform === 'qq') {
        platformLoginStatus.value.qq.logged_in = false
      } else {
        platformLoginStatus.value.netease.logged_in = false
      }
      isLoggedIn.value = platformLoginStatus.value.qq.logged_in || platformLoginStatus.value.netease.logged_in
    } catch (e) {
      console.error('Logout failed:', e)
    }
  }
  
  // --- Search functions ---
  
  async function search(keyword: string, platform: MusicPlatform = searchPlatform.value) {
    if (!keyword.trim()) {
      searchResults.value = []
      return
    }
    
    try {
      isSearching.value = true
      const res = await authFetch(`${API_BASE}/api/music/search`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ keyword, num: 20, platform })
      })
      const data = await res.json()
      searchResults.value = data.songs || []
    } catch (e) {
      console.error('Search failed:', e)
      searchResults.value = []
    } finally {
      isSearching.value = false
    }
  }
  
  // --- Queue functions ---
  
  async function addToQueue(roomName: string, song: Song) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/queue/add`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName, song })
      })
      await res.json()
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Failed to add to queue:', e)
    }
  }
  
  async function removeFromQueue(roomName: string, index: number) {
    try {
      await authFetch(`${API_BASE}/api/music/queue/${roomName}/${index}`, {
        method: 'DELETE',
      })
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Failed to remove from queue:', e)
    }
  }
  
  async function clearQueue(roomName: string) {
    try {
      await authFetch(`${API_BASE}/api/music/queue/clear`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Failed to clear queue:', e)
    }
  }
  
  async function refreshQueue(roomName: string) {
    if (!roomName) return
    try {
      const res = await authFetch(`${API_BASE}/api/music/queue/${encodeURIComponent(roomName)}`)
      const data = await res.json()
      queue.value = data.queue || []
      currentIndex.value = data.current_index || 0
      currentSong.value = data.current_song || null
      isPlaying.value = data.is_playing || false
    } catch (e) {
      console.error('Failed to refresh queue:', e)
    }
  }
  
  // --- Utility functions ---
  
  async function getSongUrl(mid: string, platform: 'qq' | 'netease' = 'qq'): Promise<string | null> {
    try {
      const res = await authFetch(`${API_BASE}/api/music/song/${mid}/url?platform=${platform}`)
      const data = await res.json()
      return data.url || null
    } catch (e) {
      console.error('Failed to get song URL:', e)
      return null
    }
  }
  
  function formatDuration(seconds: number): string {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }
  
  // --- Bot functions ---
  
  async function startBot(roomName: string) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/start`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      const data = await res.json()
      if (data.success) {
        botConnected.value = true
        botRoom.value = roomName
      }
      return data.success
    } catch (e) {
      console.error('Failed to start bot:', e)
      return false
    }
  }
  
  async function stopBot(roomName: string) {
    try {
      await authFetch(`${API_BASE}/api/music/bot/stop`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      botConnected.value = false
      botRoom.value = null
    } catch (e) {
      console.error('Failed to stop bot:', e)
    }
  }
  
  async function getBotStatus(roomName: string) {
    if (!roomName) return null
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/status/${roomName}`)
      const data = await res.json()
      botConnected.value = data.connected
      botRoom.value = data.room
      isPlaying.value = data.is_playing
      return data
    } catch (e) {
      console.error('Failed to get bot status:', e)
      return null
    }
  }
  
  async function botPlay(roomName: string) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/play`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      const data = await res.json()
      if (data.success) {
        isPlaying.value = true
        botConnected.value = true
        botRoom.value = roomName
      }
      return data.success
    } catch (e) {
      console.error('Bot play failed:', e)
      return false
    }
  }
  
  async function botPause(roomName: string) {
    try {
      await authFetch(`${API_BASE}/api/music/bot/pause`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      isPlaying.value = false
      playbackState.value = 'paused'
    } catch (e) {
      console.error('Bot pause failed:', e)
    }
  }
  
  async function botResume(roomName: string) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/resume`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      const data = await res.json()
      if (data.success) {
        isPlaying.value = data.is_playing
        playbackState.value = 'playing'
      }
    } catch (e) {
      console.error('Bot resume failed:', e)
    }
  }
  
  async function botSkip(roomName: string) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/skip`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      await res.json()
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Bot skip failed:', e)
    }
  }
  
  async function botPrevious(roomName: string) {
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/previous`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName })
      })
      await res.json()
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Bot previous failed:', e)
    }
  }
  
  async function botSeek(roomName: string, seekPositionMs: number) {
    try {
      await authFetch(`${API_BASE}/api/music/bot/seek`, {
        method: 'POST',
        headers: jsonHeaders,
        body: JSON.stringify({ room_name: roomName, position_ms: seekPositionMs })
      })
    } catch (e) {
      console.error('Bot seek failed:', e)
    }
  }
  
  async function getProgress(roomName: string) {
    if (!roomName) return null
    try {
      const res = await authFetch(`${API_BASE}/api/music/bot/progress/${roomName}`)
      const data = await res.json()
      positionMs.value = data.position_ms || 0
      durationMs.value = data.duration_ms || 0
      playbackState.value = data.state || 'idle'
      if (data.current_song) {
        currentSong.value = data.current_song
      }
      return data
    } catch (e) {
      console.error('Failed to get progress:', e)
      return null
    }
  }

  // Called from WebSocket to update progress
  function updateProgress(data: {
    position_ms: number;
    duration_ms: number;
    state: string;
    current_song?: Song;
    current_index?: number;
  }) {
    positionMs.value = data.position_ms
    durationMs.value = data.duration_ms
    playbackState.value = data.state
    isPlaying.value = data.state === 'playing'
    if (data.current_song) {
      currentSong.value = data.current_song
    }
    if (data.current_index !== undefined) {
      currentIndex.value = data.current_index
    }
  }

  // --- WebSocket and Audio Management ---

  function ensureAudioElement(): HTMLAudioElement {
    if (!audioElement) {
      audioElement = document.createElement('audio')
      audioElement.style.display = 'none'
      audioElement.volume = volume.value
      document.body.appendChild(audioElement)

      // Drive progress bar from local audio playback
      audioElement.addEventListener('timeupdate', () => {
        if (audioElement) {
          positionMs.value = Math.floor(audioElement.currentTime * 1000)
        }
      })
      audioElement.addEventListener('ended', () => {
        // Audio finished, let backend handle next song via WS
        playbackState.value = 'idle'
        isPlaying.value = false
      })
    }
    return audioElement
  }

  function connectMusicWs(roomName: string) {
    if (!auth.token || !roomName) return

    // Disconnect existing connection if room changed
    if (musicWs && currentWsRoom !== roomName) {
      musicWs.close()
      musicWs = null
    }

    if (musicWs) return // Already connected to same room

    currentWsRoom = roomName
    const url = `${WS_BASE}/ws/music?token=${auth.token}&room_name=${encodeURIComponent(roomName)}`
    musicWs = new WebSocket(url)

    musicWs.onopen = () => {
      console.log(`[MusicStore] WebSocket connected to room ${roomName}`)
      wsConnected.value = true
    }

    musicWs.onclose = () => {
      console.log('[MusicStore] WebSocket disconnected')
      wsConnected.value = false
      musicWs = null
      const voice = useVoiceStore()
      setTimeout(async () => {
        const currentRoom = voice.currentVoiceChannel ? `voice_${voice.currentVoiceChannel.id}` : null
        if (!auth.token || !currentRoom || currentRoom !== currentWsRoom) return
        if (auth.canRecoverSession()) {
          try {
            const payload = JSON.parse(atob(auth.token.split('.')[1]))
            if (payload.exp * 1000 - Date.now() < 30_000) {
              await auth.doRefreshToken()
            }
          } catch { /* proceed anyway */ }
        }
        // Re-validate after async refresh: room may have changed while awaiting
        const roomAfterRefresh = voice.currentVoiceChannel ? `voice_${voice.currentVoiceChannel.id}` : null
        if (roomAfterRefresh !== currentRoom) return
        connectMusicWs(currentRoom)
      }, 3000)
    }

    musicWs.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        const voice = useVoiceStore()
        const currentRoom = voice.currentVoiceChannel ? `voice_${voice.currentVoiceChannel.id}` : null

        // Handle global music events (no room_name check)
        if (msg.type === 'music_login_status') {
          // Update login status from WebSocket push
          loginStatus.value = msg.status
          if (msg.status === 'error' && msg.message) {
            console.warn(`[MusicStore] Login error (${msg.platform}):`, msg.message)
          }
          if (msg.status === 'success') {
            if (msg.platform === 'qq') {
              platformLoginStatus.value.qq.logged_in = true
            } else if (msg.platform === 'netease') {
              platformLoginStatus.value.netease.logged_in = true
            }
            isLoggedIn.value = true
            qrCodeUrl.value = null
          } else if (msg.status === 'refused') {
            qrCodeUrl.value = null
          }
          // Note: 'expired' keeps modal open so user can click "refresh QR code"
          return
        }

        // Handle playback commands - only process if for our room
        if (msg.room_name && msg.room_name !== currentRoom) {
          return // Ignore messages for other rooms
        }

        const audio = ensureAudioElement()

        if (msg.type === 'play') {
          // Play new song
          console.log('[MusicStore] Received play command:', msg.song?.name, 'URL:', msg.url)

          // Update store state from the play message
          if (msg.song) {
            currentSong.value = msg.song
            durationMs.value = (msg.song.duration || 0) * 1000
          }
          isPlaying.value = true
          playbackState.value = 'playing'
          positionMs.value = msg.position_ms || 0
          if (msg.current_index !== undefined) {
            currentIndex.value = msg.current_index
          }

          // Refresh queue to sync full state
          const roomName = msg.room_name || currentWsRoom
          if (roomName) {
            refreshQueue(roomName)
          }

          audio.src = msg.url
          audio.currentTime = (msg.position_ms || 0) / 1000
          audio.play().catch(e => console.error('[MusicStore] Play failed:', e))
        } else if (msg.type === 'pause') {
          // Pause playback
          console.log('[MusicStore] Received pause command')
          isPlaying.value = false
          playbackState.value = 'paused'
          audio.pause()
        } else if (msg.type === 'resume') {
          // Resume playback
          console.log('[MusicStore] Received resume command, position:', msg.position_ms)
          isPlaying.value = true
          playbackState.value = 'playing'
          audio.currentTime = (msg.position_ms || 0) / 1000
          audio.play().catch(e => console.error('[MusicStore] Resume failed:', e))
        } else if (msg.type === 'seek') {
          // Seek to position
          console.log('[MusicStore] Received seek command, position:', msg.position_ms)
          audio.currentTime = (msg.position_ms || 0) / 1000
        } else if (msg.type === 'music_state' && msg.data) {
          // Only process if for our room
          if (msg.data.room_name && msg.data.room_name !== currentRoom) {
            return
          }
          // Update music store with real-time state
          updateProgress(msg.data)
          // Also refresh queue to sync current index
          if (msg.data.current_index !== undefined) {
            const roomName = msg.data.room_name || currentRoom
            if (roomName) {
              refreshQueue(roomName)
            }
          }
        } else if (msg.type === 'song_unavailable') {
          // Show notification for unavailable song
          console.warn(`[MusicStore] Song unavailable: ${msg.song_name} - ${msg.reason}`)
        }
      } catch (e) {
        console.error('[MusicStore] Failed to handle WebSocket message:', e)
      }
    }
  }

  function disconnectMusicWs() {
    if (musicWs) {
      musicWs.close()
      musicWs = null
    }
    wsConnected.value = false
    currentWsRoom = null
  }

  function setVolume(newVolume: number) {
    volume.value = newVolume
    if (audioElement) {
      audioElement.volume = newVolume
    }
    localStorage.setItem('musicVolume', newVolume.toString())
  }

  function getAudioElement(): HTMLAudioElement | null {
    return audioElement
  }

  // Watch voice channel changes and auto-connect/disconnect WebSocket
  function initVoiceChannelWatcher() {
    const voice = useVoiceStore()

    watch(
      () => voice.currentVoiceChannel,
      async (newChannel, oldChannel) => {
        if (newChannel) {
          const roomName = `voice_${newChannel.id}`
          await refreshQueue(roomName)
          await getBotStatus(roomName)
          connectMusicWs(roomName)
        } else if (oldChannel) {
          // Left voice channel, disconnect WebSocket and stop audio
          disconnectMusicWs()
          if (audioElement) {
            audioElement.pause()
            audioElement.src = ''
          }
        }
      },
      { immediate: true }
    )
  }

  // Initialize watcher when store is created
  initVoiceChannelWatcher()

  return {
    // Login state
    isLoggedIn,
    platformLoginStatus,
    loginPlatform,
    loginType,
    qrCodeUrl,
    loginStatus,
    checkAllLoginStatus,
    checkLoginStatus,
    getQRCode,
    pollLoginStatus,
    logout,

    // Search state
    searchQuery,
    searchResults,
    searchPlatform,
    isSearching,
    search,

    // Queue state
    queue,
    currentIndex,
    currentSong,
    currentSongUrl,
    addToQueue,
    removeFromQueue,
    clearQueue,
    refreshQueue,

    // Playback state
    isPlaying,
    playbackState,
    positionMs,
    durationMs,
    getSongUrl,

    // Bot state
    botConnected,
    botRoom,
    startBot,
    stopBot,
    getBotStatus,
    botPlay,
    botPause,
    botResume,
    botSkip,
    botPrevious,
    botSeek,
    getProgress,
    updateProgress,

    // WebSocket and audio state
    volume,
    wsConnected,
    setVolume,
    getAudioElement,
    connectMusicWs,
    disconnectMusicWs,

    // Utils
    formatDuration
  }
})
