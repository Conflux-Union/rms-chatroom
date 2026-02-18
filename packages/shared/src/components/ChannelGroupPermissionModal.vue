<template>
  <div v-if="isOpen" class="modal-overlay" @click.self="handleClose">
    <div class="modal-content">
      <div class="modal-header">
        <h2>{{ groupName }} - 频道组权限设置</h2>
        <button class="close-btn" @click="handleClose">✕</button>
      </div>

      <div class="modal-body">
        <InternalLevelPermissionSettings
          v-model="groupVisibilityLevel"
          title="频道组可见性权限"
          description="只有达到此权限等级的用户才能看到此频道组及其内的频道"
          :maxLevel="userMaxLevel"
          :serverValue="initialMinServerLevel"
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

interface Props {
  isOpen: boolean
  serverId: number
  groupId: number
  groupName: string
  initialMinServerLevel?: number
}

const props = withDefaults(defineProps<Props>(), {
  initialMinServerLevel: 1
})

const auth = useAuthStore()

const emit = defineEmits<{
  'close': []
  'save': [value: { minServerLevel: number }]
}>()

const groupVisibilityLevel = ref(props.initialMinServerLevel)

const isSaving = ref(false)

const API_BASE = import.meta.env.VITE_API_BASE || ''

// 获取用户的最高权限等级
const userMaxLevel = computed(() => {
  return auth.user?.permission_level || 1
})

// Update visibility level when props change (but not during save)
watch(() => props.initialMinServerLevel, (val) => {
  if (val !== undefined && !isSaving.value) {
    groupVisibilityLevel.value = val
  }
}, { immediate: true })

function handleClose() {
  emit('close')
}

async function handleSave() {
  if (isSaving.value) return

  try {
    isSaving.value = true

    // Call API to update channel group permissions
    const response = await axios.patch(
      `${API_BASE}/api/servers/${props.serverId}/channel-groups/${props.groupId}`,
      {
        min_server_level: groupVisibilityLevel.value
      }
    )

    // Only emit and close if save was successful
    emit('save', {
      minServerLevel: groupVisibilityLevel.value
    })

    handleClose()
  } catch (error) {
    console.error('Failed to update channel group permissions:', error)
    if (axios.isAxiosError(error)) {
      const message = error.response?.data?.detail || error.message
      alert(`保存失败: ${message}`)
    } else {
      alert('保存失败，请重试')
    }
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
  border: 2px solid var(--color-border);
  border-radius: 12px;
  max-width: 550px;
  width: 90%;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
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
  background: transparent;
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
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.info-text {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 16px;
  margin-bottom: 0;
  line-height: 1.5;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 20px 24px;
  border-top: 2px solid var(--color-border);
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
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
