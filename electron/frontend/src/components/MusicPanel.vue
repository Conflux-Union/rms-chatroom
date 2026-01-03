<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useMusicStore, type Song } from '../stores/music'
import { useVoiceStore } from '../stores/voice'
import { useAuthStore } from '../stores/auth'
import { Music, Bot, SkipBack, Pause, Play, SkipForward, Plus, Trash2, X, Search, Loader2, Volume2 } from 'lucide-vue-next'
import Slider from '@vueform/slider'
import '@vueform/slider/themes/default.css'

const music = useMusicStore()
const voice = useVoiceStore()
const auth = useAuthStore()

const WS_BASE = import.meta.env.VITE_WS_BASE || 'ws://preview-chatroom.rms.net.cn'

const searchInput = ref('')
const showSearch = ref(false)
const showLoginSelect = ref(false)
const loginPollingInterval = ref<number | null>(null)
const audioRef = ref<HTMLAudioElement | null>(null)
const progressPollingInterval = ref<number | null>(null)
const musicWs = ref<WebSocket | null>(null)
const volume = ref(1.0)
const isProcessingPlayback = ref(false)

// Get current voice room name for music API calls
const currentRoomName = computed(() => {
  if (voice.currentVoiceChannel) {
    return `voice_${voice.currentVoiceChannel.id}`
  }
  return ''
})

// Progress value for slider (0-100)
const progressValue = computed({
  get: () => {
    if (music.durationMs <= 0) return 0
    return (music.positionMs / music.durationMs) * 100
  },
  set: (value: number) => {
    if (currentRoomName.value) {
      const newPosition = Math.floor((value / 100) * music.durationMs)
      music.botSeek(currentRoomName.value, newPosition)
    }
  }
})

// Format milliseconds to mm:ss
function formatTime(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

// Volume control handler
function handleVolumeChange(value: number) {
  volume.value = value
  if (audioRef.value) {
    audioRef.value.volume = value
  }
  localStorage.setItem('musicVolume', value.toString())
}

// Connect to music WebSocket for real-time state sync
function connectMusicWs() {
  if (!auth.token) return
  
  const url = `${WS_BASE}/ws/music?token=${auth.token}`
  musicWs.value = new WebSocket(url)
  
  musicWs.value.onopen = () => {
    console.log('Music WebSocket connected')
  }
  
  musicWs.value.onclose = () => {
    console.log('Music WebSocket disconnected')
    // Reconnect after 3 seconds
    setTimeout(() => {
      if (auth.token) connectMusicWs()
    }, 3000)
  }
  
  musicWs.value.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)

      // Handle playback commands
      if (msg.type === 'play') {
        if (!audioRef.value) {
          console.error('Audio element not ready, cannot play')
          return
        }
        // Play new song
        console.log('Received play command:', msg.song?.name, 'URL:', msg.url)
        audioRef.value.src = msg.url
        audioRef.value.currentTime = (msg.position_ms || 0) / 1000
        audioRef.value.play().catch(e => console.error('Play failed:', e))
      } else if (msg.type === 'pause') {
        if (!audioRef.value) {
          console.error('Audio element not ready, cannot pause')
          return
        }
        // Pause playback
        console.log('Received pause command')
        audioRef.value.pause()
      } else if (msg.type === 'resume') {
        if (!audioRef.value) {
          console.error('Audio element not ready, cannot resume')
          return
        }
        // Resume playback
        console.log('Received resume command, position:', msg.position_ms)
        audioRef.value.currentTime = (msg.position_ms || 0) / 1000
        audioRef.value.play().catch(e => console.error('Resume failed:', e))
      } else if (msg.type === 'seek') {
        if (!audioRef.value) {
          console.error('Audio element not ready, cannot seek')
          return
        }
        // Seek to position
        console.log('Received seek command, position:', msg.position_ms)
        audioRef.value.currentTime = (msg.position_ms || 0) / 1000
      } else if (msg.type === 'music_state' && msg.data) {
        // Update music store with real-time state
        music.updateProgress(msg.data)
        // Also refresh queue to sync current index (use room_name from message or current)
        if (msg.data.current_index !== undefined) {
          const roomName = msg.data.room_name || currentRoomName.value
          if (roomName) {
            music.refreshQueue(roomName)
          }
        }
      }
    } catch (e) {
      console.error('Failed to handle music WebSocket message:', e)
    }
  }
}

