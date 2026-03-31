<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, nextTick } from 'vue'
import { useVoiceStore } from '../stores/voice'
import { NModal, NSelect, NButton, NSpace, NProgress } from 'naive-ui'
import { Mic, Volume2 } from 'lucide-vue-next'

const emit = defineEmits<{ (e: 'close'): void }>()

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

const toggleWindow = ref('')
const toggleMic = ref('')
const tip = ref('')
const capturing = ref<'toggleWindow' | 'toggleMic' | null>(null)

// Device options
const inputOptions = computed(() => {
  const opts = [{ value: '', label: '系统默认' }]
  for (const d of voice.audioInputDevices || []) {
    opts.push({ value: d.deviceId, label: d.label || d.deviceId })
  }
  return opts
})

const outputOptions = computed(() => {
  const opts = [{ value: '', label: '系统默认' }]
  for (const d of voice.audioOutputDevices || []) {
    opts.push({ value: d.deviceId, label: d.label || d.deviceId })
  }
  return opts
})

const selectedInput = computed({
  get: () => voice.selectedAudioInput || '',
  set: (v) => voice.setAudioInputDevice(v),
})

const selectedOutput = computed({
  get: () => voice.selectedAudioOutput || '',
  set: (v) => voice.setAudioOutputDevice(v),
})

// Hotkey logic
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
  tip.value = r?.ok ? '已保存' : r?.error || '保存失败'
}

function startCapture(key: 'toggleWindow' | 'toggleMic') {
  tip.value = '请按下你想要的快捷键组合（例如 Ctrl + Alt + M）'
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

  tip.value = `检测到: ${acc}（点击保存以应用）`
  capturing.value = null
}

onMounted(async () => {
  nextTick(() => {
    visible.value = true
  })
  await voice.enumerateDevices()
  await loadShortcuts()
  window.addEventListener('keydown', onKeyDown, true)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown, true)
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
    micTestStream = await navigator.mediaDevices.getUserMedia({
      audio: deviceId ? { deviceId: { exact: deviceId } } : true,
    })
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
      micLevel.value = Math.min(100, rms * 150)
    }, 200)
  } catch (e) {
    micTestActive.value = false
    micLevel.value = 0
    console.log('Mic test failed', e)
  }
}

onUnmounted(() => {
  stopMicTest()
})

function stopMicTest() {
  if (!micTestActive.value) return
  micTestActive.value = false
  micLevel.value = 0
  if (micProcessorInterval) {
    clearInterval(micProcessorInterval)
    micProcessorInterval = null
  }
  try {
    micAnalyzerNode?.disconnect()
    micAnalyzerNode = null
  } catch {}
  if (micTestStream) {
    micTestStream.getTracks().forEach((t) => t.stop())
    micTestStream = null
  }
}

// Output test state
const outputTestPlaying = ref(false)
let outputOscCtx: AudioContext | null = null
let outputOscTimeout: number | null = null
let testAudioEl: HTMLAudioElement | null = null

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
      try {
        await (testAudioEl as any).setSinkId(sinkId)
      } catch (e) {
        console.warn('setSinkId failed', e)
      }
    }

    osc.start()
    outputOscTimeout = window.setTimeout(() => {
      stopOutputTest()
    }, 2000)
  } catch (e) {
    console.log('output test failed', e)
    outputTestPlaying.value = false
  }
}

function stopOutputTest() {
  if (!outputTestPlaying.value) return
  outputTestPlaying.value = false
  if (outputOscTimeout) {
    clearTimeout(outputOscTimeout)
    outputOscTimeout = null
  }
  try {
    outputOscCtx?.close()
    outputOscCtx = null
  } catch {}
  if (testAudioEl) {
    try {
      testAudioEl.pause()
      testAudioEl.srcObject = null
      document.body.removeChild(testAudioEl)
    } catch {}
    testAudioEl = null
  }
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    title="设置"
    :bordered="false"
    :closable="true"
    :mask-closable="true"
    style="width: 520px; max-width: 90vw"
    @after-leave="emit('close')"
  >
    <NSpace vertical :size="20">
      <!-- Input Device -->
      <div class="setting-row">
        <div class="setting-label">
          <Mic :size="16" />
          <span>输入设备</span>
        </div>
        <div class="setting-ctrl">
          <NSelect
            v-model:value="selectedInput"
            :options="inputOptions"
            style="flex: 1"
          />
          <NButton
            size="small"
            :type="micTestActive ? 'error' : 'default'"
            @click="micTestActive ? stopMicTest() : startMicTest()"
          >
            {{ micTestActive ? '停止' : '测试' }}
          </NButton>
        </div>
        <NProgress
          type="line"
          :percentage="micLevel"
          :show-indicator="false"
          :height="8"
          style="margin-top: 8px"
        />
      </div>

      <!-- Output Device -->
      <div class="setting-row">
        <div class="setting-label">
          <Volume2 :size="16" />
          <span>输出设备</span>
        </div>
        <div class="setting-ctrl">
          <NSelect
            v-model:value="selectedOutput"
            :options="outputOptions"
            style="flex: 1"
          />
          <NButton
            size="small"
            :type="outputTestPlaying ? 'error' : 'default'"
            @click="outputTestPlaying ? stopOutputTest() : startOutputTest()"
          >
            {{ outputTestPlaying ? '停止' : '播放' }}
          </NButton>
        </div>
      </div>

      <!-- Hotkey: Toggle Window -->
      <div class="setting-row">
        <div class="setting-label">显示/隐藏窗口（全局快捷键）</div>
        <div class="setting-ctrl">
          <input
            class="hotkey-input"
            :class="{ capturing: capturing === 'toggleWindow' }"
            v-model="toggleWindow"
            readonly
            @click="startCapture('toggleWindow')"
            placeholder="点击后按下快捷键"
          />
          <NButton size="small" @click="save('toggleWindow')">保存</NButton>
        </div>
      </div>

      <!-- Hotkey: Toggle Mic -->
      <div class="setting-row">
        <div class="setting-label">切换麦克风（全局快捷键）</div>
        <div class="setting-ctrl">
          <input
            class="hotkey-input"
            :class="{ capturing: capturing === 'toggleMic' }"
            v-model="toggleMic"
            readonly
            @click="startCapture('toggleMic')"
            placeholder="点击后按下快捷键"
          />
          <NButton size="small" @click="save('toggleMic')">保存</NButton>
        </div>
      </div>

      <!-- Status tip -->
      <div v-if="tip" class="tip">{{ tip }}</div>
    </NSpace>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="stopCapture(); handleClose()">关闭</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.setting-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.setting-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-muted);
}

.setting-ctrl {
  display: flex;
  gap: 8px;
  align-items: center;
}

.hotkey-input {
  flex: 1;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.5);
  color: var(--color-text-main);
  cursor: pointer;
  transition: all 0.2s ease;
}

.hotkey-input:focus {
  outline: none;
  border-color: var(--color-primary);
  background: rgba(255, 255, 255, 0.65);
}

.hotkey-input.capturing {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.tip {
  padding: 10px 14px;
  font-size: 13px;
  color: var(--color-text-muted);
  background: rgba(0, 0, 0, 0.05);
  border-radius: 8px;
}
</style>
