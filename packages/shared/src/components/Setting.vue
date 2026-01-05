<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import { useVoiceStore } from '../stores/voice'
import { Mic, Volume2 } from 'lucide-vue-next'

const emit = defineEmits<{ (e: 'close'): void }>()

// Internal visibility for animation
const visible = ref(false)

function handleClose() {
  visible.value = false
  setTimeout(() => emit('close'), 250)
}

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

// Keep the original v-model behavior: changing this value => will still trigger setNoiseMode
const noiseModeSelect = computed<NoiseCancelMode>({
  get: () => noiseCancelMode.value as NoiseCancelMode,
  set: (v) => setNoiseMode(v),
})

/* ====== Custom dropdown (replaces select) ====== */
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
  // This will run the computed setter -> setNoiseMode(v), so behavior/logic remains identical
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

/* ====== Device dropdowns (styled like noise dropdown) ====== */
const inputDd = ref<HTMLElement | null>(null)
const inputMenu = ref<HTMLElement | null>(null)
const inputOpen = ref(false)
const inputActiveIndex = ref(0)

const outputDd = ref<HTMLElement | null>(null)
const outputMenu = ref<HTMLElement | null>(null)
const outputOpen = ref(false)
const outputActiveIndex = ref(0)

const inputOptions = computed(() => (voice.audioInputDevices || []).map(d => ({ value: d.deviceId, text: d.label || d.deviceId })))
const outputOptions = computed(() => (voice.audioOutputDevices || []).map(d => ({ value: d.deviceId, text: d.label || d.deviceId })))

const inputLabel = computed(() => {
  if (!voice.selectedAudioInput) return '系统默认'
  const found = (voice.audioInputDevices || []).find(d => d.deviceId === voice.selectedAudioInput)
  return found?.label || voice.selectedAudioInput
})
const outputLabel = computed(() => {
  if (!voice.selectedAudioOutput) return '系统默认'
  const found = (voice.audioOutputDevices || []).find(d => d.deviceId === voice.selectedAudioOutput)
  return found?.label || voice.selectedAudioOutput
})

function scrollInputActiveIntoView() {
  const menu = inputMenu.value
  if (!menu) return
  const items = menu.querySelectorAll<HTMLElement>('.dd-item')
  const el = items[inputActiveIndex.value]
  el?.scrollIntoView({ block: 'nearest' })
}
function scrollOutputActiveIntoView() {
  const menu = outputMenu.value
  if (!menu) return
  const items = menu.querySelectorAll<HTMLElement>('.dd-item')
  const el = items[outputActiveIndex.value]
  el?.scrollIntoView({ block: 'nearest' })
}

function openInput() {
  if (inputOpen.value) return
  inputOpen.value = true
  const idx = inputOptions.value.findIndex(o => o.value === voice.selectedAudioInput)
  inputActiveIndex.value = idx >= 0 ? idx : 0
  nextTick(() => { inputMenu.value?.focus(); scrollInputActiveIntoView() })
}
function closeInput() { inputOpen.value = false }
function toggleInput() { inputOpen.value ? closeInput() : openInput() }
function selectInput(v: string) {
  voice.setAudioInputDevice(v)
  closeInput()
}
function onInputBtnKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); openInput() }
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleInput() }
  if (e.key === 'Escape') closeInput()
}
function onInputMenuKeydown(e: KeyboardEvent) {
  if (!inputOpen.value) return
  if (e.key === 'Escape') { e.preventDefault(); closeInput(); return }
  if (e.key === 'ArrowDown') { e.preventDefault(); inputActiveIndex.value = Math.min(inputOptions.value.length - 1, inputActiveIndex.value + 1); scrollInputActiveIntoView(); return }
  if (e.key === 'ArrowUp') { e.preventDefault(); inputActiveIndex.value = Math.max(0, inputActiveIndex.value - 1); scrollInputActiveIntoView(); return }
  if (e.key === 'Enter') { e.preventDefault(); const opt = inputOptions.value[inputActiveIndex.value]; if (opt) selectInput(opt.value) }
}

