<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useVoiceStore } from '../stores/voice'
import { ZmModal, ZmSelect, ZmButton, ZmSpace, ZmProgress } from './ui'
import type { ZmSelectOption } from './ui'
import { Mic, Volume2, Activity, Bell, Globe } from 'lucide-vue-next'
import { isTauri } from '../index'
import { isTelemetryEnabled, setTelemetryEnabled } from '../utils/telemetry'
import { t, getLocalePreference, setLocalePreference } from '../i18n'
import type { LocalePreference } from '../i18n'

const emit = defineEmits<{ (e: 'close'): void }>()

// The parent owns visibility via v-if. Route every close path (backdrop, ✕,
// Esc) straight to it so the two never drift apart.
function handleClose() {
  emit('close')
}

const voice = useVoiceStore()

const toggleWindow = ref('')
const toggleMic = ref('')
const tip = ref('')
const capturing = ref<'toggleWindow' | 'toggleMic' | null>(null)

// Telemetry opt-out
const telemetryEnabled = ref(isTelemetryEnabled())
function toggleTelemetry() {
  telemetryEnabled.value = !telemetryEnabled.value
  setTelemetryEnabled(telemetryEnabled.value)
}

// Language preference. Option labels for the concrete languages stay in their
// own language regardless of the active locale.
const languageOptions = computed(() => [
  { value: 'system', label: t('settings.languageSystem') },
  { value: 'zh', label: '简体中文' },
  { value: 'en', label: 'English' },
])
const selectedLanguage = computed({
  get: () => getLocalePreference(),
  set: (v) => setLocalePreference(v as LocalePreference),
})

// Voice join TTS announcement
function toggleVoiceAnnounce() {
  voice.setVoiceAnnounceEnabled(!voice.voiceAnnounceEnabled)
}

// setSinkId support: Chromium yes, Firefox 135+, Safari never. Without it the
// output picker silently does nothing, so hide the row where unsupported.
const supportsAudioOutput = typeof document !== 'undefined'
  && typeof document.createElement('audio').setSinkId === 'function'

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
  if (!isTauri) return
  const { invoke } = await import('@tauri-apps/api/core')
  const s = await invoke<Record<string, string>>('get_shortcuts')
  toggleWindow.value = s?.toggleWindow || 'CommandOrControl+Alt+K'
  toggleMic.value = s?.toggleMic || 'CommandOrControl+Alt+M'
}

async function save(key: 'toggleWindow' | 'toggleMic') {
  tip.value = ''
  if (!isTauri) return
  const { invoke } = await import('@tauri-apps/api/core')
  const val = (key === 'toggleWindow' ? toggleWindow.value : toggleMic.value).trim()
  const r = await invoke<Record<string, boolean>>('set_shortcut', { key, accelerator: val })
  tip.value = r?.ok ? t('common.saved') : t('common.saveFailed')
}

function startCapture(key: 'toggleWindow' | 'toggleMic') {
  tip.value = t('settings.hotkeyTip')
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

  tip.value = t('settings.hotkeyDetected', { acc })
  capturing.value = null
}

