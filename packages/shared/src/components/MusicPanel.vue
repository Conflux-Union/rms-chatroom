<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useMusicStore, type Song, type LoopMode } from '../stores/music'
import { useVoiceStore } from '../stores/voice'
import { formatDuration } from '../utils/datetime'
import qqMusicIconUrl from '../assets/qq_music_icon.png'
import { t } from '../i18n'
import { ZmSlider, ZmModal, ZmButton, ZmSpace, ZmInput } from './ui'
import { Music, SkipBack, Pause, Play, SkipForward, Plus, Trash2, X, Search, Loader2, Volume2, Captions, ListMusic, Repeat, Repeat1, Shuffle } from 'lucide-vue-next'

const music = useMusicStore()
const voice = useVoiceStore()

const searchInput = ref('')
const showSearch = ref(false)
const showLoginSelect = ref(false)
const isProcessingPlayback = ref(false)
// Search dialog mode: songs, playlist search, or the recommendation tab.
const searchTab = ref<'songs' | 'playlists' | 'recommend'>('songs')

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
      music.seek(currentRoomName.value, newPosition)
    }
  }
})

// Volume control handler - use store's setVolume
function handleVolumeChange(value: number) {
  music.setVolume(value)
}

const LOOP_MODE_ICONS: Record<LoopMode, typeof ListMusic> = {
  sequence: ListMusic,
  list: Repeat,
  one: Repeat1,
  random: Shuffle,
}

const loopIcon = computed(() => LOOP_MODE_ICONS[music.loopMode])

// Labels go through t() inside the computed so a locale switch refreshes them.
const loopTitle = computed(() => {
  const mode = music.loopMode
  const label = mode === 'sequence' ? t('music.loopSequence')
    : mode === 'list' ? t('music.loopList')
    : mode === 'one' ? t('music.loopOne')
    : t('music.loopRandom')
  return t('music.loopModeTitle', { mode: label })
})

function handleCycleLoopMode() {
  if (currentRoomName.value) {
    music.cycleLoopMode(currentRoomName.value)
  }
}

onMounted(async () => {
  await music.checkAllLoginStatus()
  // WebSocket connection is now handled by the store automatically
})

onUnmounted(() => {
  // WebSocket disconnection is now handled by the store automatically
})

async function startLogin(platform: 'qq' | 'netease' = 'qq', loginType: 'qq' | 'wx' = 'qq') {
  showLoginSelect.value = false
  await music.getQRCode(platform, loginType)
  // No longer need to poll - WebSocket will push login status updates
}

const lastSearchedQuery = ref('')

function handleSearch() {
  const query = searchInput.value.trim()
  if (!query) return
  lastSearchedQuery.value = query
  if (searchTab.value === 'playlists') {
    music.searchPlaylists(query)
  } else {
    music.search(query)
  }
}

// The recommendation tab has no search box; its daily list loads on entry
// and whenever the platform filter changes while it is open.
watch([searchTab, () => music.searchPlatform], ([tab]) => {
  if (tab === 'recommend' && !music.isFetchingRecommend) {
    music.fetchRecommend()
  }
})

function formatPlayCount(count: number): string {
  if (!count) return ''
  return new Intl.NumberFormat(undefined, { notation: 'compact' }).format(count)
}

function platformName(platform: string): string {
  return platform === 'qq' ? t('music.qqMusic') : t('music.neteaseCloudMusic')
}

function closeSearchDialog() {
  showSearch.value = false
  searchInput.value = ''
  lastSearchedQuery.value = ''
  music.searchResults = []
  music.playlistResults = []
  music.playlistPreview = null
}

async function handleAddToQueue(song: Song) {
  if (!currentRoomName.value) return
  await music.addToQueue(currentRoomName.value, song)
  closeSearchDialog()
}

async function handleOpenPlaylist(platform: 'qq' | 'netease', id: string) {
  await music.loadPlaylistPreview(platform, id)
}

