<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useMusicStore, type Song } from '../stores/music'
import { useVoiceStore } from '../stores/voice'
import { Music, Bot, SkipBack, Pause, Play, SkipForward, Plus, Trash2, X, Search } from 'lucide-vue-next'

const music = useMusicStore()
const voice = useVoiceStore()

const searchInput = ref('')
const showSearch = ref(false)
const loginPollingInterval = ref<number | null>(null)
const audioRef = ref<HTMLAudioElement | null>(null)

onMounted(async () => {
  await music.checkLoginStatus()
  await music.refreshQueue()
  await music.getBotStatus()
})

onUnmounted(() => {
  if (loginPollingInterval.value) {
    clearInterval(loginPollingInterval.value)
  }
})

// Watch for song URL changes to auto-play
watch(() => music.currentSongUrl, (url) => {
  if (url && audioRef.value) {
    audioRef.value.src = url
    if (music.isPlaying) {
      audioRef.value.play()
    }
  }
})

watch(() => music.isPlaying, (playing) => {
  if (audioRef.value) {
    if (playing) {
      audioRef.value.play()
    } else {
      audioRef.value.pause()
    }
  }
})

async function startLogin() {
  await music.getQRCode()
  
  // Start polling for login status
  loginPollingInterval.value = window.setInterval(async () => {
    const success = await music.pollLoginStatus()
    if (success || music.loginStatus === 'expired' || music.loginStatus === 'refused') {
      if (loginPollingInterval.value) {
        clearInterval(loginPollingInterval.value)
        loginPollingInterval.value = null
      }
    }
  }, 2000)
}

function handleSearch() {
  music.search(searchInput.value)
}

async function handleAddToQueue(song: Song) {
  await music.addToQueue(song)
  showSearch.value = false
  searchInput.value = ''
  music.searchResults = []
}

function handleAudioEnded() {
  music.skip()
}

async function handleBotPlayPause() {
  if (music.isPlaying) {
    await music.botPause()
  } else if (voice.currentVoiceChannel) {
    const roomName = `voice_${voice.currentVoiceChannel.id}`
    await music.botPlay(roomName)
  }
}
</script>