function openOutput() {
  if (outputOpen.value) return
  outputOpen.value = true
  const idx = outputOptions.value.findIndex(o => o.value === voice.selectedAudioOutput)
  outputActiveIndex.value = idx >= 0 ? idx : 0
  nextTick(() => { outputMenu.value?.focus(); scrollOutputActiveIntoView() })
}
function closeOutput() { outputOpen.value = false }
function toggleOutput() { outputOpen.value ? closeOutput() : openOutput() }
function selectOutput(v: string) {
  voice.setAudioOutputDevice(v)
  closeOutput()
}
function onOutputBtnKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); openOutput() }
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleOutput() }
  if (e.key === 'Escape') closeOutput()
}
function onOutputMenuKeydown(e: KeyboardEvent) {
  if (!outputOpen.value) return
  if (e.key === 'Escape') { e.preventDefault(); closeOutput(); return }
  if (e.key === 'ArrowDown') { e.preventDefault(); outputActiveIndex.value = Math.min(outputOptions.value.length - 1, outputActiveIndex.value + 1); scrollOutputActiveIntoView(); return }
  if (e.key === 'ArrowUp') { e.preventDefault(); outputActiveIndex.value = Math.max(0, outputActiveIndex.value - 1); scrollOutputActiveIntoView(); return }
  if (e.key === 'Enter') { e.preventDefault(); const opt = outputOptions.value[outputActiveIndex.value]; if (opt) selectOutput(opt.value) }
}

/* ====== Your original hotkey logic (unchanged) ====== */
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
  // Trigger enter animation
  nextTick(() => { visible.value = true })

  // enumerate devices so the settings page can show device dropdowns
  await voice.enumerateDevices()
  await loadShortcuts()
  window.addEventListener('keydown', onKeyDown, true)

  // 用来点击外部关闭自定义下拉
  document.addEventListener('mousedown', onDocMouseDown, true)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown, true)
  document.removeEventListener('mousedown', onDocMouseDown, true)
})

// Mic test state
const micTestActive = ref(false)
const micLevel = ref(0)
let micTestStream: MediaStream | null = null
let micAnalyzerNode: AnalyserNode | null = null
let micProcessorInterval: number | null = null

async function startMicTest() {
  if (micTestActive.value) return
  try {
    micTestActive.value = true
    const deviceId = voice.selectedAudioInput || undefined
    micTestStream = await navigator.mediaDevices.getUserMedia({ audio: deviceId ? { deviceId: { exact: deviceId } } : true })
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
    const source = ctx.createMediaStreamSource(micTestStream)
    micAnalyzerNode = ctx.createAnalyser()
    micAnalyzerNode.fftSize = 2048
    source.connect(micAnalyzerNode)
    const data = new Uint8Array(micAnalyzerNode.frequencyBinCount)
    micProcessorInterval = window.setInterval(() => {
      if (!micAnalyzerNode) return
      micAnalyzerNode.getByteTimeDomainData(data)
      let sum = 0
      for (let i = 0; i < data.length; i++) {
        const sample = data[i] ?? 128
        const v = (sample - 128) / 128
        sum += v * v
      }
      const rms = Math.sqrt(sum / data.length)
      micLevel.value = Math.min(1, rms * 1.5)
    }, 100)
  } catch (e) {
    micTestActive.value = false
    micLevel.value = 0
    console.log('Mic test failed', e)
  }
}

function stopMicTest() {
  if (!micTestActive.value) return
  micTestActive.value = false
  micLevel.value = 0
  if (micProcessorInterval) { clearInterval(micProcessorInterval); micProcessorInterval = null }
  try { micAnalyzerNode?.disconnect(); micAnalyzerNode = null } catch {}
  if (micTestStream) { micTestStream.getTracks().forEach(t => t.stop()); micTestStream = null }
}

// Output test state
const outputTestPlaying = ref(false)
let testAudioEl: HTMLAudioElement | null = null
let outputOscCtx: AudioContext | null = null
let outputOscTimeout: number | null = null

async function startOutputTest() {
  if (outputTestPlaying.value) return
  try {
    outputTestPlaying.value = true
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
    outputOscCtx = ctx
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = 880
    gain.gain.value = 0.05
    osc.connect(gain)

    const dest = ctx.createMediaStreamDestination()
    gain.connect(dest)

    testAudioEl = document.createElement('audio')
    testAudioEl.autoplay = true
    testAudioEl.srcObject = dest.stream
    ;(testAudioEl as any).playsInline = true
    testAudioEl.style.display = 'none'
    document.body.appendChild(testAudioEl)

    const sinkId = voice.selectedAudioOutput || ''
    if (sinkId && typeof (testAudioEl as any).setSinkId === 'function') {
      try { await (testAudioEl as any).setSinkId(sinkId) } catch (e) { console.warn('setSinkId failed', e) }
    }

    osc.start()
    outputOscTimeout = window.setTimeout(() => { stopOutputTest() }, 2000)
  } catch (e) {
    console.log('output test failed', e)
    outputTestPlaying.value = false
  }
}