async function handleAddPlaylistToQueue() {
  const preview = music.playlistPreview
  if (!preview || !currentRoomName.value) return
  await music.addToQueueFromSource(currentRoomName.value, {
    platform: preview.playlist.platform,
    kind: 'playlist',
    id: preview.playlist.id,
  })
  closeSearchDialog()
}

async function handleAddDailyToQueue() {
  if (!currentRoomName.value) return
  await music.addDailyToQueue(currentRoomName.value)
  closeSearchDialog()
}

async function handleStartRadio(platform: 'qq' | 'netease') {
  if (!currentRoomName.value) return
  const ok = await music.startRadio(currentRoomName.value, platform)
  if (ok) closeSearchDialog()
}

async function handleStopRadio() {
  if (!currentRoomName.value) return
  await music.stopRadio(currentRoomName.value)
}

const searchDialogTitle = computed(() => {
  if (searchTab.value === 'playlists') {
    return music.playlistPreview ? music.playlistPreview.playlist.name : t('music.searchPlaylists')
  }
  return searchTab.value === 'recommend' ? t('music.tabRecommend') : t('music.searchSongs')
})

async function handlePlayPause() {
  if (!currentRoomName.value && music.playbackState !== 'paused') return

  // Prevent rapid clicks
  if (isProcessingPlayback.value) {
    return
  }

  isProcessingPlayback.value = true

  try {
    if (music.isPlaying) {
      await music.pause(currentRoomName.value)
    } else if (music.playbackState === 'paused') {
      // Resume from paused state
      await music.resume(currentRoomName.value)
    } else if (currentRoomName.value) {
      // Start new playback
      await music.play(currentRoomName.value)
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

async function handleSkipNext() {
  if (currentRoomName.value) {
    await music.skipNext(currentRoomName.value)
  }
}

async function handleSkipPrevious() {
  if (currentRoomName.value) {
    await music.skipPrevious(currentRoomName.value)
  }
}
</script>

<template>
  <div class="music-panel">
    <div class="music-header">
      <Music class="header-icon" :size="20" />
      <span class="header-title">{{ t('music.player') }}</span>
      <div class="login-actions">
        <span
          v-if="music.platformLoginStatus.qq.logged_in"
          class="login-status logged-in qq"
          @click="music.logout('qq')"
          :title="t('music.qqLoggedInQuit')"
        >
          <span class="platform-dot"></span>QQ
        </span>
        <span
          v-if="music.platformLoginStatus.netease.logged_in"
          class="login-status logged-in netease"
          @click="music.logout('netease')"
          :title="t('music.neteaseLoggedInQuit')"
        >
          <span class="platform-dot"></span>{{ t('music.netease') }}
        </span>
        <span
          v-if="!music.platformLoginStatus.qq.logged_in || !music.platformLoginStatus.netease.logged_in"
          class="login-status login-btn"
          @click="showLoginSelect = true"
        >
          {{ t('music.login') }}
        </span>
      </div>
    </div>

    <div class="music-content">
      <!-- Login Platform Select Dialog -->
      <ZmModal
        v-model:show="showLoginSelect"
        :title="t('music.selectLoginPlatform')"
        style="width: 360px"
      >
        <ZmSpace vertical size="large">
          <ZmButton
            v-if="!music.platformLoginStatus.qq.logged_in"
            block
            size="large"
            type="success"
            @click="startLogin('qq', 'qq')"
          >
            {{ t('music.qqLoginQQ') }}
          </ZmButton>
          <ZmButton
            v-if="!music.platformLoginStatus.qq.logged_in"
            block
            size="large"
            type="success"
            @click="startLogin('qq', 'wx')"
          >
            {{ t('music.qqLoginWeChat') }}
          </ZmButton>
          <ZmButton
            v-if="!music.platformLoginStatus.netease.logged_in"
            block
            size="large"
            type="error"
            @click="startLogin('netease')"
          >
            {{ t('music.neteaseCloudMusic') }}
          </ZmButton>
        </ZmSpace>
      </ZmModal>

      <!-- QR Code Login Dialog -->
      <ZmModal
        :show="!!music.qrCodeUrl"
        :title="t('music.scanToLogin', { platform: music.loginPlatform === 'qq' ? t('music.qqMusic') : t('music.neteaseCloudMusic') })"
        style="width: 320px"
        @update:show="(v: boolean) => { if (!v) music.qrCodeUrl = null }"
      >
        <div class="qr-login-content">
          <img :src="music.qrCodeUrl || ''" alt="QR Code" class="qr-code" />
          <p class="login-hint">
            {{ music.loginStatus === 'waiting' ? t('music.qrWaiting') :
               music.loginStatus === 'scanned' ? t('music.qrScanned') :
               music.loginStatus === 'expired' ? t('music.qrExpired') :
               music.loginStatus === 'refused' ? t('music.qrRefused') :
               music.loginStatus === 'error' ? t('music.qrError') :
               t('common.loading') }}
          </p>
        </div>
        <template #footer>
          <ZmSpace justify="center">
            <ZmButton v-if="music.loginStatus === 'expired'" type="primary" @click="startLogin(music.loginPlatform, music.loginType)">
              {{ t('music.refreshQr') }}
            </ZmButton>
            <ZmButton @click="music.qrCodeUrl = null">{{ t('common.close') }}</ZmButton>
          </ZmSpace>
        </template>
      </ZmModal>

      <!-- Now Playing -->
      <div v-if="music.currentSong" class="now-playing">
        <div class="now-playing-top">
          <img :src="music.currentSong.cover" alt="Cover" class="album-cover" />
          <div class="song-info">
            <div class="song-name">{{ music.currentSong.name }}</div>
            <div class="song-artist">{{ music.currentSong.artist }}</div>
          </div>
          <div class="playback-controls">
            <button
              class="control-btn loop-btn-toggle"
              :class="{ active: music.loopMode !== 'sequence' }"
              @click="handleCycleLoopMode"
              :title="loopTitle"
            ><component :is="loopIcon" :size="18" /></button>
            <button
              class="control-btn lyrics-btn-toggle"
              :class="{ active: music.lyricsWindowVisible }"
              @click="music.setLyricsWindowVisible(!music.lyricsWindowVisible)"
              :title="t('music.lyrics')"
            ><Captions :size="18" /></button>
            <button class="control-btn" @click="handleSkipPrevious" :title="t('music.previous')"><SkipBack :size="18" /></button>
            <button
              class="control-btn play-btn"
              @click="handlePlayPause"
              :disabled="!voice.isConnected && music.playbackState !== 'paused' || isProcessingPlayback"
              :title="voice.isConnected || music.playbackState === 'paused' ? '' : t('music.joinVoiceFirst')"
            >
              <Loader2 v-if="music.playbackState === 'loading' || isProcessingPlayback" :size="22" class="spin" />
              <Pause v-else-if="music.isPlaying" :size="22" />
              <Play v-else :size="22" />
            </button>
            <button class="control-btn" @click="handleSkipNext" :title="t('music.next')"><SkipForward :size="18" /></button>
          </div>
        </div>
        <!-- Progress Bar - Full Width -->
        <div class="progress-container">
          <span class="time-current">{{ formatDuration(music.positionMs) }}</span>
          <ZmSlider
            v-model:value="progressValue"
            :min="0"
            :max="100"
            :tooltip="false"
            :step="0.1"
          />
          <span class="time-total">{{ formatDuration(music.durationMs) }}</span>
        </div>
        <!-- Volume Control - Full Width -->
        <div class="volume-control">
          <Volume2 :size="16" class="volume-icon" />
          <ZmSlider
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
        <p>{{ t('music.nothingPlaying') }}</p>
        <button class="add-song-btn " @click="showSearch = true">
          {{ t('music.addSong') }}
        </button>
      </div>

      <!-- Queue -->
      <div class="queue-section">
        <div class="queue-header">
          <span>{{ t('music.queueCount', { count: music.queue.length }) }}</span>
          <div class="queue-actions">
            <button class="icon-btn" @click="showSearch = true" :title="t('music.addSong')"><Plus :size="16" /></button>
            <button 
              v-if="music.queue.length > 0" 
              class="icon-btn" 
              @click="handleClearQueue" 
              :title="t('music.clearQueue')"
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
            {{ t('music.queueEmpty') }}
          </div>
        </div>
      </div>

      <!-- Search Dialog -->
      <ZmModal
        v-model:show="showSearch"
        :title="searchDialogTitle"
        style="width: 500px; max-height: 80vh"
      >
        <ZmSpace vertical>
          <div class="search-tabs">
            <button class="tab-chip" :class="{ active: searchTab === 'songs' }" @click="searchTab = 'songs'">
              {{ t('music.tabSongs') }}
            </button>
            <button class="tab-chip" :class="{ active: searchTab === 'playlists' }" @click="searchTab = 'playlists'">
              {{ t('music.tabPlaylists') }}
            </button>
            <button class="tab-chip" :class="{ active: searchTab === 'recommend' }" @click="searchTab = 'recommend'">
              {{ t('music.tabRecommend') }}
            </button>
          </div>

          <ZmSpace>
            <div class="platform-picker">
              <button
                class="platform-chip"
                :class="{ active: music.searchPlatform === 'all' }"
                @click="music.searchPlatform = 'all'"
                :title="t('music.all')"
              >{{ t('music.all') }}</button>
              <button
                class="platform-chip"
                :class="{ active: music.searchPlatform === 'qq' }"
                @click="music.searchPlatform = 'qq'"
                :title="t('music.qqMusic')"
              >
                <!-- Official QQ Music mark, cropped from the zh.wikipedia logo -->
                <img :src="qqMusicIconUrl" alt="" class="platform-mark qq-mark" />
              </button>
              <button
                class="platform-chip"
                :class="{ active: music.searchPlatform === 'netease' }"
                @click="music.searchPlatform = 'netease'"
                :title="t('music.neteaseCloudMusic')"
              >
                <svg class="platform-mark netease" viewBox="0 0 24 24" aria-hidden="true">
                  <!-- Official NetEase Cloud Music mark (simple-icons path data) -->
                  <path d="M13.046 9.388a3.919 3.919 0 0 0-.66.19c-.809.312-1.447.991-1.666 1.775a2.269 2.269 0 0 0-.074.81c.048.546.333 1.05.764 1.35a1.483 1.483 0 0 0 2.01-.286c.406-.531.355-1.183.24-1.636-.098-.387-.22-.816-.345-1.249a64.76 64.76 0 0 1-.269-.954zm-.82 10.07c-3.984 0-7.224-3.24-7.224-7.223 0-.98.226-3.02 1.884-4.822A7.188 7.188 0 0 1 9.502 5.6a.792.792 0 1 1 .587 1.472 5.619 5.619 0 0 0-2.795 2.462 5.538 5.538 0 0 0-.707 2.7 5.645 5.645 0 0 0 5.638 5.638c1.844 0 3.627-.953 4.542-2.428 1.042-1.68.772-3.931-.627-5.238a3.299 3.299 0 0 0-1.437-.777c.172.589.334 1.18.494 1.772.284 1.12.1 2.181-.519 2.989-.39.51-.956.888-1.592 1.064a3.038 3.038 0 0 1-2.58-.44 3.45 3.45 0 0 1-1.44-2.514c-.04-.467.002-.93.128-1.376.35-1.256 1.356-2.339 2.622-2.826a5.5 5.5 0 0 1 .823-.246l-.134-.505c-.37-1.371.25-2.579 1.547-3.007.329-.109.68-.145 1.025-.105.792.09 1.476.592 1.709 1.023.258.507-.096 1.153-.706 1.153a.788.788 0 0 1-.54-.213c-.088-.08-.163-.174-.259-.247a.825.825 0 0 0-.632-.166.807.807 0 0 0-.634.551c-.056.191-.031.406.02.595.07.256.159.597.217.82 1.11.098 2.162.54 2.97 1.296 1.974 1.844 2.35 4.886.892 7.233-1.197 1.93-3.509 3.177-5.889 3.177zM0 12c0 6.627 5.373 12 12 12s12-5.373 12-12S18.627 0 12 0 0 5.373 0 12Z" />
                </svg>
              </button>
            </div>
            <template v-if="searchTab !== 'recommend'">
              <ZmInput
                v-model:value="searchInput"
                :placeholder="t('music.searchPlaceholder')"
                @keyup.enter="handleSearch"
                style="flex: 1"
              />
              <ZmButton
                type="primary"
                @click="handleSearch"
                :loading="searchTab === 'songs' ? music.isSearching : music.isSearchingPlaylists"
                style="min-width: 40px"
              >
                <Search v-if="searchTab === 'songs' ? !music.isSearching : !music.isSearchingPlaylists" :size="18" />
              </ZmButton>
            </template>
          </ZmSpace>

          <!-- Songs results -->
          <div v-if="searchTab === 'songs'" class="search-results">
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
                    {{ song.platform === 'qq' ? 'QQ' : t('music.netease') }}
                  </span>
                </div>
                <div class="search-song-artist">{{ song.artist }} · {{ song.album }}</div>
              </div>
              <span class="search-duration">{{ music.formatDuration(song.duration) }}</span>
            </div>
            <div
              v-if="lastSearchedQuery && searchInput.trim() === lastSearchedQuery && music.searchResults.length === 0"
              class="search-empty"
            >
              {{ music.isSearching ? t('music.searching') : t('music.noResults') }}
            </div>
          </div>

          <!-- Playlist results / preview -->
          <div v-else-if="searchTab === 'playlists'" class="search-results">
            <template v-if="!music.playlistPreview">
              <div
                v-for="p in music.playlistResults"
                :key="`${p.platform}-${p.id}`"
                class="search-item"
                @click="handleOpenPlaylist(p.platform, p.id)"
              >
                <img :src="p.cover" alt="Cover" class="search-cover" />
                <div class="search-info">
                  <div class="search-song-name">
                    {{ p.name }}
                    <span class="platform-tag" :class="p.platform">
                      {{ p.platform === 'qq' ? 'QQ' : t('music.netease') }}
                    </span>
                  </div>
                  <div class="search-song-artist">
                    {{ t('music.songCount', { count: p.track_count }) }}
                    <template v-if="p.creator"> · {{ p.creator }}</template>
                    <template v-if="formatPlayCount(p.play_count)"> · {{ formatPlayCount(p.play_count) }}</template>
                  </div>
                </div>
              </div>
              <div v-if="lastSearchedQuery && music.playlistResults.length === 0" class="search-empty">
                {{ music.isSearchingPlaylists ? t('music.searching') : t('music.noPlaylists') }}
              </div>
            </template>
            <template v-else>
              <div class="preview-header">
                <ZmButton @click="music.playlistPreview = null">{{ t('music.back') }}</ZmButton>
                <div class="preview-meta">
                  {{ t('music.songCount', { count: music.playlistPreview.total }) }}
                  <span v-if="music.playlistPreview.songs.length < music.playlistPreview.total" class="preview-hint">
                    · {{ t('music.playlistPreviewOnly', { count: music.playlistPreview.songs.length }) }}
                  </span>
                </div>
                <ZmButton
                  type="primary"
                  :disabled="music.playlistPreview.songs.length === 0"
                  @click="handleAddPlaylistToQueue"
                >{{ t('music.addToQueueAll') }}</ZmButton>
              </div>
              <div
                v-for="song in music.playlistPreview.songs"
                :key="`${song.platform}-${song.mid}`"
                class="search-item"
                @click="handleAddToQueue(song)"
              >
                <img :src="song.cover" alt="Cover" class="search-cover" />
                <div class="search-info">
                  <div class="search-song-name">
                    {{ song.name }}
                    <span class="platform-tag" :class="song.platform">
                      {{ song.platform === 'qq' ? 'QQ' : t('music.netease') }}
                    </span>
                  </div>
                  <div class="search-song-artist">{{ song.artist }} · {{ song.album }}</div>
                </div>
                <span class="search-duration">{{ music.formatDuration(song.duration) }}</span>
              </div>
              <div v-if="music.isLoadingPreview" class="search-empty">{{ t('music.loading') }}</div>
              <div v-else-if="music.playlistPreview.songs.length === 0" class="search-empty">
                {{ t('music.noResults') }}
              </div>
            </template>
          </div>

          <!-- Recommendations: radio toggle + daily mix -->
          <div v-else class="search-results">
            <div class="radio-card">
              <span class="radio-title">{{ t('music.radio') }}</span>
              <template v-if="music.radioPlatform">
                <span class="radio-status">{{ t('music.radioRunning', { platform: platformName(music.radioPlatform) }) }}</span>
                <ZmButton @click="handleStopRadio">{{ t('music.stopRadio') }}</ZmButton>
              </template>
              <template v-else>
                <ZmButton @click="handleStartRadio('qq')">{{ t('music.qqMusic') }}</ZmButton>
                <ZmButton @click="handleStartRadio('netease')">{{ t('music.neteaseCloudMusic') }}</ZmButton>
              </template>
            </div>
            <div class="recommend-header">
              <span class="recommend-title">{{ t('music.dailyRecommend') }}</span>
              <ZmButton
                type="primary"
                :disabled="music.recommendSongs.length === 0"
                @click="handleAddDailyToQueue"
              >{{ t('music.addToQueueAll') }}</ZmButton>
            </div>
            <div
              v-for="song in music.recommendSongs"
              :key="`${song.platform}-${song.mid}`"
              class="search-item"
              @click="handleAddToQueue(song)"
            >
              <img :src="song.cover" alt="Cover" class="search-cover" />
              <div class="search-info">
                <div class="search-song-name">
                  {{ song.name }}
                  <span class="platform-tag" :class="song.platform">
                    {{ song.platform === 'qq' ? 'QQ' : t('music.netease') }}
                  </span>
                </div>
                <div class="search-song-artist">{{ song.artist }} · {{ song.album }}</div>
              </div>
              <span class="search-duration">{{ music.formatDuration(song.duration) }}</span>
            </div>
            <div v-if="music.isFetchingRecommend" class="search-empty">{{ t('music.loading') }}</div>
            <div v-else-if="music.recommendSongs.length === 0" class="search-empty">
              {{ t('music.recommendEmpty') }}
            </div>
          </div>
        </ZmSpace>

        <template #footer>
          <ZmSpace justify="end">
            <ZmButton @click="showSearch = false">{{ t('common.close') }}</ZmButton>
          </ZmSpace>
        </template>
      </ZmModal>
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
  border-bottom: 1px dashed var(--color-border);
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

.login-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}

.login-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-muted);
  transition: all 0.2s;
}