<template>
  <div class="music-panel">
    <div class="music-header">
      <Music class="header-icon" :size="20" />
      <span class="header-title">Music Player</span>
      <span 
        v-if="music.botConnected" 
        class="bot-status connected"
        @click="music.stopBot()"
        title="Bot connected - Click to disconnect"
      >
        <Bot :size="14" /> Bot
      </span>
      <span 
        v-if="music.isLoggedIn" 
        class="login-status logged-in"
        @click="music.logout()"
        title="Click to logout"
      >
        QQ VIP
      </span>
      <span 
        v-else 
        class="login-status"
        @click="startLogin"
      >
        Login
      </span>
    </div>

    <div class="music-content">
      <!-- QR Code Login Dialog -->
      <div v-if="music.qrCodeUrl" class="qr-login-overlay" @click.self="music.qrCodeUrl = null">
        <div class="qr-login-dialog">
          <h3>Scan to Login QQ Music</h3>
          <img :src="music.qrCodeUrl" alt="QR Code" class="qr-code" />
          <p class="login-hint">
            {{ music.loginStatus === 'waiting' ? 'Waiting for scan...' :
               music.loginStatus === 'scanned' ? 'Scanned! Confirm on phone...' :
               music.loginStatus === 'expired' ? 'QR Code expired' :
               music.loginStatus === 'refused' ? 'Login refused' :
               'Loading...' }}
          </p>
          <button v-if="music.loginStatus === 'expired'" class="refresh-btn" @click="startLogin">
            Refresh QR Code
          </button>
        </div>
      </div>

      <!-- Now Playing -->
      <div v-if="music.currentSong" class="now-playing">
        <img :src="music.currentSong.cover" alt="Cover" class="album-cover" />
        <div class="song-info">
          <div class="song-name">{{ music.currentSong.name }}</div>
          <div class="song-artist">{{ music.currentSong.artist }}</div>
        </div>
        <div class="playback-controls">
          <button class="control-btn" @click="music.previous()" title="Previous"><SkipBack :size="18" /></button>
          <button 
            class="control-btn play-btn" 
            @click="handleBotPlayPause"
            :disabled="!voice.isConnected"
            :title="voice.isConnected ? '' : 'Join voice channel first'"
          >
            <Pause v-if="music.isPlaying" :size="22" />
            <Play v-else :size="22" />
          </button>
          <button class="control-btn" @click="music.skip()" title="Next"><SkipForward :size="18" /></button>
        </div>
      </div>

      <!-- Empty State -->
      <div v-else class="empty-state">
        <Music class="empty-icon" :size="48" />
        <p>No song playing</p>
        <button class="add-song-btn glow-effect" @click="showSearch = true">
          Add Songs
        </button>
      </div>

      <!-- Queue -->
      <div class="queue-section">
        <div class="queue-header">
          <span>Queue ({{ music.queue.length }})</span>
          <div class="queue-actions">
            <button class="icon-btn" @click="showSearch = true" title="Add song"><Plus :size="16" /></button>
            <button 
              v-if="music.queue.length > 0" 
              class="icon-btn" 
              @click="music.clearQueue()" 
              title="Clear queue"
            ><Trash2 :size="16" /></button>
          </div>
        </div>
        <div class="queue-list">
          <div 
            v-for="(item, index) in music.queue" 
            :key="index"
            class="queue-item"
            :class="{ current: index === music.currentIndex }"
          >
            <img :src="item.song.cover" alt="Cover" class="queue-cover" />
            <div class="queue-info">
              <div class="queue-song-name">{{ item.song.name }}</div>
              <div class="queue-song-artist">{{ item.song.artist }}</div>
            </div>
            <span class="queue-duration">{{ music.formatDuration(item.song.duration) }}</span>
            <button class="remove-btn" @click="music.removeFromQueue(index)"><X :size="14" /></button>
          </div>
          <div v-if="music.queue.length === 0" class="queue-empty">
            Queue is empty
          </div>
        </div>
      </div>

      <!-- Search Dialog -->
      <Teleport to="body">
        <div v-if="showSearch" class="search-overlay" @click.self="showSearch = false">
          <div class="search-dialog">
            <div class="search-header">
              <input 
                v-model="searchInput"
                type="text"
                placeholder="Search songs..."
                class="search-input"
                @keyup.enter="handleSearch"
                autofocus
              />
              <button class="search-btn" @click="handleSearch" :disabled="music.isSearching">
                <span v-if="music.isSearching">...</span>
                <Search v-else :size="18" />
              </button>
            </div>
            <div class="search-results">
              <div 
                v-for="song in music.searchResults" 
                :key="song.mid"
                class="search-item"
                @click="handleAddToQueue(song)"
              >
                <img :src="song.cover" alt="Cover" class="search-cover" />
                <div class="search-info">
                  <div class="search-song-name">{{ song.name }}</div>
                  <div class="search-song-artist">{{ song.artist }} · {{ song.album }}</div>
                </div>
                <span class="search-duration">{{ music.formatDuration(song.duration) }}</span>
              </div>
              <div v-if="music.searchResults.length === 0 && searchInput" class="search-empty">
                {{ music.isSearching ? 'Searching...' : 'No results found' }}
              </div>
            </div>
            <button class="close-search" @click="showSearch = false">Close</button>
          </div>
        </div>
      </Teleport>

      <!-- Hidden audio element for local playback -->
      <audio 
        ref="audioRef" 
        @ended="handleAudioEnded"
        style="display: none;"
      />
    </div>
  </div>
</template>

<style scoped>
.music-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-height: 100%;
  overflow: hidden;
}

.music-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
  flex-shrink: 0;
}

.header-icon {
  font-size: 20px;
  margin-right: 8px;
}

.header-title {
  font-weight: 600;
  color: var(--color-text-main);
}

.login-status {
  margin-left: auto;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 12px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.1);
  transition: all 0.2s;
}

.login-status:hover {
  background: rgba(255, 255, 255, 0.2);
}

.login-status.logged-in {
  background: linear-gradient(135deg, #10b981, #059669);
  color: #fff;
}

.bot-status {
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 12px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.1);
  transition: all 0.2s;
  margin-right: 8px;
}

