<template>
  <div v-if="isOpen" class="modal-overlay" @click.self="handleClose">
    <div class="modal-content">
      <div class="modal-header">
        <h2>{{ channelName }} - 频道权限设置</h2>
        <button class="close-btn" @click="handleClose">✕</button>
      </div>

      <div class="modal-body">
        <InternalLevelPermissionSettings
          v-model="channelVisibilityLevel"
          title="频道可见性权限"
          description="只有达到此权限等级的用户才能看到此频道"
          :maxLevel="userMaxLevel"
          :serverValue="initialPermissions?.visibilityMinServerLevel"
        />
        <InternalLevelPermissionSettings
          v-model="channelSpeakLevel"
          title="频道发言权限"
          description="只有达到此权限等级的用户才能在此频道发言"
          :maxLevel="userMaxLevel"
          :serverValue="initialPermissions?.speakMinServerLevel"
        />
      </div>

      <div class="modal-footer">
        <n-space justify="end" size="medium">
          <n-button secondary @click="handleClose">取消</n-button>
          <n-button type="primary" :disabled="isSaving" @click="handleSave">
            {{ isSaving ? '保存中...' : '保存' }}
          </n-button>
        </n-space>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import InternalLevelPermissionSettings from './InternalLevelPermissionSettings.vue'
import axios from 'axios'

interface ChannelPermissions {
  visibilityMinServerLevel: number
  speakMinServerLevel: number
}

interface Props {
  isOpen: boolean
  serverId: number
  channelId: number
  channelName: string
  initialPermissions?: Partial<ChannelPermissions>
}

const props = withDefaults(defineProps<Props>(), {
  initialPermissions: () => ({
    visibilityMinServerLevel: 1,
    speakMinServerLevel: 1
  })
})

const auth = useAuthStore()

const emit = defineEmits<{
  'close': []
  'save': [value: ChannelPermissions]
}>()

const channelVisibilityLevel = ref(props.initialPermissions?.visibilityMinServerLevel || 1)
const channelSpeakLevel = ref(props.initialPermissions?.speakMinServerLevel || 1)

const isSaving = ref(false)
const API_BASE = import.meta.env.VITE_API_BASE || ''

// 获取用户的最高权限等级
const userMaxLevel = computed(() => {
  return auth.user?.permission_level || 1
})

// Watch for initial permissions change (but not during save)
watch(() => props.initialPermissions, (newVal) => {
  if (newVal && !isSaving.value) {
    channelVisibilityLevel.value = newVal.visibilityMinServerLevel || 1
    channelSpeakLevel.value = newVal.speakMinServerLevel || 1
  }
}, { immediate: true })

const handleClose = () => {
  emit('close')
}

const handleSave = async () => {
  isSaving.value = true
  try {
    await axios.patch(
      `${API_BASE}/api/servers/${props.serverId}/channels/${props.channelId}`,
      {
        visibility_min_server_level: channelVisibilityLevel.value,
        speak_min_server_level: channelSpeakLevel.value
      },
      {
        headers: {
          Authorization: `Bearer ${localStorage.getItem('token')}`
        }
      }
    )
    
    emit('save', {
      visibilityMinServerLevel: channelVisibilityLevel.value,
      speakMinServerLevel: channelSpeakLevel.value
    })
    emit('close')
  } catch (error) {
    console.error('Failed to save channel permissions:', error)
    alert('保存权限设置失败')
  } finally {
    isSaving.value = false
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.modal-content {
  background: #ffffff;
  border-radius: 12px;
  width: 90%;
  max-width: 650px;
  max-height: 90vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
  border: 1px solid var(--color-border);
  animation: slideUp 0.3s ease-out;
}

@keyframes slideUp {
  from {
    transform: translateY(20px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px;
  border-bottom: 2px solid var(--color-border);
  background: linear-gradient(135deg, var(--color-background) 0%, var(--color-background-soft) 100%);
}

.modal-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text-main);
  letter-spacing: 0.5px;
}

.close-btn {
  background: none;
  border: none;
  font-size: 28px;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  transition: all 0.2s;
}

.close-btn:hover {
  background: var(--color-background-soft);
  color: var(--color-text-main);
  transform: rotate(90deg);
}

.modal-body {
  padding: 24px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.info-text {
  margin-top: 16px;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
}

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 20px;
  border-top: 1px solid var(--color-border);
  justify-content: flex-end;
  background: var(--color-background-soft);
}

.btn {
  padding: 10px 18px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary {
  background: var(--color-background);
  color: var(--color-text-main);
  border: 2px solid var(--color-border);
}

.btn-secondary:hover {
  border-color: var(--color-accent);
  color: var(--color-accent);
}

.btn-primary {
  background: var(--color-gradient-primary);
  color: white;
  border: none;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.2);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn {
  padding: 8px 16px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-primary {
  background: var(--color-accent);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  background: var(--color-background-soft);
  color: var(--color-text-main);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover {
  background: var(--color-background);
}
</style>