onMounted(async () => {
  await music.checkAllLoginStatus()
  if (currentRoomName.value) {
    await music.refreshQueue(currentRoomName.value)
    await music.getBotStatus(currentRoomName.value)
  }

  // Connect to music WebSocket
  connectMusicWs()

  // Poll progress every 1 second when playing
  progressPollingInterval.value = window.setInterval(async () => {
    if (music.isPlaying && currentRoomName.value) {
      await music.getProgress(currentRoomName.value)
    }
  }, 1000)

  // Load saved volume from localStorage
  const savedVolume = localStorage.getItem('musicVolume')
  if (savedVolume) {
    volume.value = parseFloat(savedVolume)
  }
  if (audioRef.value) {
    audioRef.value.volume = volume.value
  }
})

// Refresh queue when voice channel changes
watch(currentRoomName, async (newRoom) => {
  if (newRoom) {
    await music.refreshQueue(newRoom)
    await music.getBotStatus(newRoom)
  }
})

onUnmounted(() => {
  if (loginPollingInterval.value) {
    clearInterval(loginPollingInterval.value)
  }
  if (progressPollingInterval.value) {
    clearInterval(progressPollingInterval.value)
  }
  if (musicWs.value) {
    musicWs.value.close()
    musicWs.value = null
  }
})

// Watch for audio element changes to apply volume
watch(audioRef, (newAudio) => {
  if (newAudio) {
    newAudio.volume = volume.value
  }
})

async function startLogin(platform: 'qq' | 'netease' = 'qq') {
  showLoginSelect.value = false
  await music.getQRCode(platform)
  
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
  if (!currentRoomName.value) return
  await music.addToQueue(currentRoomName.value, song)
  showSearch.value = false
  searchInput.value = ''
  music.searchResults = []
}

function handleAudioEnded() {
  if (currentRoomName.value) {
    music.botSkip(currentRoomName.value)
  }
}

async function handleBotPlayPause() {
  if (!currentRoomName.value && music.playbackState !== 'paused') return

  // Prevent rapid clicks
  if (isProcessingPlayback.value) {
    console.log('Playback action already in progress, ignoring')
    return
  }

  isProcessingPlayback.value = true

  try {
    if (music.isPlaying) {
      console.log('Pausing playback')
      await music.botPause(currentRoomName.value)
    } else if (music.playbackState === 'paused') {
      // Resume from paused state
      console.log('Resuming playback')
      await music.botResume(currentRoomName.value)
    } else if (currentRoomName.value) {
      // Start new playback
      console.log('Starting new playback')
      await music.botPlay(currentRoomName.value)
    }
  } finally {
    // Add a small delay to prevent rapid toggling
    setTimeout(() => {
      isProcessingPlayback.value = false
    }, 300)
  }
}

async function handleClearQueue() {
  if (currentRoomName.value) {
    await music.clearQueue(currentRoomName.value)
  }
}

async function handleRemoveFromQueue(index: number) {
  if (currentRoomName.value) {
    await music.removeFromQueue(currentRoomName.value, index)
  }
}

async function handleBotSkip() {
  if (currentRoomName.value) {
    await music.botSkip(currentRoomName.value)
  }
}

async function handleBotPrevious() {
  if (currentRoomName.value) {
    await music.botPrevious(currentRoomName.value)
  }
}

async function handleStopBot() {
  if (currentRoomName.value) {
    await music.stopBot(currentRoomName.value)
  }
}
</script>