.bot-status:hover {
  background: rgba(255, 255, 255, 0.2);
}

.bot-status.connected {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
}

.music-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  overflow: hidden;
}

/* QR Login Dialog */
.qr-login-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.8);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
}

.qr-login-dialog {
  background: var(--surface-glass-strong, rgba(30, 30, 40, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 16px;
  padding: 24px;
  text-align: center;
}

.qr-login-dialog h3 {
  margin: 0 0 16px;
  color: var(--color-text-main);
}

.qr-code {
  width: 200px;
  height: 200px;
  border-radius: 8px;
  background: #fff;
}

.login-hint {
  margin: 16px 0 0;
  color: var(--color-text-muted);
  font-size: 14px;
}

.refresh-btn {
  margin-top: 12px;
  padding: 8px 16px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

/* Now Playing */
.now-playing {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

.album-cover {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  object-fit: cover;
}

.song-info {
  flex: 1;
  min-width: 0;
}

.song-name {
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.song-artist {
  font-size: 13px;
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playback-controls {
  display: flex;
  gap: 8px;
}

.control-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  font-size: 18px;
  cursor: pointer;
  transition: all 0.2s;
}

.control-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  transform: scale(1.1);
}

.play-btn {
  width: 48px;
  height: 48px;
  font-size: 22px;
  background: var(--color-gradient-primary);
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 32px;
  flex-shrink: 0;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-state p {
  color: var(--color-text-muted);
  margin-bottom: 16px;
}

.add-song-btn {
  background: var(--color-gradient-primary);
  color: #fff;
  border: none;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
}

/* Queue Section */
.queue-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  overflow: hidden;
}

.queue-header {
  padding: 12px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
  color: var(--color-text-main);
  font-weight: 600;
}

.queue-actions {
  display: flex;
  gap: 8px;
}

.icon-btn {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: background 0.2s;
}

.icon-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.queue-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.queue-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 8px;
  transition: background 0.2s;
}

.queue-item:hover {
  background: rgba(255, 255, 255, 0.05);
}

.queue-item.current {
  background: rgba(99, 102, 241, 0.2);
}

.queue-cover {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  object-fit: cover;
}

.queue-info {
  flex: 1;
  min-width: 0;
}

.queue-song-name {
  font-size: 14px;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.queue-song-artist {
  font-size: 12px;
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.queue-duration {
  font-size: 12px;
  color: var(--color-text-muted);
}

.remove-btn {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px 8px;
  opacity: 0;
  transition: opacity 0.2s;
}

.queue-item:hover .remove-btn {
  opacity: 1;
}

.remove-btn:hover {
  color: var(--color-error);
}

.queue-empty {
  text-align: center;
  padding: 24px;
  color: var(--color-text-muted);
  font-size: 14px;
}

/* Search Dialog */
.search-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.8);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
  padding: 20px;
}

.search-dialog {
  background: var(--surface-glass-strong, rgba(30, 30, 40, 0.98));
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 16px;
  width: 100%;
  max-width: 500px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.search-header {
  display: flex;
  gap: 8px;
  padding: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.search-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  padding: 12px 16px;
  color: var(--color-text-main);
  font-size: 16px;
}

.search-input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.search-btn {
  background: var(--color-primary);
  border: none;
  border-radius: 8px;
  padding: 0 16px;
  font-size: 18px;
  cursor: pointer;
}

.search-results {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.search-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.search-item:hover {
  background: rgba(255, 255, 255, 0.1);
}

.search-cover {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  object-fit: cover;
}

.search-info {
  flex: 1;
  min-width: 0;
}

.search-song-name {
  color: var(--color-text-main);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-song-artist {
  font-size: 13px;
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-duration {
  font-size: 13px;
  color: var(--color-text-muted);
}

.search-empty {
  text-align: center;
  padding: 32px;
  color: var(--color-text-muted);
}

.close-search {
  margin: 16px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  border-radius: 8px;
  color: var(--color-text-main);
  cursor: pointer;
  font-size: 14px;
}

.close-search:hover {
  background: rgba(255, 255, 255, 0.2);
}
</style>
