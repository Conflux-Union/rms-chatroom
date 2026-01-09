<template>
  <NModal
    v-model:show="showModal"
    preset="card"
    :title="'选择要共享的窗口/屏幕'"
    :style="{ width: 'min(980px, 95vw)', maxHeight: '85vh' }"
    :segmented="{ content: true, footer: 'soft' }"
    :closable="true"
    @update:show="handleShowChange"
  >
    <template #header-extra>
      <NSpace>
        <NInput
          v-model:value="keyword"
          placeholder="搜索窗口名..."
          clearable
          style="width: 200px"
        />
        <NButton :loading="loading" @click="refreshSources">
          {{ loading ? '刷新中...' : '刷新列表' }}
        </NButton>
      </NSpace>
    </template>

    <div class="ss-grid">
      <NCard
        v-for="s in filteredSources"
        :key="s.id"
        hoverable
        :content-style="{ padding: 0 }"
        class="ss-card"
        @click="selectAndStart(s)"
      >
        <div class="ss-thumb">
          <img v-if="s.thumbnail" :src="s.thumbnail" alt="" />
          <NEmpty v-else description="无预览" :show-icon="false" size="small" />
        </div>
        <div class="ss-name">
          <NEllipsis :tooltip="{ width: 300 }">{{ s.name }}</NEllipsis>
        </div>
      </NCard>

      <NEmpty
        v-if="!loading && filteredSources.length === 0"
        description="没找到可共享的窗口/屏幕"
        class="ss-empty"
      />
    </div>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="closeModal">取消</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NModal, NCard, NButton, NInput, NSpace, NEmpty, NEllipsis } from 'naive-ui'
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

const showModal = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const isElectron = computed(() => {
  const api = (window as any).electronAPI
  return !!api?.getCaptureSources && !!api?.setCaptureSource
})

const filteredSources = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return sources.value
  return sources.value.filter((s) => (s.name || '').toLowerCase().includes(k))
})

function handleShowChange(v: boolean) {
  emit('update:modelValue', v)
}

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

    const ok = await voice.toggleScreenShare()
    if (!ok) {
      if (api?.clearCaptureSource) await api.clearCaptureSource()
    }
  } catch (e) {
    console.log('selectAndStart failed:', e)
  }
}

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
.ss-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  max-height: 60vh;
  overflow: auto;
}

.ss-card {
  cursor: pointer;
}

.ss-thumb {
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--n-color-embedded);
}

.ss-thumb img {
  max-width: 100%;
  max-height: 100%;
  display: block;
}

.ss-name {
  padding: 10px 12px;
  font-size: 13px;
}

.ss-empty {
  grid-column: 1 / -1;
  padding: 20px 0;
}
</style>
