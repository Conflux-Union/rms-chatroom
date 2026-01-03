<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import { useVoiceStore } from '../stores/voice'

const emit = defineEmits<{ (e: 'close'): void }>()

declare global {
  interface Window {
    hotkey?: {
      get: () => Promise<{ toggleWindow?: string; toggleMic?: string }>
      set: (key: string, accelerator: string) => Promise<{ ok: boolean; error?: string }>
    }
  }
}

const voice = useVoiceStore()
const { noiseCancelMode } = storeToRefs(voice)

type NoiseCancelMode = 'webrtc' | 'rnnoise' | 'dtln'

const toggleWindow = ref('')
const toggleMic = ref('')
const tip = ref('')

const capturing = ref<'toggleWindow' | 'toggleMic' | null>(null)

async function setNoiseMode(m: NoiseCancelMode) {
  tip.value = ''
  try {
    await voice.setNoiseCancelMode(m)
    tip.value = `降噪模式已切换：${m}`
  } catch (e) {
    tip.value = `切换失败：${String(e)}`
  }
}

// 保持你原来的 v-model 行为：改这里的值 => 仍然会触发 setNoiseMode
const noiseModeSelect = computed<NoiseCancelMode>({
  get: () => noiseCancelMode.value as NoiseCancelMode,
  set: (v) => setNoiseMode(v),
})

/* ====== 自定义下拉（替代 select） ====== */
const noiseDd = ref<HTMLElement | null>(null)
const noiseMenu = ref<HTMLElement | null>(null)

const noiseOpen = ref(false)
const noiseActiveIndex = ref(0)

const noiseOptions: Array<{ value: NoiseCancelMode; text: string }> = [
  { value: 'webrtc', text: 'WebRTC(默认降噪)' },
  { value: 'rnnoise', text: 'RNNoise(中等降噪，低CPU占用)' },
  // { value: 'dtln', text: 'DTLN(高降噪，高CPU占用)' },
]

const noiseLabel = computed(() => {
  return noiseOptions.find((o) => o.value === noiseModeSelect.value)?.text || '请选择'
})

function scrollNoiseActiveIntoView() {
  const menu = noiseMenu.value
  if (!menu) return
  const items = menu.querySelectorAll<HTMLElement>('.dd-item')
  const el = items[noiseActiveIndex.value]
  el?.scrollIntoView({ block: 'nearest' })
}

function openNoise() {
  if (noiseOpen.value) return
  noiseOpen.value = true

  const idx = noiseOptions.findIndex((o) => o.value === noiseModeSelect.value)
  noiseActiveIndex.value = idx >= 0 ? idx : 0

  nextTick(() => {
    noiseMenu.value?.focus()
    scrollNoiseActiveIntoView()
  })
}

function closeNoise() {
  noiseOpen.value = false
}

function toggleNoise() {
  noiseOpen.value ? closeNoise() : openNoise()
}

function selectNoise(v: NoiseCancelMode) {
  // 这句会走 computed setter -> setNoiseMode(v)，所以事件/逻辑完全不变
  noiseModeSelect.value = v
  closeNoise()
}

function onNoiseBtnKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    openNoise()
  }
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    toggleNoise()
  }
  if (e.key === 'Escape') {
    closeNoise()
  }
}

function onNoiseMenuKeydown(e: KeyboardEvent) {
  if (!noiseOpen.value) return

  if (e.key === 'Escape') {
    e.preventDefault()
    closeNoise()
    return
  }

  if (e.key === 'ArrowDown') {
    e.preventDefault()
    noiseActiveIndex.value = Math.min(noiseOptions.length - 1, noiseActiveIndex.value + 1)
    scrollNoiseActiveIntoView()
    return
  }

  if (e.key === 'ArrowUp') {
    e.preventDefault()
    noiseActiveIndex.value = Math.max(0, noiseActiveIndex.value - 1)
    scrollNoiseActiveIntoView()
    return
  }

  if (e.key === 'Enter') {
    e.preventDefault()
    const opt = noiseOptions[noiseActiveIndex.value]
    if (opt) selectNoise(opt.value)
  }
}

