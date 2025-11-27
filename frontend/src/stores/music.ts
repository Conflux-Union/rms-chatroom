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
  
  async function addToQueue(song: Song) {
    try {
      const res = await fetch(`${API_BASE}/api/music/queue/add`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(song)
      })
      await res.json()
      await refreshQueue()
    } catch (e) {
      console.error('Failed to add to queue:', e)
    }
  }
  
  async function removeFromQueue(index: number) {
    try {
      await fetch(`${API_BASE}/api/music/queue/${index}`, {
        method: 'DELETE',
        headers: headers()
      })
      await refreshQueue()
    } catch (e) {
      console.error('Failed to remove from queue:', e)
    }
  }
  
  async function clearQueue() {
    try {
      await fetch(`${API_BASE}/api/music/queue/clear`, {
        method: 'POST',
        headers: headers()
      })
      await refreshQueue()
    } catch (e) {
      console.error('Failed to clear queue:', e)
    }
  }
  
  async function refreshQueue() {
    try {
      const res = await fetch(`${API_BASE}/api/music/queue`, {
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
  
  // --- Playback control ---
  
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
  
  async function play() {
    try {
      await fetch(`${API_BASE}/api/music/control/play`, {
        method: 'POST',
        headers: headers()
      })
      isPlaying.value = true
      
      // Get current song URL if we have a current song
      if (currentSong.value && !currentSongUrl.value) {
        currentSongUrl.value = await getSongUrl(currentSong.value.mid)
      }
    } catch (e) {
      console.error('Play failed:', e)
    }
  }
  
  async function pause() {
    try {
      await fetch(`${API_BASE}/api/music/control/pause`, {
        method: 'POST',
        headers: headers()
      })
      isPlaying.value = false
    } catch (e) {
      console.error('Pause failed:', e)
    }
  }
  
  async function skip() {
    try {
      await fetch(`${API_BASE}/api/music/control/skip`, {
        method: 'POST',
        headers: headers()
      })
      await refreshQueue()
      
      // Get new song URL
      if (currentSong.value) {
        currentSongUrl.value = await getSongUrl(currentSong.value.mid)
      }
    } catch (e) {
      console.error('Skip failed:', e)
    }
  }
  
  async function previous() {
    try {
      await fetch(`${API_BASE}/api/music/control/previous`, {
        method: 'POST',
        headers: headers()
      })
      await refreshQueue()
      
      // Get new song URL
      if (currentSong.value) {
        currentSongUrl.value = await getSongUrl(currentSong.value.mid)
      }
    } catch (e) {
      console.error('Previous failed:', e)
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
  
  async function stopBot() {
    try {
      await fetch(`${API_BASE}/api/music/bot/stop`, {
        method: 'POST',
        headers: headers()
      })
      botConnected.value = false
      botRoom.value = null
    } catch (e) {
      console.error('Failed to stop bot:', e)
    }
  }
  
  async function getBotStatus() {
    try {
      const res = await fetch(`${API_BASE}/api/music/bot/status`, {
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
  
  async function botPlay() {
    try {
      const res = await fetch(`${API_BASE}/api/music/bot/play`, {
        method: 'POST',
        headers: headers()
      })
      const data = await res.json()
      if (data.success) {
        isPlaying.value = true
      }
      return data.success
    } catch (e) {
      console.error('Bot play failed:', e)
      return false
    }
  }
  
  async function botPause() {
    try {
      await fetch(`${API_BASE}/api/music/bot/pause`, {
        method: 'POST',
        headers: headers()
      })
      isPlaying.value = false
    } catch (e) {
      console.error('Bot pause failed:', e)
    }
  }
  
  async function botSkip() {
    try {
      const res = await fetch(`${API_BASE}/api/music/bot/skip`, {
        method: 'POST',
        headers: headers()
      })
      await res.json()
      await refreshQueue()
    } catch (e) {
      console.error('Bot skip failed:', e)
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
    play,
    pause,
    skip,
    previous,
    getSongUrl,
    
    // Bot state
    botConnected,
    botRoom,
    startBot,
    stopBot,
    getBotStatus,
    botPlay,
    botPause,
    botSkip,
    
    // Utils
    formatDuration
  }
})