onMounted(async () => {
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
    console.error('Mic test failed', e)
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
    console.error('output test failed', e)
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
  <ZmModal
    :show="true"
    :title="t('settings.title')"
    style="width: 520px; max-width: 90vw"
    @update:show="(v: boolean) => { if (!v) handleClose() }"
  >
    <ZmSpace vertical :size="20">
      <!-- Language -->
      <div class="setting-row">
        <div class="setting-label">
          <Globe :size="16" />
          <span>{{ t('settings.language') }}</span>
        </div>
        <div class="setting-ctrl">
          <ZmSelect
            v-model:value="selectedLanguage"
            :options="languageOptions"
            style="flex: 1"
          />
        </div>
      </div>

      <!-- Input Device -->
      <div class="setting-row">
        <div class="setting-label">
          <Mic :size="16" />
          <span>{{ t('settings.inputDevice') }}</span>
        </div>
        <div class="setting-ctrl">
          <ZmSelect
            v-model:value="selectedInput"
            :options="inputOptions"
            style="flex: 1"
          />
          <ZmButton
            size="small"
            :type="micTestActive ? 'error' : 'default'"
            @click="micTestActive ? stopMicTest() : startMicTest()"
          >
            {{ micTestActive ? t('common.stop') : t('common.test') }}
          </ZmButton>
        </div>
        <ZmProgress
          :percentage="micLevel"
          :show-indicator="false"
          style="margin-top: 8px"
        />
      </div>

      <!-- Output Device -->
      <div v-if="supportsAudioOutput" class="setting-row">
        <div class="setting-label">
          <Volume2 :size="16" />
          <span>{{ t('settings.outputDevice') }}</span>
        </div>
        <div class="setting-ctrl">
          <ZmSelect
            v-model:value="selectedOutput"
            :options="outputOptions"
            style="flex: 1"
          />
          <ZmButton
            size="small"
            :type="outputTestPlaying ? 'error' : 'default'"
            @click="outputTestPlaying ? stopOutputTest() : startOutputTest()"
          >
            {{ outputTestPlaying ? t('common.stop') : t('settings.play') }}
          </ZmButton>
        </div>
      </div>

      <!-- Voice join TTS announcement -->
      <div class="setting-row">
        <div class="setting-label">
          <Bell :size="16" />
          <span>{{ t('settings.voiceAnnounce') }}</span>
        </div>
        <div class="setting-ctrl">
          <span class="telemetry-desc">{{ t('settings.voiceAnnounceDesc') }}</span>
          <ZmButton
            size="small"
            :type="voice.voiceAnnounceEnabled ? 'primary' : 'default'"
            @click="toggleVoiceAnnounce"
          >
            {{ voice.voiceAnnounceEnabled ? t('common.enabled') : t('common.disabled') }}
          </ZmButton>
        </div>
      </div>

      <!-- Hotkey: Toggle Window -->
      <div v-if="isTauri" class="setting-row">
        <div class="setting-label">{{ t('settings.hotkeyWindow') }}</div>
        <div class="setting-ctrl">
          <input
            class="hotkey-input"
            :class="{ capturing: capturing === 'toggleWindow' }"
            v-model="toggleWindow"
            readonly
            @click="startCapture('toggleWindow')"
            :placeholder="t('settings.hotkeyPlaceholder')"
          />
          <ZmButton size="small" @click="save('toggleWindow')">{{ t('common.save') }}</ZmButton>
        </div>
      </div>

      <!-- Hotkey: Toggle Mic -->
      <div v-if="isTauri" class="setting-row">
        <div class="setting-label">{{ t('settings.hotkeyMic') }}</div>
        <div class="setting-ctrl">
          <input
            class="hotkey-input"
            :class="{ capturing: capturing === 'toggleMic' }"
            v-model="toggleMic"
            readonly
            @click="startCapture('toggleMic')"
            :placeholder="t('settings.hotkeyPlaceholder')"
          />
          <ZmButton size="small" @click="save('toggleMic')">{{ t('common.save') }}</ZmButton>
        </div>
      </div>

      <!-- Telemetry -->
      <div class="setting-row">
        <div class="setting-label">
          <Activity :size="16" />
          <span>{{ t('settings.telemetry') }}</span>
        </div>
        <div class="setting-ctrl">
          <span class="telemetry-desc">{{ t('settings.telemetryDesc') }}</span>
          <ZmButton
            size="small"
            :type="telemetryEnabled ? 'primary' : 'default'"
            @click="toggleTelemetry"
          >
            {{ telemetryEnabled ? t('common.enabled') : t('common.disabled') }}
          </ZmButton>
        </div>
      </div>

      <!-- Status tip -->
      <div v-if="tip" class="tip">{{ tip }}</div>
    </ZmSpace>

    <template #footer>
      <ZmSpace justify="end">
        <ZmButton @click="stopCapture(); handleClose()">{{ t('common.close') }}</ZmButton>
      </ZmSpace>
    </template>
  </ZmModal>
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

.telemetry-desc {
  flex: 1;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