function stopOutputTest() {
  if (!outputTestPlaying.value) return
  outputTestPlaying.value = false
  if (outputOscTimeout) { clearTimeout(outputOscTimeout); outputOscTimeout = null }
  try { outputOscCtx?.close(); outputOscCtx = null } catch {}
  if (testAudioEl) {
    try { testAudioEl.pause(); testAudioEl.srcObject = null; document.body.removeChild(testAudioEl) } catch {}
    testAudioEl = null
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="settings-modal">
      <div v-if="visible" class="settings-mask" @click.self="handleClose">
        <div class="settings-panel">
          <div class="header">
            <div class="title">设置</div>
            <button class="close" @click="handleClose">×</button>
          </div>

      <div class="row">
        <div class="label">输入设备</div>
        <div class="ctrl">
          <div class="dd dd-flex"
            ref="inputDd"
            :class="{ open: inputOpen }"
          >
            <button
              class="dd-btn"
              type="button"
              aria-haspopup="listbox"
              :aria-expanded="inputOpen ? 'true' : 'false'"
              @click="toggleInput()"
              @keydown="onInputBtnKeydown"
            >
              <span class="dd-label"><Mic :size="14" /> {{ inputLabel }}</span>
              <span class="dd-arrow" aria-hidden="true"></span>
            </button>

            <div
              class="dd-menu"
              role="listbox"
              ref="inputMenu"
              :tabindex="inputOpen ? 0 : -1"
              :aria-hidden="inputOpen ? 'false' : 'true'"
              @keydown="onInputMenuKeydown"
            >
              <div
                class="dd-item"
                role="option"
                :class="{ selected: !voice.selectedAudioInput }"
                @click="selectInput('')"
              >
                系统默认
              </div>

              <div
                v-for="(opt, i) in inputOptions"
                :key="opt.value"
                class="dd-item"
                role="option"
                :aria-selected="opt.value === voice.selectedAudioInput ? 'true' : 'false'"
                :class="{ active: i === inputActiveIndex, selected: opt.value === voice.selectedAudioInput }"
                @mousemove="inputActiveIndex = i"
                @click="selectInput(opt.value)"
              >
                {{ opt.text }}
              </div>
            </div>
          </div>

          <div class="inline-test">
            <button class="btn" @click="micTestActive ? stopMicTest() : startMicTest()">{{ micTestActive ? '停止' : '测试' }}</button>
          </div>
        </div>
      </div>

      <div class="row">
        <div class="label"></div>
        <div class="ctrl">
          <div class="mic-meter full">
            <div class="mic-meter-bar" :style="{ width: (micLevel * 100) + '%' }"></div>
          </div>
        </div>
      </div>

      <div class="row">
        <div class="label">输出设备</div>
        <div class="ctrl">
          <div class="dd dd-flex"
            ref="outputDd"
            :class="{ open: outputOpen }"
          >
            <button
              class="dd-btn"
              type="button"
              aria-haspopup="listbox"
              :aria-expanded="outputOpen ? 'true' : 'false'"
              @click="toggleOutput()"
              @keydown="onOutputBtnKeydown"
            >
              <span class="dd-label"><Volume2 :size="14" /> {{ outputLabel }}</span>
              <span class="dd-arrow" aria-hidden="true"></span>
            </button>

            <div
              class="dd-menu"
              role="listbox"
              ref="outputMenu"
              :tabindex="outputOpen ? 0 : -1"
              :aria-hidden="outputOpen ? 'false' : 'true'"
              @keydown="onOutputMenuKeydown"
            >
              <div
                class="dd-item"
                role="option"
                :class="{ selected: !voice.selectedAudioOutput }"
                @click="selectOutput('')"
              >
                系统默认
              </div>

              <div
                v-for="(opt, i) in outputOptions"
                :key="opt.value"
                class="dd-item"
                role="option"
                :aria-selected="opt.value === voice.selectedAudioOutput ? 'true' : 'false'"
                :class="{ active: i === outputActiveIndex, selected: opt.value === voice.selectedAudioOutput }"
                @mousemove="outputActiveIndex = i"
                @click="selectOutput(opt.value)"
              >
                {{ opt.text }}
              </div>
            </div>
          </div>

          <div class="inline-test">
            <button class="btn" @click="outputTestPlaying ? stopOutputTest() : startOutputTest()">{{ outputTestPlaying ? '停止' : '播放' }}</button>
          </div>
        </div>
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

      <!-- Device selection moved from VoicePanel -->
      

      <div class="row">
        <div class="label">降噪模式</div>

        <div class="seg">
          <div
            class="dd"
            ref="noiseDd"
            :class="{ open: noiseOpen }"
          >
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
        <button class="btn ghost" @click="stopCapture(); handleClose()">关闭</button>
      </div>
    </div>
  </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.settings-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100000;
}
.settings-panel {
  width: 520px;
  max-height: 90vh;
  overflow-y: auto;
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: var(--radius-lg);
  padding: 24px;
  color: var(--color-text-main);
  box-shadow: var(--shadow-lg);
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.1);
}
.title {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text-main);
}
.close {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: none;
  background: rgba(0, 0, 0, 0.08);
  color: var(--color-text-main);
  font-size: 18px;
  cursor: pointer;
  transition: all var(--transition-fast);
}
.close:hover {
  background: rgba(0, 0, 0, 0.15);
}
.row {
  margin-top: 16px;
}
.label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}
.ctrl {
  display: flex;
  gap: 8px;
  align-items: center;
}
.device-ctrl-column {
  flex-direction: column;
  gap: 8px;
}
.dd-fullwidth {
  width: 100%;
}
.test-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-top: 8px;
}