.login-status:hover {
  background: var(--surface-glass-hover);
  color: var(--color-text-main);
}

.login-status .platform-dot {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
}

.login-status.logged-in.qq .platform-dot {
  background: var(--brand-qq);
}

.login-status.logged-in.netease .platform-dot {
  background: var(--brand-netease);
}

.login-status.login-btn {
  color: var(--color-primary);
}

.login-status.login-btn:hover {
  color: var(--color-primary-hover);
}

.music-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  min-height: 0;
  overflow: hidden;
}

/* QR Login Content */
.qr-login-content {
  text-align: center;
}

.qr-code {
  width: 200px;
  height: 200px;
  border-radius: var(--radius-md);
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
  border-radius: var(--radius-md);
  margin-left: 6px;
  vertical-align: middle;
  color: var(--color-text-light);
}

.platform-tag.qq {
  background: var(--brand-qq);
}

.platform-tag.netease {
  background: var(--brand-netease);
}

/* Now Playing */
.now-playing {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  border-radius: var(--radius-md);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
  border: 1px solid var(--color-border);
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
  border-radius: var(--radius-md);
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background: var(--surface-glass-darker);
  color: var(--color-text-main);
  border: none;
  font-size: 18px;
  cursor: pointer;
  transition: all 0.2s;
}

