<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useMusicStore, type Song } from '../stores/music'
import { useVoiceStore } from '../stores/voice'
import { NSlider, NSelect, NModal, NButton, NSpace, NInput, NSpin } from 'naive-ui'
import { Music, Bot, SkipBack, Pause, Play, SkipForward, Plus, Trash2, X, Search, Loader2, Volume2 } from 'lucide-vue-next'

const music = useMusicStore()
const voice = useVoiceStore()

const searchInput = ref('')
const showSearch = ref(false)
const showLoginSelect = ref(false)
const loginPollingInterval = ref<number | null>(null)
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

// Volume control handler - use store's setVolume
function handleVolumeChange(value: number) {
  music.setVolume(value)
}

onMounted(async () => {
  await music.checkAllLoginStatus()
  // WebSocket connection is now handled by the store automatically
})

onUnmounted(() => {
  if (loginPollingInterval.value) {
    clearInterval(loginPollingInterval.value)
  }
  // WebSocket disconnection is now handled by the store automatically
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
      <!-- Login Platform Select Dialog (NModal) -->
      <NModal
        v-model:show="showLoginSelect"
        preset="card"
        title="选择登录平台"
        style="width: 360px"
        :segmented="{ content: true }"
      >
        <NSpace vertical size="large">
          <NButton
            v-if="!music.platformLoginStatus.qq.logged_in"
            block
            size="large"
            type="success"
            @click="startLogin('qq')"
          >
            QQ 音乐
          </NButton>
          <NButton
            v-if="!music.platformLoginStatus.netease.logged_in"
            block
            size="large"
            type="error"
            @click="startLogin('netease')"
          >
            网易云音乐
          </NButton>
        </NSpace>
      </NModal>

      <!-- QR Code Login Dialog (NModal) -->
      <NModal
        :show="!!music.qrCodeUrl"
        preset="card"
        :title="'扫码登录 ' + (music.loginPlatform === 'qq' ? 'QQ 音乐' : '网易云音乐')"
        style="width: 320px"
        :segmented="{ content: true, footer: 'soft' }"
        @update:show="(v) => { if (!v) music.qrCodeUrl = null }"
      >
        <div class="qr-login-content">
          <img :src="music.qrCodeUrl || ''" alt="QR Code" class="qr-code" />
          <p class="login-hint">
            {{ music.loginStatus === 'waiting' ? '等待扫码...' :
               music.loginStatus === 'scanned' ? '扫码成功！请在手机上确认...' :
               music.loginStatus === 'expired' ? '二维码已过期' :
               music.loginStatus === 'refused' ? '登录被拒绝' :
               '加载中...' }}
          </p>
        </div>
        <template #footer>
          <NSpace justify="center">
            <NButton v-if="music.loginStatus === 'expired'" type="primary" @click="startLogin(music.loginPlatform)">
              刷新二维码
            </NButton>
            <NButton @click="music.qrCodeUrl = null">关闭</NButton>
          </NSpace>
        </template>
      </NModal>

      <!-- Now Playing -->
      <div v-if="music.currentSong" class="now-playing">
        <div class="now-playing-top">
          <img :src="music.currentSong.cover" alt="Cover" class="album-cover" />
          <div class="song-info">
            <div class="song-name">{{ music.currentSong.name }}</div>
            <div class="song-artist">{{ music.currentSong.artist }}</div>
          </div>
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
        </div>
        <!-- Progress Bar - Full Width -->
        <div class="progress-container">
          <span class="time-current">{{ formatTime(music.positionMs) }}</span>
          <NSlider
            v-model:value="progressValue"
            :min="0"
            :max="100"
            :tooltip="false"
            :step="0.1"
          />
          <span class="time-total">{{ formatTime(music.durationMs) }}</span>
        </div>
        <!-- Volume Control - Full Width -->
        <div class="volume-control">
          <Volume2 :size="16" class="volume-icon" />
          <NSlider
            :value="music.volume * 100"
            @update:value="(v: number) => handleVolumeChange(v / 100)"
            :min="0"
            :max="100"
            :step="1"
            :tooltip="false"
          />
          <span class="volume-text">{{ Math.round(music.volume * 100) }}%</span>
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

      <!-- Search Dialog (NModal) -->
      <NModal
        v-model:show="showSearch"
        preset="card"
        title="搜索歌曲"
        style="width: 500px; max-height: 80vh"
        :segmented="{ content: true, footer: 'soft' }"
      >
        <template #header-extra>
          <NSelect
            v-model:value="music.searchPlatform"
            :options="[
              { label: 'All', value: 'all' },
              { label: 'QQ Music', value: 'qq' },
              { label: 'NetEase', value: 'netease' },
            ]"
            style="width: 100px"
            size="small"
          />
        </template>

        <NSpace vertical>
          <NSpace>
            <NInput
              v-model:value="searchInput"
              placeholder="搜索歌曲..."
              @keyup.enter="handleSearch"
              style="flex: 1"
            />
            <NButton type="primary" @click="handleSearch" :loading="music.isSearching">
              <template #icon><Search :size="18" /></template>
            </NButton>
          </NSpace>

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
        </NSpace>

        <template #footer>
          <NSpace justify="end">
            <NButton @click="showSearch = false">关闭</NButton>
          </NSpace>
        </template>
      </NModal>
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

/* QR Login Content (inside NModal) */
.qr-login-content {
  text-align: center;
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

/* Now Playing */
.now-playing {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

.now-playing-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.album-cover {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
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

/* Progress Bar - Full Width */
.progress-container {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.time-current,
.time-total {
  font-size: 11px;
  color: var(--color-text-muted);
  min-width: 36px;
  text-align: center;
  flex-shrink: 0;
}

.progress-slider {
  flex: 1;
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

/* Volume Control - Full Width */
.volume-control {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
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
  min-width: 36px;
  text-align: right;
  flex-shrink: 0;
}

/* Naive UI Slider customization */
.progress-container :deep(.n-slider),
.volume-control :deep(.n-slider) {
  flex: 1;
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

/* Search Results (inside NModal) */
.search-results {
  max-height: 400px;
  overflow-y: auto;
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
</style>