.ipt {
  flex: 1;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  cursor: pointer;
  transition: all var(--transition-fast);
}
.ipt:focus {
  outline: none;
  border-color: var(--color-primary);
  background: var(--surface-glass-input-focus);
}
.ipt.capturing {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.btn {
  padding: 10px 16px;
  border-radius: var(--radius-sm);
  border: none;
  background: var(--color-gradient-primary);
  color: white;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-glow);
}
.btn:hover {
  transform: translateY(-1px);
  filter: brightness(1.05);
}
.btn.ghost {
  background: rgba(0, 0, 0, 0.08);
  color: var(--color-text-main);
  box-shadow: none;
}
.btn.ghost:hover {
  background: rgba(0, 0, 0, 0.12);
}
.tip {
  margin-top: 16px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--color-text-muted);
  background: rgba(0, 0, 0, 0.05);
  border-radius: var(--radius-sm);
}
.footer {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.1);
  display: flex;
  justify-content: flex-end;
}

/* Custom dropdown */
.dd {
  width: 100%;
  position: relative;
}
.dd-flex {
  flex: 1;
}

.dd-btn {
  width: 100%;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dd-btn:hover {
  background: var(--surface-glass-input-focus);
}

.dd-btn:focus {
  outline: none;
  border-color: var(--color-primary);
}

.dd-label {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dd-arrow {
  width: 8px;
  height: 8px;
  border-right: 2px solid var(--color-text-muted);
  border-bottom: 2px solid var(--color-text-muted);
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
  top: calc(100% + 6px);
  width: 100%;
  z-index: 60;
  background: var(--surface-glass-strong);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: var(--radius-md);
  padding: 6px;
  max-height: 240px;
  overflow: auto;
  box-shadow: var(--shadow-md);
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
  padding: 8px 12px;
  font-size: 13px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  user-select: none;
  color: var(--color-text-main);
  transition: background var(--transition-fast);
}

.dd-item:hover {
  background: rgba(0, 0, 0, 0.06);
}

.dd-item.active {
  background: rgba(0, 0, 0, 0.08);
}

.dd-item.selected {
  background: rgba(252, 121, 97, 0.15);
  color: var(--color-primary);
  font-weight: 600;
}

/* Mic meter */
.mic-meter {
  height: 8px;
  background: rgba(0, 0, 0, 0.1);
  border-radius: var(--radius-pill);
  overflow: hidden;
}
.mic-meter-bar {
  height: 100%;
  width: 0%;
  background: var(--color-gradient-primary);
  transition: width 0.08s linear;
}

.mic-meter.full { flex: 1 }
.inline-test { white-space: nowrap }

/* Modal transition */
.settings-modal-enter-active,
.settings-modal-leave-active {
  transition: opacity 0.25s ease;
}
.settings-modal-enter-active .settings-panel,
.settings-modal-leave-active .settings-panel {
  transition: transform 0.25s cubic-bezier(0.175, 0.885, 0.32, 1.275), opacity 0.25s ease;
}

.settings-modal-enter-from,
.settings-modal-leave-to {
  opacity: 0;
}
.settings-modal-enter-from .settings-panel {
  transform: scale(0.9) translateY(20px);
}
.settings-modal-leave-to .settings-panel {
  transform: scale(0.95) translateY(-10px);
}
</style>
