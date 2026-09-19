<template>
  <!-- Teleport to <body> so position:fixed escapes #app's global
       `#app > *:not(.ink-bg) { position: relative }` rule, which would
       override .mask's position:fixed and pin the dialog to the document flow. -->
  <Teleport to="body">
    <div v-if="visible" class="mask">
    <div class="box">
      <div class="title">
        {{ forced ? t('app.updateRequired') : t('app.updateAvailable') }}
      </div>

      <div class="info">
        <div>{{ t('app.statusLabel', { value: stateText }) }}</div>
        <div v-if="versionText">{{ t('app.versionLabel', { value: versionText }) }}</div>
      </div>

      <div v-if="state === 'downloading'" class="progress">
        <div class="progress-row">
          <span>{{ total > 0 ? t('app.downloadingPercent', { percent: percent.toFixed(1) }) : t('app.downloading') }}</span>
          <span class="speed">{{ formatBytes(speed) }}/s</span>
        </div>
        <div class="bar" :class="{ indeterminate: total <= 0 }">
          <div class="bar-fill" :style="total > 0 ? { width: `${percent}%` } : undefined"></div>
        </div>
        <div class="sub">{{ formatBytes(transferred) }} / {{ total > 0 ? formatBytes(total) : t('app.unknownSize') }}</div>
      </div>

      <div v-if="state === 'error'" class="error">
        {{ t('app.updateError', { message }) }}
      </div>

      <div class="actions">
        <template v-if="forced">
          <button class="btn primary" :disabled="btnDisabled" @click="forceUpdateAction">
            {{ forceBtnText }}
          </button>
          <button class="btn danger" @click="quit">{{ t('app.quit') }}</button>
        </template>

        <template v-else>
          <button
            v-if="state === 'available'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="download"
          >
            {{ t('app.downloadUpdate') }}
          </button>

          <button
            v-if="state === 'downloaded'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="install"
          >
            {{ t('app.installAndRestart') }}
          </button>

          <button class="btn" @click="later">{{ t('app.later') }}</button>
          <button class="btn" @click="visible = false">{{ t('common.close') }}</button>
        </template>
      </div>
    </div>
  </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { isTauri } from '../index'
import { t } from '../i18n'

const visible = ref(false)
const state = ref<'idle' | 'checking' | 'available' | 'none' | 'downloading' | 'downloaded' | 'error'>('idle')
const forced = ref(false)
const percent = ref(0)
const transferred = ref(0)
const total = ref(0)
const speed = ref(0)
const message = ref('')
const versionText = ref('')

let updateObj: any = null

const stateText = computed(() => {
  const map: Record<string, string> = {
    idle: t('app.stateIdle'),
    checking: t('app.stateChecking'),
    available: t('app.stateAvailable'),
    none: t('app.stateUpToDate'),
    downloading: t('app.stateDownloading'),
    downloaded: t('app.stateDownloaded'),
    error: t('app.stateError'),
  }
  return map[state.value] || state.value
})

const btnDisabled = computed(() => state.value === 'checking' || state.value === 'downloading')

const forceBtnText = computed(() => {
  if (state.value === 'downloaded') return t('app.installAndRestart')
  if (state.value === 'downloading') return t('app.downloadingEllipsis')
  if (state.value === 'available') return t('app.updateStartDownload')
  return t('app.update')
})

function formatBytes(n: number) {
  if (!Number.isFinite(n) || n <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(1)} ${units[i]}`
}

function isForcedUpdate(body: string): boolean {
  const t = String(body || '').toLowerCase()
  const words = [
    'security', 'forced', 'force update', 'mandatory', 'must update',
    '强制更新', '必须更新', '安全更新',
  ].map(w => w.toLowerCase())
  return words.some(w => t.includes(w))
}

async function check() {
  if (!isTauri) return
  state.value = 'checking'
  try {
    const { check } = await import('@tauri-apps/plugin-updater')
    const update = await check()
    if (update) {
      updateObj = update
      versionText.value = update.version
      forced.value = isForcedUpdate(update.body || '')
      state.value = 'available'

      if (forced.value) {
        await download()
      }
    } else {
      state.value = 'none'
      visible.value = false
    }
  } catch (e: any) {
    state.value = 'error'
    message.value = String(e?.message || e)
    visible.value = true
  }
}

async function download() {
  if (!updateObj) return
  state.value = 'downloading'
  percent.value = 0
  transferred.value = 0
  total.value = 0
  speed.value = 0
  try {
    let contentLength = 0
    let chunkLength = 0
    let lastSampleTime = Date.now()
    let lastSampleBytes = 0
    await updateObj.downloadAndInstall((event: any) => {
      if (event.event === 'Started') {
        contentLength = event.data.contentLength || 0
        total.value = contentLength
        transferred.value = 0
        lastSampleTime = Date.now()
        lastSampleBytes = 0
      } else if (event.event === 'Progress') {
        chunkLength += event.data.chunkLength
        transferred.value = chunkLength
        if (contentLength > 0) {
          percent.value = (chunkLength / contentLength) * 100
        }
        const now = Date.now()
        const elapsed = now - lastSampleTime
        if (elapsed >= 500) {
          speed.value = ((chunkLength - lastSampleBytes) / elapsed) * 1000
          lastSampleTime = now
          lastSampleBytes = chunkLength
        }
      } else if (event.event === 'Finished') {
        state.value = 'downloaded'
        percent.value = 100
        speed.value = 0
      }
    })
    // downloadAndInstall may auto-install; if we reach here without error, mark as downloaded
    state.value = 'downloaded'
    speed.value = 0
  } catch (e: any) {
    state.value = 'error'
    message.value = String(e?.message || e)
    visible.value = true
  }
}

async function install() {
  if (!updateObj) return
  try {
    const { relaunch } = await import('@tauri-apps/plugin-process')
    await relaunch()
  } catch (e: any) {
    state.value = 'error'
    message.value = String(e?.message || e)
  }
}

async function quit() {
  if (!isTauri) return
  const { invoke } = await import('@tauri-apps/api/core')
  await invoke('quit_app')
}

async function forceUpdateAction() {
  if (state.value === 'downloaded') return install()
  if (state.value === 'available') return download()
}

function later() {
  visible.value = false
}

onMounted(() => {
  if (isTauri) {
    check()
  }
})
</script>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}
.box {
  width: min(520px, calc(100vw - 32px));
  background: #fff;
  border-radius: 14px;
  padding: 16px;
  border: 1px solid #eee;
}
.title {
  font-weight: 800;
  font-size: 16px;
  margin-bottom: 10px;
}
.info { font-size: 13px; color: #333; }
.progress { margin-top: 10px; font-size: 13px; }
.progress-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.speed { font-size: 12px; color: #666; }
.bar {
  position: relative;
  height: 8px;
  margin-top: 8px;
  border-radius: 4px;
  background: #eee;
  overflow: hidden;
}
.bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  width: 0;
  border-radius: 4px;
  background: #111;
  transition: width 0.2s ease;
}
.bar.indeterminate .bar-fill {
  width: 30%;
  animation: bar-slide 1.2s ease-in-out infinite;
}
@keyframes bar-slide {
  0% { left: -30%; }
  100% { left: 100%; }
}
.sub { margin-top: 6px; font-size: 12px; color: #666; }
.error { margin-top: 10px; color: #b00020; font-size: 13px; }
.actions { display: flex; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.btn {
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid #ccc;
  background: #fff;
  cursor: pointer;
}
.primary { border-color: #111; }
.danger { border-color: #b00020; color: #b00020; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
