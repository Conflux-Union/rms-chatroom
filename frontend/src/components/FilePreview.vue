<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import type { Attachment } from '../types'
import { useAuthStore } from '../stores/auth'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8000'

const props = defineProps<{
  attachment: Attachment
}>()

const auth = useAuthStore()
const showLightbox = ref(false)
const blobUrl = ref<string | null>(null)
const isLoading = ref(true)

const isImage = computed(() => props.attachment.content_type.startsWith('image/'))
const isVideo = computed(() => props.attachment.content_type.startsWith('video/'))
const isAudio = computed(() => props.attachment.content_type.startsWith('audio/'))
const isPdf = computed(() => props.attachment.content_type === 'application/pdf')

const fileUrl = computed(() => `${API_BASE}${props.attachment.url}`)
const inlineUrl = computed(() => `${fileUrl.value}?inline=1`)

// Fetch file as blob with auth header
async function loadBlobUrl() {
  if (!isImage.value && !isVideo.value && !isAudio.value) return
  
  isLoading.value = true
  try {
    const res = await fetch(inlineUrl.value, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    if (res.ok) {
      const blob = await res.blob()
      blobUrl.value = URL.createObjectURL(blob)
    }
  } catch (e) {
    console.error('Failed to load file:', e)
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  loadBlobUrl()
})

onUnmounted(() => {
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value)
  }
})

watch(() => props.attachment.id, () => {
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value)
    blobUrl.value = null
  }
  loadBlobUrl()
})

const fileIcon = computed(() => {
  if (isImage.value) return '🖼️'
  if (isVideo.value) return '🎬'
  if (isAudio.value) return '🎵'
  if (isPdf.value) return '📄'
  if (props.attachment.content_type.includes('zip') || props.attachment.content_type.includes('rar')) return '📦'
  if (props.attachment.content_type.includes('document') || props.attachment.content_type.includes('word')) return '📝'
  if (props.attachment.content_type.includes('sheet') || props.attachment.content_type.includes('excel')) return '📊'
  return '📎'
})

const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const downloadFile = () => {
  const link = document.createElement('a')
  link.href = fileUrl.value
  link.download = props.attachment.filename
  // Add auth header via fetch and blob for download
  fetch(fileUrl.value, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })
    .then(res => res.blob())
    .then(blob => {
      const url = URL.createObjectURL(blob)
      link.href = url
      link.click()
      URL.revokeObjectURL(url)
    })
}

const openPdf = () => {
  window.open(inlineUrl.value, '_blank')
}
</script>

<template>
  <div class="file-preview">
    <!-- Image preview -->
    <div v-if="isImage" class="image-preview">
      <div v-if="isLoading" class="loading-placeholder">加载中...</div>
      <img v-else-if="blobUrl" :src="blobUrl" :alt="attachment.filename" @click="showLightbox = true" />
    </div>

    <!-- Video preview -->
    <div v-else-if="isVideo" class="video-preview">
      <div v-if="isLoading" class="loading-placeholder">加载中...</div>
      <video v-else-if="blobUrl" controls preload="metadata">
        <source :src="blobUrl" :type="attachment.content_type" />
        Your browser does not support video playback.
      </video>
    </div>

    <!-- Audio preview -->
    <div v-else-if="isAudio" class="audio-preview">
      <div class="audio-info">
        <span class="file-icon">{{ fileIcon }}</span>
        <span class="file-name">{{ attachment.filename }}</span>
      </div>
      <div v-if="isLoading" class="loading-placeholder">加载中...</div>
      <audio v-else-if="blobUrl" controls preload="metadata">
        <source :src="blobUrl" :type="attachment.content_type" />
        Your browser does not support audio playback.
      </audio>
    </div>

    <!-- PDF preview -->
    <div v-else-if="isPdf" class="file-card" @click="openPdf">
      <span class="file-icon">{{ fileIcon }}</span>
      <div class="file-info">
        <span class="file-name">{{ attachment.filename }}</span>
        <span class="file-size">{{ formatSize(attachment.size) }}</span>
      </div>
      <span class="open-hint">点击在新窗口打开</span>
    </div>

    <!-- Other files -->
    <div v-else class="file-card" @click="downloadFile">
      <span class="file-icon">{{ fileIcon }}</span>
      <div class="file-info">
        <span class="file-name">{{ attachment.filename }}</span>
        <span class="file-size">{{ formatSize(attachment.size) }}</span>
      </div>
      <span class="download-hint">点击下载</span>
    </div>

    <!-- Image lightbox -->
    <Teleport to="body">
      <div v-if="showLightbox && isImage && blobUrl" class="lightbox" @click="showLightbox = false">
        <img :src="blobUrl" :alt="attachment.filename" />
        <button class="close-btn" @click.stop="showLightbox = false">×</button>
        <button class="download-btn" @click.stop="downloadFile">下载</button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.file-preview {
  margin-top: 8px;
}

.loading-placeholder {
  padding: 20px;
  background: var(--surface-glass);
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  font-size: 14px;
  text-align: center;
}

.image-preview {
  max-width: 400px;
  cursor: pointer;
  border-radius: var(--radius-md);
  overflow: hidden;
}

.image-preview img {
  max-width: 100%;
  max-height: 300px;
  object-fit: contain;
  display: block;
  transition: transform 0.2s;
}

.image-preview:hover img {
  transform: scale(1.02);
}

.video-preview {
  max-width: 500px;
}

.video-preview video {
  max-width: 100%;
  max-height: 400px;
  border-radius: var(--radius-md);
  background: #000;
}

.audio-preview {
  background: var(--surface-glass);
  border-radius: var(--radius-md);
  padding: 12px;
  max-width: 400px;
}

.audio-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.audio-preview audio {
  width: 100%;
  height: 32px;
}

.file-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--surface-glass);
  border-radius: var(--radius-md);
  cursor: pointer;
  max-width: 400px;
  transition: all var(--transition-fast);
}

.file-card:hover {
  background: var(--surface-glass-hover);
  transform: translateY(-1px);
}

.file-icon {
  font-size: 24px;
}

.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.file-name {
  color: var(--color-text-main);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 12px;
  color: var(--color-text-muted);
}

.download-hint,
.open-hint {
  font-size: 12px;
  color: var(--color-accent);
  white-space: nowrap;
}

/* Lightbox */
.lightbox {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  cursor: zoom-out;
}

.lightbox img {
  max-width: 90vw;
  max-height: 90vh;
  object-fit: contain;
  cursor: default;
}

.close-btn {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 40px;
  height: 40px;
  border: none;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  font-size: 24px;
  border-radius: 50%;
  cursor: pointer;
  transition: background 0.2s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

.download-btn {
  position: absolute;
  bottom: 20px;
  right: 20px;
  padding: 8px 16px;
  border: none;
  background: var(--color-accent);
  color: white;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 14px;
  transition: opacity 0.2s;
}

.download-btn:hover {
  opacity: 0.9;
}

/* Mobile */
@media (max-width: 768px) {
  .image-preview {
    max-width: 100%;
  }

  .video-preview {
    max-width: 100%;
  }

  .file-card {
    max-width: 100%;
  }

  .audio-preview {
    max-width: 100%;
  }
}
</style>