<template>
  <div class="music-panel">
    <div class="music-header">
      <Music class="header-icon" :size="20" />
      <span class="header-title">音乐播放器</span>
      <span 
        v-if="music.botConnected" 
        class="bot-status connected"
        @click="handleStopBot"
        title="机器人已连接 - 点击断开"
      >
        <Bot :size="14" /> 机器人
      </span>
      <span 
        v-if="music.platformLoginStatus.qq.logged_in" 
        class="login-status logged-in qq"
        @click="music.logout('qq')"
        title="QQ音乐已登录 - 点击退出"
      >
        QQ
      </span>
      <span 
        v-if="music.platformLoginStatus.netease.logged_in" 
        class="login-status logged-in netease"
        @click="music.logout('netease')"
        title="网易云已登录 - 点击退出"
      >
        网易云
      </span>
      <span 
        v-if="!music.platformLoginStatus.qq.logged_in || !music.platformLoginStatus.netease.logged_in"
        class="login-status"
        @click="showLoginSelect = true"
      >
        登录
      </span>
    </div>

    <div class="music-content">
      <!-- Login Platform Select Dialog -->
      <div v-if="showLoginSelect" class="qr-login-overlay" @click.self="showLoginSelect = false">
        <div class="qr-login-dialog">
          <h3>选择登录平台</h3>
          <div class="platform-select">
            <button 
              v-if="!music.platformLoginStatus.qq.logged_in"
              class="platform-btn qq" 
              @click="startLogin('qq')"
            >
              QQ 音乐
            </button>
            <button 
              v-if="!music.platformLoginStatus.netease.logged_in"
              class="platform-btn netease" 
              @click="startLogin('netease')"
            >
              网易云音乐
            </button>
          </div>
        </div>
      </div>

      <!-- QR Code Login Dialog -->
      <div v-if="music.qrCodeUrl" class="qr-login-overlay" @click.self="music.qrCodeUrl = null">
        <div class="qr-login-dialog">
          <h3>扫码登录 {{ music.loginPlatform === 'qq' ? 'QQ 音乐' : '网易云音乐' }}</h3>
          <img :src="music.qrCodeUrl" alt="QR Code" class="qr-code" />
          <p class="login-hint">
            {{ music.loginStatus === 'waiting' ? '等待扫码...' :
               music.loginStatus === 'scanned' ? '扫码成功！请在手机上确认...' :
               music.loginStatus === 'expired' ? '二维码已过期' :
               music.loginStatus === 'refused' ? '登录被拒绝' :
               '加载中...' }}
          </p>
          <button v-if="music.loginStatus === 'expired'" class="refresh-btn" @click="startLogin(music.loginPlatform)">
            刷新二维码
          </button>
        </div>
      </div>

      <!-- Now Playing -->
      <div v-if="music.currentSong" class="now-playing">
        <img :src="music.currentSong.cover" alt="Cover" class="album-cover" />
        <div class="song-details">
          <div class="song-info">
            <div class="song-name">{{ music.currentSong.name }}</div>
            <div class="song-artist">{{ music.currentSong.artist }}</div>
          </div>
          <!-- Progress Bar -->
          <div class="progress-container">
            <span class="time-current">{{ formatTime(music.positionMs) }}</span>
            <Slider
              v-model="progressValue"
              :min="0"
              :max="100"
              :tooltips="false"
              class="progress-slider"
            />
            <span class="time-total">{{ formatTime(music.durationMs) }}</span>
          </div>
        </div>
        <div class="controls-wrapper">
          <div class="playback-controls">
            <button class="control-btn" @click="handleBotPrevious" title="上一首"><SkipBack :size="18" /></button>
            <button
              class="control-btn play-btn"
              @click="handleBotPlayPause"
              :disabled="!voice.isConnected && music.playbackState !== 'paused' || isProcessingPlayback"
              :title="voice.isConnected || music.playbackState === 'paused' ? '' : '请先加入语音频道'"
            >
              <Loader2 v-if="music.playbackState === 'loading' || isProcessingPlayback" :size="22" class="spin" />
              <Pause v-else-if="music.isPlaying" :size="22" />
              <Play v-else :size="22" />
            </button>
            <button class="control-btn" @click="handleBotSkip" title="下一首"><SkipForward :size="18" /></button>
          </div>
          <div class="volume-control">
            <Volume2 :size="16" class="volume-icon" />
            <Slider
              :model-value="volume"
              @update:model-value="handleVolumeChange"
              :min="0"
              :max="1"
              :step="0.01"
              :tooltips="false"
              class="volume-slider"
            />
            <span class="volume-text">{{ Math.round(volume * 100) }}%</span>
          </div>
        </div>
      </div>

      <!-- Empty State -->
      <div v-else class="empty-state">
        <Music class="empty-icon" :size="48" />
        <p>暂无播放</p>
        <button class="add-song-btn glow-effect" @click="showSearch = true">
          添加歌曲
        </button>
      </div>

      <!-- Queue -->
      <div class="queue-section">
        <div class="queue-header">
          <span>播放队列 ({{ music.queue.length }})</span>
          <div class="queue-actions">
            <button class="icon-btn" @click="showSearch = true" title="添加歌曲"><Plus :size="16" /></button>
            <button 
              v-if="music.queue.length > 0" 
              class="icon-btn" 
              @click="handleClearQueue" 
              title="清空队列"
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
            <button class="remove-btn" @click="handleRemoveFromQueue(index)"><X :size="14" /></button>
          </div>
          <div v-if="music.queue.length === 0" class="queue-empty">
            队列为空
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
                placeholder="搜索歌曲..."
                class="search-input"
                @keyup.enter="handleSearch"
                autofocus
              />
              <select v-model="music.searchPlatform" class="platform-selector">
                <option value="all">全部</option>
                <option value="qq">QQ音乐</option>
                <option value="netease">网易云</option>
              </select>
              <button class="search-btn" @click="handleSearch" :disabled="music.isSearching">
                <span v-if="music.isSearching">...</span>
                <Search v-else :size="18" />
              </button>
            </div>
            <div class="search-results">
              <div 
                v-for="song in music.searchResults" 
                :key="`${song.platform}-${song.mid}`"
                class="search-item"
                @click="handleAddToQueue(song)"
              >
                <img :src="song.cover" alt="Cover" class="search-cover" />
                <div class="search-info">
                  <div class="search-song-name">
                    {{ song.name }}
                    <span class="platform-tag" :class="song.platform">
                      {{ song.platform === 'qq' ? 'QQ' : '网易云' }}
                    </span>
                  </div>
                  <div class="search-song-artist">{{ song.artist }} · {{ song.album }}</div>
                </div>
                <span class="search-duration">{{ music.formatDuration(song.duration) }}</span>
              </div>
              <div v-if="music.searchResults.length === 0 && searchInput" class="search-empty">
                {{ music.isSearching ? '搜索中...' : '未找到结果' }}
              </div>
            </div>
            <button class="close-search" @click="showSearch = false">关闭</button>
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
  min-height: 0;
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
  color: #fff;
}

