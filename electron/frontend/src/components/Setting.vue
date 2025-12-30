<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const emit = defineEmits<{ (e: 'close'): void }>()

declare global {
  interface Window {
    hotkey?: {
      get: () => Promise<{ toggleWindow?: string; toggleMic?: string }>
      set: (key: string, accelerator: string) => Promise<{ ok: boolean; error?: string }>
    }
  }
}

const toggleWindow = ref('')
const toggleMic = ref('')
const tip = ref('')

const capturing = ref<'toggleWindow' | 'toggleMic' | null>(null)

function normalizeKeyName(k: string) {
  // 常用键名映射成 Electron accelerator 能认的
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

  // Windows/ Linux 的 Ctrl 统一用 CommandOrControl
  if (e.ctrlKey) parts.push('CommandOrControl')
  if (e.altKey) parts.push('Alt')
  if (e.shiftKey) parts.push('Shift')
  if (e.metaKey) parts.push('Super') // 有的键盘会用到

  const key = normalizeKeyName(e.key)

  // 只按修饰键不算
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
  tip.value = r?.ok ? '保存成功' : (r?.error || '保存失败')
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
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown, true)
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

      <div v-if="tip" class="tip">{{ tip }}</div>
      <div class="footer">
        <button class="btn ghost" @click="stopCapture(); emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-mask{
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0);
  display:flex;
  align-items:center;
  justify-content:center;
  transform: translateY(-50%);
  left: 50vw;
  top: 50vh;
  transform: translate(-50%, -50%);
  z-index: 100000;
  width: 500px;
}
.settings-panel{
  width: 520px;
  background: rgba(20,20,22,.95);
  border: 1px solid rgba(255,255,255,.12);
  border-radius: 14px;
  padding: 16px;
  color: #fff;
}
.header{
  display:flex;
  align-items:center;
  justify-content:space-between;
  margin-bottom: 12px;
}
.title{ font-size: 18px; font-weight: 700; }
.close{
  width: 32px; height: 32px;
  border-radius: 10px;
  border: none;
  background: rgba(255,255,255,.08);
  color:#fff;
  cursor:pointer;
}
.row{ margin-top: 12px; }
.label{ font-size: 13px; opacity: .85; margin-bottom: 6px; }
.ctrl{ display:flex; gap: 8px; align-items:center; }
.ipt{
  flex: 1;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255,255,255,.14);
  background: rgba(255,255,255,.06);
  color: #fff;
  cursor: pointer;
}
.ipt.capturing{
  outline: 2px solid rgba(96,165,250,.8);
}
.btn{
  padding: 10px 14px;
  border-radius: 10px;
  border: none;
  background: rgba(255,255,255,.12);
  color:#fff;
  cursor:pointer;
}
.btn.ghost{
  background: rgba(255,255,255,.08);
}
.tip{
  margin-top: 12px;
  font-size: 12px;
  opacity: .85;
}
.footer{
  margin-top: 14px;
  display:flex;
  justify-content:flex-end;
}
</style>