function onDocMouseDown(e: MouseEvent) {
  if (!noiseOpen.value) return
  const root = noiseDd.value
  if (root && !root.contains(e.target as Node)) closeNoise()
}

/* ====== 你原来的快捷键逻辑（不改） ====== */
function normalizeKeyName(k: string) {
  const map: Record<string, string> = {
    ' ': 'Space',
    Escape: 'Esc',
    ArrowUp: 'Up',
    ArrowDown: 'Down',
    ArrowLeft: 'Left',
    ArrowRight: 'Right',
    Delete: 'Delete',
    Backspace: 'Backspace',
    Enter: 'Enter',
    Tab: 'Tab',
  }
  if (map[k]) return map[k]
  if (/^F\d{1,2}$/.test(k)) return k
  if (k.length === 1) return k.toUpperCase()
  return k
}

function eventToAccelerator(e: KeyboardEvent): string | null {
  const parts: string[] = []
  if (e.ctrlKey) parts.push('CommandOrControl')
  if (e.altKey) parts.push('Alt')
  if (e.shiftKey) parts.push('Shift')
  if (e.metaKey) parts.push('Super')

  const key = normalizeKeyName(e.key)

  const onlyModifier = ['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)
  if (onlyModifier) return null

  parts.push(key)
  return parts.join('+')
}

async function loadShortcuts() {
  tip.value = ''
  const s = await window.hotkey?.get()
  toggleWindow.value = s?.toggleWindow || 'CommandOrControl+Alt+K'
  toggleMic.value = s?.toggleMic || 'CommandOrControl+Alt+M'
}

async function save(key: 'toggleWindow' | 'toggleMic') {
  tip.value = ''
  const val = (key === 'toggleWindow' ? toggleWindow.value : toggleMic.value).trim()
  const r = await window.hotkey?.set(key, val)
  tip.value = r?.ok ? '保存成功' : r?.error || '保存失败'
}

function startCapture(key: 'toggleWindow' | 'toggleMic') {
  tip.value = '请按下你想要的组合键（例如 Ctrl + Alt + M）'
  capturing.value = key
}

function stopCapture() {
  capturing.value = null
}

function onKeyDown(e: KeyboardEvent) {
  if (!capturing.value) return
  e.preventDefault()
  e.stopPropagation()

  const acc = eventToAccelerator(e)
  if (!acc) return

  if (capturing.value === 'toggleWindow') toggleWindow.value = acc
  if (capturing.value === 'toggleMic') toggleMic.value = acc

  tip.value = `已识别：${acc}（点保存生效）`
  capturing.value = null
}

onMounted(async () => {
  await loadShortcuts()
  window.addEventListener('keydown', onKeyDown, true)

  // 用来点击外部关闭自定义下拉
  document.addEventListener('mousedown', onDocMouseDown, true)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown, true)
  document.removeEventListener('mousedown', onDocMouseDown, true)
})
</script>

