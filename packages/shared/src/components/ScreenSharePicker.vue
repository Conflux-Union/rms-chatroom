<template>
  <!-- 只负责弹出“选择共享源”的窗口，触发按钮保留在外面（原来的共享屏幕按钮） -->
  <teleport to="body">
    <div v-if="modelValue" class="ss-modal-mask" @click.self="closeModal">
      <div class="ss-modal">
        <div class="ss-modal-header">
          <div class="ss-title">选择要共享的窗口/屏幕</div>
          <button class="ss-x" @click="closeModal">✕</button>
        </div>

        <div class="ss-toolbar">
          <input class="ss-input" v-model="keyword" placeholder="搜索窗口名..." />
          <button class="ss-btn2" @click="refreshSources" :disabled="loading">
            {{ loading ? '刷新中...' : '刷新列表' }}
          </button>
        </div>

        <div class="ss-grid">
          <button
            v-for="s in filteredSources"
            :key="s.id"
            class="ss-card"
            @click="selectAndStart(s)"
          >
            <div class="ss-thumb">
              <img v-if="s.thumbnail" :src="s.thumbnail" alt="" />
              <div v-else class="ss-thumb-empty">无预览</div>
            </div>
            <div class="ss-name" :title="s.name">{{ s.name }}</div>
          </button>

          <div v-if="!loading && filteredSources.length === 0" class="ss-empty">
            没找到可共享的窗口/屏幕
          </div>
        </div>

        <div class="ss-footer">
          <button class="ss-btn3" @click="closeModal">取消</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useVoiceStore } from '../stores/voice'

type CaptureSource = {
  id: string
  name: string
  thumbnail: string | null
  appIcon?: string | null
}

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const voice = useVoiceStore()

const sources = ref<CaptureSource[]>([])
const loading = ref(false)
const keyword = ref('')

const isElectron = computed(() => {
  const api = (window as any).electronAPI
  return !!api?.getCaptureSources && !!api?.setCaptureSource
})

const filteredSources = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return sources.value
  return sources.value.filter((s) => (s.name || '').toLowerCase().includes(k))
})

function closeModal() {
  emit('update:modelValue', false)
}

async function refreshSources() {
  if (!isElectron.value) return
  loading.value = true
  try {
    const api = (window as any).electronAPI
    const list = await api.getCaptureSources()
    sources.value = Array.isArray(list) ? list : []
  } finally {
    loading.value = false
  }
}

async function selectAndStart(s: CaptureSource) {
  try {
    const api = (window as any).electronAPI
    await api.setCaptureSource(s.id)
    closeModal()

    // 继续用你原来的共享逻辑（LiveKit）
    const ok = await voice.toggleScreenShare()
    if (!ok) {
      // 如果失败，清掉选中的 source，避免下次“误用旧的选择”
      if (api?.clearCaptureSource) await api.clearCaptureSource()
    }
  } catch (e) {
    console.log('selectAndStart failed:', e)
  }
}

// 打开时自动拉一次列表
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      keyword.value = ''
      refreshSources()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.ss-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  z-index: 9999;
}

.ss-modal {
  width: min(980px, 95vw);
  max-height: 85vh;
  background: #fff;
  border-radius: 14px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.ss-modal-header {
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
}

.ss-title {
  font-size: 16px;
  font-weight: 600;
}

.ss-x {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
}

.ss-toolbar {
  padding: 12px 16px;
  display: flex;
  gap: 10px;
  border-bottom: 1px solid #eee;
}

.ss-input {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 10px;
  outline: none;
}

.ss-btn2 {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #ddd;
  background: #fff;
  cursor: pointer;
}

.ss-btn2:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ss-grid {
  padding: 14px 16px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  overflow: auto;
}

.ss-card {
  border: 1px solid #eee;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  cursor: pointer;
  text-align: left;
  padding: 0;
}

.ss-card:hover {
  border-color: #d5d5d5;
}

.ss-thumb {
  height: 120px;
  background: #f6f6f6;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ss-thumb img {
  max-width: 100%;
  max-height: 100%;
  display: block;
}

.ss-thumb-empty {
  font-size: 12px;
  color: #888;
}

.ss-name {
  padding: 10px 12px;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ss-empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #777;
  padding: 20px 0;
}

.ss-footer {
  padding: 12px 16px;
  border-top: 1px solid #eee;
  display: flex;
  justify-content: flex-end;
}

.ss-btn3 {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #ddd;
  background: #fff;
  cursor: pointer;
}
</style>
