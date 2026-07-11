<template>
  <!-- Teleport to <body> so position:fixed escapes #app's global
       `#app > *:not(.ink-bg) { position: relative }` rule, which would
       override .mask's position:fixed and pin the dialog to the document flow. -->
  <Teleport to="body">
    <div v-if="visible" class="mask">
    <div class="box">
      <div class="title">
        {{ forced ? "需要更新才能继续使用" : "发现新版本" }}
      </div>

      <div class="info">
        <div>状态：{{ stateText }}</div>
        <div v-if="versionText">版本：{{ versionText }}</div>
      </div>

      <div v-if="state === 'downloading'" class="progress">
        <div>下载中：{{ percent.toFixed(1) }}%</div>
        <div class="sub">{{ formatBytes(transferred) }} / {{ formatBytes(total) }}</div>
      </div>

      <div v-if="state === 'error'" class="error">
        更新出错：{{ message }}
      </div>

      <div class="actions">
        <template v-if="forced">
          <button class="btn primary" :disabled="btnDisabled" @click="forceUpdateAction">
            {{ forceBtnText }}
          </button>
          <button class="btn danger" @click="quit">退出</button>
        </template>

        <template v-else>
          <button
            v-if="state === 'available'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="download"
          >
            下载更新
          </button>

          <button
            v-if="state === 'downloaded'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="install"
          >
            安装并重启
          </button>

          <button class="btn" @click="later">稍后</button>
          <button class="btn" @click="visible = false">关闭</button>
        </template>
      </div>
    </div>
  </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { isTauri } from '../index'

const visible = ref(false)
const state = ref<'idle' | 'checking' | 'available' | 'none' | 'downloading' | 'downloaded' | 'error'>('idle')
const forced = ref(false)
const percent = ref(0)
const transferred = ref(0)
const total = ref(0)
const message = ref('')
const versionText = ref('')

let updateObj: any = null

const stateText = computed(() => {
  const map: Record<string, string> = {
    idle: '空闲',
    checking: '检查更新中',
    available: '有新版本（待下载）',
    none: '已是最新版本',
    downloading: '正在下载',
    downloaded: '下载完成',
    error: '错误',
  }
  return map[state.value] || state.value
})

const btnDisabled = computed(() => state.value === 'checking' || state.value === 'downloading')

const forceBtnText = computed(() => {
  if (state.value === 'downloaded') return '安装并重启'
  if (state.value === 'downloading') return '下载中…'
  if (state.value === 'available') return '更新（开始下载）'
  return '更新'
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
  try {
    let contentLength = 0
    let chunkLength = 0
    await updateObj.downloadAndInstall((event: any) => {
      if (event.event === 'Started') {
        contentLength = event.data.contentLength || 0
        total.value = contentLength
        transferred.value = 0
      } else if (event.event === 'Progress') {
        chunkLength += event.data.chunkLength
        transferred.value = chunkLength
        if (contentLength > 0) {
          percent.value = (chunkLength / contentLength) * 100
        }
      } else if (event.event === 'Finished') {
        state.value = 'downloaded'
        percent.value = 100
      }
    })
    // downloadAndInstall may auto-install; if we reach here without error, mark as downloaded
    state.value = 'downloaded'
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
