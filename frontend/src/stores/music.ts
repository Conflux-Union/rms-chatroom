import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export interface Song {
  mid: string
  name: string
  artist: string
  album: string
  duration: number
  cover: string
}

export interface QueueItem {
  song: Song
  requested_by: string
}

export const useMusicStore = defineStore('music', () => {
  const auth = useAuthStore()
  
  // Login state
  const isLoggedIn = ref(false)
  const qrCodeUrl = ref<string | null>(null)
  const loginStatus = ref<string>('idle')
  
  // Search state
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
  
  const headers = () => ({
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${auth.token}`
  })

  // --- Login functions ---
  
  async function checkLoginStatus() {
    try {
      const res = await fetch(`${API_BASE}/api/music/login/check`, {
        headers: headers()
      })
      const data = await res.json()
      isLoggedIn.value = data.logged_in
      return data.logged_in
    } catch {
      isLoggedIn.value = false
      return false
    }
  }
  
  async function getQRCode() {
    try {
      loginStatus.value = 'loading'
      const res = await fetch(`${API_BASE}/api/music/login/qrcode`)
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
      const res = await fetch(`${API_BASE}/api/music/login/status`)
      const data = await res.json()
      loginStatus.value = data.status
      
      if (data.status === 'success') {
        isLoggedIn.value = true
        qrCodeUrl.value = null
        return true
      }
      
      if (data.status === 'expired' || data.status === 'refused') {
        qrCodeUrl.value = null
        return false
      }
      
      return false
    } catch {
      return false
    }
  }
  
  async function logout() {
    try {
      await fetch(`${API_BASE}/api/music/login/logout`, {
        method: 'POST',
        headers: headers()
      })
      isLoggedIn.value = false
    } catch (e) {
      console.error('Logout failed:', e)
    }
  }
  
  // --- Search functions ---
  
  async function search(keyword: string) {
    if (!keyword.trim()) {
      searchResults.value = []
      return
    }
    
    try {
      isSearching.value = true
      const res = await fetch(`${API_BASE}/api/music/search`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ keyword, num: 20 })
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
      const res = await fetch(`${API_BASE}/api/music/queue/add`, {
        method: 'POST',
        headers: headers(),
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
      await fetch(`${API_BASE}/api/music/queue/${roomName}/${index}`, {
        method: 'DELETE',
        headers: headers()
      })
      await refreshQueue(roomName)
    } catch (e) {
      console.error('Failed to remove from queue:', e)
    }
  }
  
  async function clearQueue(roomName: string) {
    try {
      await fetch(`${API_BASE}/api/music/queue/clear`, {
        method: 'POST',
        headers: headers(),
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
      const res = await fetch(`${API_BASE}/api/music/queue/${roomName}`, {
        headers: headers()
      })
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
  
  async function getSongUrl(mid: string): Promise<string | null> {
    try {
      const res = await fetch(`${API_BASE}/api/music/song/${mid}/url`, {
        headers: headers()
      })
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
      const res = await fetch(`${API_BASE}/api/music/bot/start`, {
        method: 'POST',
        headers: headers(),
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
      await fetch(`${API_BASE}/api/music/bot/stop`, {
        method: 'POST',
        headers: headers(),
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
      const res = await fetch(`${API_BASE}/api/music/bot/status/${roomName}`, {
        headers: headers()
      })
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
      const res = await fetch(`${API_BASE}/api/music/bot/play`, {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
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
      await fetch(`${API_BASE}/api/music/bot/pause`, {
        method: 'POST',
        headers: headers(),
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
      const res = await fetch(`${API_BASE}/api/music/bot/resume`, {
        method: 'POST',
        headers: headers(),
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
      const res = await fetch(`${API_BASE}/api/music/bot/skip`, {
        method: 'POST',
        headers: headers(),
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
      const res = await fetch(`${API_BASE}/api/music/bot/previous`, {
        method: 'POST',
        headers: headers(),
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
      await fetch(`${API_BASE}/api/music/bot/seek`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ room_name: roomName, position_ms: seekPositionMs })
      })
    } catch (e) {
      console.error('Bot seek failed:', e)
    }
  }
  
  async function getProgress(roomName: string) {
    if (!roomName) return null
    try {
      const res = await fetch(`${API_BASE}/api/music/bot/progress/${roomName}`, {
        headers: headers()
      })
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
  
  return {
    // Login state
    isLoggedIn,
    qrCodeUrl,
    loginStatus,
    checkLoginStatus,
    getQRCode,
    pollLoginStatus,
    logout,
    
    // Search state
    searchQuery,
    searchResults,
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
    
    // Utils
    formatDuration
  }
})