<template>
  <div class="settings-mask" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="header">
        <div class="title">设置</div>
        <button class="close" @click="emit('close')">×</button>
      </div>

      <div class="row">
        <div class="label">显示/隐藏窗口（全局快捷键）</div>
        <div class="ctrl">
          <input
            class="ipt"
            :class="{ capturing: capturing === 'toggleWindow' }"
            v-model="toggleWindow"
            readonly
            @click="startCapture('toggleWindow')"
            placeholder="点击后按组合键"
          />
          <button class="btn" @click="save('toggleWindow')">保存</button>
        </div>
      </div>

      <div class="row">
        <div class="label">开关麦克风（全局快捷键）</div>
        <div class="ctrl">
          <input
            class="ipt"
            :class="{ capturing: capturing === 'toggleMic' }"
            v-model="toggleMic"
            readonly
            @click="startCapture('toggleMic')"
            placeholder="点击后按组合键"
          />
          <button class="btn" @click="save('toggleMic')">保存</button>
        </div>
      </div>
      <div class="row">
        <div class="label">降噪模式</div>

        <div class="seg">
          <div class="dd" ref="noiseDd" :class="{ open: noiseOpen }">
            <button
              class="dd-btn"
              type="button"
              aria-haspopup="listbox"
              :aria-expanded="noiseOpen ? 'true' : 'false'"
              @click="toggleNoise()"
              @keydown="onNoiseBtnKeydown"
            >
              <span class="dd-label">{{ noiseLabel }}</span>
              <span class="dd-arrow" aria-hidden="true"></span>
            </button>

            <div
              class="dd-menu"
              role="listbox"
              ref="noiseMenu"
              :tabindex="noiseOpen ? 0 : -1"
              :aria-hidden="noiseOpen ? 'false' : 'true'"
              @keydown="onNoiseMenuKeydown"
            >
              <div
                v-for="(opt, i) in noiseOptions"
                :key="opt.value"
                class="dd-item"
                role="option"
                :aria-selected="opt.value === noiseModeSelect ? 'true' : 'false'"
                :class="{ active: i === noiseActiveIndex, selected: opt.value === noiseModeSelect }"
                @mousemove="noiseActiveIndex = i"
                @click="selectNoise(opt.value)"
              >
                {{ opt.text }}
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-if="tip" class="tip">状态: {{ tip }}</div>
      <div class="footer">
        <button class="btn ghost" @click="stopCapture(); emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0);
  display: flex;
  align-items: center;
  justify-content: center;
  transform: translateY(-50%);
  left: 50vw;
  top: 50vh;
  transform: translate(-50%, -50%);
  z-index: 100000;
  width: 500px;
}
.settings-panel {
  width: 520px;
  background: rgba(20, 20, 22, 0.95);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 14px;
  padding: 16px;
  color: #fff;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.title {
  font-size: 18px;
  font-weight: 700;
}
.close {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: none;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  cursor: pointer;
}
.row {
  margin-top: 12px;
}
.label {
  font-size: 13px;
  opacity: 0.85;
  margin-bottom: 6px;
}
.ctrl {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ipt {
  flex: 1;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
  cursor: pointer;
}
.ipt.capturing {
  outline: 2px solid rgba(96, 165, 250, 0.8);
}
.btn {
  padding: 10px 10px;
  border-radius: 10px;
  border: none;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  cursor: pointer;
}
.btn.ghost {
  background: rgba(255, 255, 255, 0.08);
}
.tip {
  margin-top: 12px;
  font-size: 12px;
  opacity: 0.85;
}
.footer {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}

/* ====== 自定义下拉样式（暗色，跟面板一致） ====== */
.dd {
  width: 100%;
  position: relative;
}

.dd-btn {
  width: 100%;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
  cursor: pointer;

  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dd-btn:focus {
  outline: 2px solid rgba(96, 165, 250, 0.8);
}

.dd-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dd-arrow {
  width: 10px;
  height: 10px;
  border-right: 2px solid rgba(255, 255, 255, 0.65);
  border-bottom: 2px solid rgba(255, 255, 255, 0.65);
  transform: rotate(45deg);
  transition: transform 0.18s ease;
  flex: 0 0 auto;
}

.dd.open .dd-arrow {
  transform: rotate(-135deg);
}

.dd-menu {
  position: absolute;
  left: 0;
  top: calc(100% + 8px);
  width: 100%;
  z-index: 60;

  background: rgba(20, 20, 22, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  padding: 6px;

  max-height: 240px;
  overflow: auto;

  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.35);

  opacity: 0;
  transform: translateY(-6px) scale(0.98);
  pointer-events: none;
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.dd.open .dd-menu {
  opacity: 1;
  transform: translateY(0) scale(1);
  pointer-events: auto;
}

.dd-item {
  padding: 6px 10px;
  font-size: 12px;
  border-radius: 10px;
  cursor: pointer;
  user-select: none;
}

.dd-item:hover {
  background: rgba(255, 255, 255, 0.08);
}

.dd-item.active {
  background: rgba(255, 255, 255, 0.1);
}

.dd-item.selected {
  background: rgba(96, 165, 250, 0.22);
  font-weight: 600;
}
</style>