.login-status.logged-in.qq {
  background: linear-gradient(135deg, #10b981, #059669);
}

.login-status.logged-in.netease {
  background: linear-gradient(135deg, #e60026, #c20020);
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
  min-height: 0;
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

.platform-select {
  display: flex;
  gap: 16px;
  margin-top: 16px;
}

.platform-btn {
  flex: 1;
  padding: 16px 24px;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  color: #fff;
}

.platform-btn.qq {
  background: linear-gradient(135deg, #10b981, #059669);
}

.platform-btn.qq:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.4);
}

.platform-btn.netease {
  background: linear-gradient(135deg, #e60026, #c20020);
}

.platform-btn.netease:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(230, 0, 38, 0.4);
}

.platform-tag {
  display: inline-block;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  margin-left: 6px;
  vertical-align: middle;
  color: #fff;
}

.platform-tag.qq {
  background: #10b981;
}

.platform-tag.netease {
  background: #e60026;
}

.platform-selector {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  padding: 8px 12px;
  color: var(--color-text-main);
  font-size: 14px;
  cursor: pointer;
}

.platform-selector:focus {
  outline: none;
  border-color: var(--color-primary);
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
  flex-shrink: 0;
}

.song-details {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.song-info {
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

/* Progress Bar */
.progress-container {
  display: flex;
  align-items: center;
  gap: 8px;
}

.time-current,
.time-total {
  font-size: 11px;
  color: var(--color-text-muted);
  min-width: 32px;
  text-align: center;
}

.progress-slider {
  flex: 1;
}

/* Controls wrapper */
.controls-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}

/* Loading spinner */
.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
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

/* Volume Control */
.volume-control {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 20px;
  min-width: 140px;
}

.volume-icon {
  color: var(--color-text-muted);
  flex-shrink: 0;
}

.volume-slider {
  flex: 1;
}

.volume-text {
  font-size: 11px;
  color: var(--color-text-muted);
  min-width: 32px;
  text-align: right;
  flex-shrink: 0;
}

/* Customize Slider component */
:deep(.slider-connect) {
  background: var(--color-primary, #6366f1);
}

:deep(.slider-tooltip) {
  display: none;
}

:deep(.slider-base) {
  background: rgba(255, 255, 255, 0.1);
  height: 4px;
  border-radius: 2px;
}

:deep(.slider-origin) {
  background: transparent;
}

:deep(.slider-handle) {
  width: 12px;
  height: 12px;
  background: #fff;
  border: none;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  top: 50%;
  transform: translateY(-50%);
}

:deep(.slider-handle:hover) {
  transform: translateY(-50%) scale(1.2);
}

:deep(.slider-horizontal) {
  height: 4px;
}

:deep(.slider-horizontal .slider-handle) {
  right: -6px;
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
