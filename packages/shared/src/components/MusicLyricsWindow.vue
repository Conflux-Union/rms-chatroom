<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMusicStore } from '../stores/music'
import { activeLyricIndex } from '../utils/lyrics'
import { t } from '../i18n'
import { X, Languages } from 'lucide-vue-next'

const music = useMusicStore()

// Size mirrors the CSS constraints below; used for viewport clamping.
const WINDOW_W = 320
const WINDOW_H = 420
const POS_KEY = 'musicLyricsWindowPos'
const TRANS_KEY = 'musicLyricsTranslation'

const pos = ref({ x: 0, y: 0 })
const dragging = ref(false)
const showTranslation = ref(localStorage.getItem(TRANS_KEY) !== '0')
const scrollEl = ref<HTMLElement | null>(null)
let lastUserScroll = 0

function clamp(x: number, y: number): { x: number; y: number } {
  return {
    x: Math.min(Math.max(0, x), Math.max(0, window.innerWidth - WINDOW_W)),
    y: Math.min(Math.max(0, y), Math.max(0, window.innerHeight - WINDOW_H)),
  }
}

function loadPos() {
  try {
    const saved = JSON.parse(localStorage.getItem(POS_KEY) || '')
    if (typeof saved?.x === 'number' && typeof saved?.y === 'number') {
      pos.value = clamp(saved.x, saved.y)
      return
    }
  } catch {
    // Missing or corrupt entry — fall through to the default corner.
  }
  pos.value = clamp(window.innerWidth - WINDOW_W - 24, window.innerHeight - WINDOW_H - 24)
}

function onWindowResize() {
  pos.value = clamp(pos.value.x, pos.value.y)
}

// --- Dragging (pointer events cover mouse + touch; header is the handle) ---

let dragOrigin = { px: 0, py: 0, x: 0, y: 0 }

function onDragStart(e: PointerEvent) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  dragging.value = true
  dragOrigin = { px: e.clientX, py: e.clientY, x: pos.value.x, y: pos.value.y }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onDragMove(e: PointerEvent) {
  if (!dragging.value) return
  pos.value = clamp(
    dragOrigin.x + e.clientX - dragOrigin.px,
    dragOrigin.y + e.clientY - dragOrigin.py,
  )
}

function onDragEnd() {
  if (!dragging.value) return
  dragging.value = false
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    // Private mode etc. — position just won't persist.
  }
}

// --- Playback sync ---

const activeIndex = computed(() => activeLyricIndex(music.lyrics, music.positionMs))
const hasTranslation = computed(() => music.lyrics.some((line) => line.translation))

function toggleTranslation() {
  showTranslation.value = !showTranslation.value
  localStorage.setItem(TRANS_KEY, showTranslation.value ? '1' : '0')
}

function scrollToActive(behavior: ScrollBehavior) {
  const container = scrollEl.value
  if (!container) return
  const lines = container.querySelectorAll<HTMLElement>('.lyric-line')
  const el = activeIndex.value >= 0 ? lines[activeIndex.value] : null
  if (!el) {
    container.scrollTo({ top: 0, behavior })
    return
  }
  container.scrollTo({
    top: el.offsetTop - container.clientHeight / 2 + el.offsetHeight / 2,
    behavior,
  })
}

// Manual scrolling pauses auto-follow briefly so the user can look around.
function markUserScroll() {
  lastUserScroll = Date.now()
}

watch(activeIndex, () => {
  if (Date.now() - lastUserScroll < 4000) return
  scrollToActive('smooth')
})

watch(() => music.lyrics, () => {
  lastUserScroll = 0
  nextTick(() => scrollToActive('auto'))
})

// While hidden the container has no size, so re-align once it opens again.
watch(() => music.lyricsWindowVisible, (visible) => {
  if (visible) nextTick(() => scrollToActive('auto'))
})