.control-btn:hover {
  background: var(--surface-glass-hover);
  transform: scale(1.1);
}

.control-btn.lyrics-btn-toggle.active {
  color: var(--zhimo-seal);
  background: var(--zhimo-bg-hover);
}

.control-btn.loop-btn-toggle.active {
  color: var(--zhimo-seal);
  background: var(--zhimo-bg-hover);
}

.play-btn {
  width: 48px;
  height: 48px;
  font-size: 22px;
  background: var(--color-gradient-primary);
  color: var(--color-text-light);
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
  color: var(--color-text-light);
  border: none;
  padding: 12px 24px;
  border-radius: var(--radius-md);
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
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  overflow: hidden;
}

.queue-header {
  padding: 12px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--color-border);
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
  color: var(--color-text-muted);
  font-size: 16px;
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-md);
  transition: background 0.2s, color 0.2s;
}

.icon-btn:hover {
  background: var(--surface-glass-hover);
  color: var(--color-text-main);
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
  border-radius: var(--radius-md);
  transition: background 0.2s;
}

.queue-item:hover {
  background: var(--surface-glass-hover);
}

.queue-item.current {
  background: rgba(var(--color-accent-rgb), 0.12);
}

.queue-cover {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
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

/* Search dialog platform picker: bare official marks, no boxes */
.platform-picker {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.search-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
}

.tab-chip {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border: none;
  background: none;
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-chip:hover {
  background: var(--surface-glass-hover);
  color: var(--color-text-main);
}

.tab-chip.active {
  background: var(--surface-glass-darker);
  color: var(--color-text-main);
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
}

.preview-meta {
  flex: 1;
  min-width: 0;
  color: var(--color-text-light);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.radio-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-glass);
}

.radio-title {
  font-weight: 600;
  color: var(--color-text-main);
  margin-right: auto;
}

.radio-status {
  color: var(--color-text-light);
  font-size: 12px;
}

.recommend-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px 0;
}

.recommend-title {
  font-weight: 600;
  color: var(--color-text-main);
  margin-right: auto;
}

.platform-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 30px;
  height: 30px;
  padding: 0 7px;
  border: none;
  background: none;
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.2s, background 0.2s, color 0.2s;
}

.platform-chip:hover {
  background: var(--surface-glass-hover);
  opacity: 0.85;
}

.platform-chip.active {
  background: var(--surface-glass-darker);
  color: var(--color-text-main);
  opacity: 1;
}

.platform-mark {
  width: 18px;
  height: 18px;
}

.platform-mark.qq-mark {
  width: 19px;
  height: 19px;
  object-fit: contain;
}

.platform-mark.netease path {
  fill: var(--brand-netease);
}

/* Search Results */
.search-results {
  max-height: 400px;
  overflow-y: auto;
}

.search-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.2s;
}

.search-item:hover {
  background: var(--surface-glass-hover);
}

.search-cover {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
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
