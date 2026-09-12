import { ref } from 'vue'
import { Image, Video, Music, FileText, File } from 'lucide-vue-next'
import type { Attachment } from '../types'
import { useChatStore } from '../stores/chat'

/**
 * File attachment flow for the message composer: file picker + drag & drop,
 * per-file upload progress, and icon/size formatting for the pending list.
 */
export function useMessageAttachments() {
  const chat = useChatStore()

  const fileInput = ref<HTMLInputElement | null>(null)
  const pendingFiles = ref<File[]>([])
  const uploadedAttachments = ref<Attachment[]>([])
  const uploadProgress = ref<Map<string, number>>(new Map())
  const isUploading = ref(false)
  const isDragging = ref(false)

  function triggerFileSelect() {
    fileInput.value?.click()
  }

  function handleFileSelect(event: Event) {
    const target = event.target as HTMLInputElement
    if (target.files) {
      pendingFiles.value = [...pendingFiles.value, ...Array.from(target.files)]
      target.value = '' // Reset for same file selection
    }
  }

  function handleDragOver(event: DragEvent) {
    event.preventDefault()
    isDragging.value = true
  }

  function handleDragLeave(event: DragEvent) {
    event.preventDefault()
    isDragging.value = false
  }

  function handleDrop(event: DragEvent) {
    event.preventDefault()
    isDragging.value = false

    if (event.dataTransfer?.files) {
      pendingFiles.value = [...pendingFiles.value, ...Array.from(event.dataTransfer.files)]
    }
  }

  function removePendingFile(index: number) {
    pendingFiles.value.splice(index, 1)
  }

  function removeUploadedAttachment(index: number) {
    uploadedAttachments.value.splice(index, 1)
  }

  async function uploadFiles() {
    if (!chat.currentChannel || pendingFiles.value.length === 0) return

    isUploading.value = true
    const channelId = chat.currentChannel.id

    for (const file of pendingFiles.value) {
      const attachment = await chat.uploadFile(channelId, file, (progress) => {
        uploadProgress.value.set(file.name, progress)
      })
      if (attachment) {
        uploadedAttachments.value.push(attachment)
      }
      uploadProgress.value.delete(file.name)
    }

    pendingFiles.value = []
    isUploading.value = false
  }

  function formatFileSize(bytes: number) {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }

  function getFileIconComponent(file: File) {
    if (file.type.startsWith('image/')) return Image
    if (file.type.startsWith('video/')) return Video
    if (file.type.startsWith('audio/')) return Music
    if (file.type === 'application/pdf') return FileText
    return File
  }

  function getAttachmentIconComponent(att: Attachment) {
    if (att.content_type.startsWith('image/')) return Image
    if (att.content_type.startsWith('video/')) return Video
    if (att.content_type.startsWith('audio/')) return Music
    if (att.content_type === 'application/pdf') return FileText
    return File
  }

  return {
    fileInput,
    pendingFiles,
    uploadedAttachments,
    uploadProgress,
    isUploading,
    isDragging,
    triggerFileSelect,
    handleFileSelect,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    removePendingFile,
    removeUploadedAttachment,
    uploadFiles,
    formatFileSize,
    getFileIconComponent,
    getAttachmentIconComponent,
  }
}