onMounted(() => {
  loadPos()
  window.addEventListener('resize', onWindowResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="music.lyricsWindowVisible"
      class="lyrics-window"
      :style="{ left: `${pos.x}px`, top: `${pos.y}px` }"
    >
      <div
        class="lyrics-header"
        :class="{ dragging: dragging }"
        :title="t('music.lyricsDragHint')"
        @pointerdown="onDragStart"
        @pointermove="onDragMove"
        @pointerup="onDragEnd"
        @pointercancel="onDragEnd"
      >
        <span class="lyrics-title">{{ music.currentSong?.name || t('music.lyrics') }}</span>
        <button
          v-if="hasTranslation"
          class="lyrics-btn"
          :class="{ on: showTranslation }"
          :title="t('music.lyricsTranslation')"
          @pointerdown.stop
          @click="toggleTranslation"
        >
          <Languages :size="14" />
        </button>
        <button
          class="lyrics-btn"
          :title="t('common.close')"
          @pointerdown.stop
          @click="music.setLyricsWindowVisible(false)"
        >
          <X :size="14" />
        </button>
      </div>
      <div ref="scrollEl" class="lyrics-body" @wheel="markUserScroll" @touchmove="markUserScroll">
        <div v-if="music.lyricsStatus === 'loading'" class="lyrics-message">
          {{ t('common.loading') }}
        </div>
        <div v-else-if="music.lyricsStatus === 'error'" class="lyrics-message">
          {{ t('music.lyricsLoadFailed') }}
        </div>
        <div v-else-if="music.lyricsStatus !== 'ready'" class="lyrics-message">
          {{ music.currentSong ? t('music.lyricsEmpty') : t('music.lyricsNotPlaying') }}
        </div>
        <template v-else>
          <div
            v-for="(line, i) in music.lyrics"
            :key="i"
            class="lyric-line"
            :class="{ active: i === activeIndex }"
          >
            <div class="lyric-text">{{ line.text }}</div>
            <div v-if="line.translation && showTranslation" class="lyric-trans">
              {{ line.translation }}
            </div>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lyrics-window {
  position: fixed;
  z-index: 900; /* above app chrome, below zhimo modals (1000+) */
  width: min(320px, calc(100vw - 16px));
  height: min(420px, 60vh);
  display: flex;
  flex-direction: column;
  background: var(--surface-glass);
  backdrop-filter: var(--zhimo-surface-blur);
  -webkit-backdrop-filter: var(--zhimo-surface-blur);
  border: 1px solid var(--zhimo-border-strong);
  border-radius: var(--radius-md);
  box-shadow: var(--zhimo-shadow-lg);
  overflow: hidden;
  user-select: none;
}

.lyrics-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px 6px 12px;
  border-bottom: 1px solid var(--zhimo-border);
  cursor: grab;
  touch-action: none;
}

.lyrics-header.dragging {
  cursor: grabbing;
}

.lyrics-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lyrics-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  flex-shrink: 0;
}

.lyrics-btn:hover {
  background: var(--zhimo-bg-hover);
  color: var(--color-text-main);
}

.lyrics-btn.on {
  color: var(--zhimo-seal);
}

.lyrics-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 16px;
  user-select: text;
  -webkit-mask-image: linear-gradient(
    to bottom,
    transparent,
    #000 24px,
    #000 calc(100% - 24px),
    transparent
  );
  mask-image: linear-gradient(
    to bottom,
    transparent,
    #000 24px,
    #000 calc(100% - 24px),
    transparent
  );
}

.lyric-line {
  padding: 7px 4px;
  text-align: center;
}

.lyric-text {
  font-size: 14px;
  line-height: 1.5;
  color: var(--color-text-muted);
  transition: color 0.3s ease, transform 0.3s ease;
}

.lyric-line.active .lyric-text {
  color: var(--zhimo-seal);
  font-weight: 600;
  transform: scale(1.04);
}

.lyric-trans {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--color-text-muted);
}

.lyric-line.active .lyric-trans {
  color: var(--zhimo-seal);
  opacity: 0.8;
}

.lyrics-message {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--color-text-muted);
}
</style>
