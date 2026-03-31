<template>
  <div v-if="isOpen" class="modal-overlay" @click.self="handleClose">
    <div class="modal-content">
      <div class="modal-header">
        <h2>{{ channelName }} - 频道权限设置</h2>
        <button class="close-btn" @click="handleClose">&times;</button>
      </div>

      <div class="modal-body">
        <DualPermissionSettings
          v-model:permLevel="channelPermMinLevel"
          v-model:groupLevel="channelMinLevel"
          v-model:logicOperator="channelLogicOperator"
          title="可见性权限"
          description="用户需满足此权限要求才能看到此频道"
          :maxLevel="userMaxLevel"
          :serverPermLevel="initialPermissions?.permMinLevel"
          :serverGroupLevel="initialPermissions?.minLevel"
          :serverOperator="initialPermissions?.logicOperator"
        />
        <DualPermissionSettings
          v-model:permLevel="channelSpeakPermMinLevel"
          v-model:groupLevel="channelSpeakMinLevel"
          v-model:logicOperator="channelSpeakLogicOperator"
          title="发言权限"
          description="用户需满足此权限要求才能在此频道发言"
          :maxLevel="userMaxLevel"
          :serverPermLevel="initialPermissions?.speakPermMinLevel"
          :serverGroupLevel="initialPermissions?.speakMinLevel"
          :serverOperator="initialPermissions?.speakLogicOperator"
        />
      </div>

      <div class="modal-footer">
        <button class="btn btn-secondary" @click="handleClose">取消</button>
        <button class="btn btn-primary" @click="handleSave" :disabled="isSaving">
          {{ isSaving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import DualPermissionSettings from './DualPermissionSettings.vue'
import axios from 'axios'

interface ChannelPermissions {
  minLevel: number
  permMinLevel: number
  logicOperator: 'AND' | 'OR'
  speakMinLevel: number
  speakPermMinLevel: number
  speakLogicOperator: 'AND' | 'OR'
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
    minLevel: 0,
    permMinLevel: 0,
    logicOperator: 'AND' as const,
    speakMinLevel: 0,
    speakPermMinLevel: 0,
    speakLogicOperator: 'AND' as const
  })
})

const auth = useAuthStore()

const emit = defineEmits<{
  'close': []
  'save': [value: ChannelPermissions]
}>()

const channelMinLevel = ref(props.initialPermissions?.minLevel || 0)
const channelPermMinLevel = ref(props.initialPermissions?.permMinLevel || 0)
const channelLogicOperator = ref<'AND' | 'OR'>(props.initialPermissions?.logicOperator || 'AND')
const channelSpeakMinLevel = ref(props.initialPermissions?.speakMinLevel || 0)
const channelSpeakPermMinLevel = ref(props.initialPermissions?.speakPermMinLevel || 0)
const channelSpeakLogicOperator = ref<'AND' | 'OR'>(props.initialPermissions?.speakLogicOperator || 'AND')

const isSaving = ref(false)
const API_BASE = import.meta.env.VITE_API_BASE || ''

const userMaxLevel = computed(() => {
  return auth.user?.permission_level || 1
})

watch(() => props.isOpen, (open) => {
  if (open && props.initialPermissions) {
    channelMinLevel.value = props.initialPermissions.minLevel || 0
    channelPermMinLevel.value = props.initialPermissions.permMinLevel || 0
    channelLogicOperator.value = props.initialPermissions.logicOperator || 'AND'
    channelSpeakMinLevel.value = props.initialPermissions.speakMinLevel || 0
    channelSpeakPermMinLevel.value = props.initialPermissions.speakPermMinLevel || 0
    channelSpeakLogicOperator.value = props.initialPermissions.speakLogicOperator || 'AND'
  }
})

const handleClose = () => {
  emit('close')
}

const handleSave = async () => {
  isSaving.value = true
  try {
    await axios.patch(
      `${API_BASE}/api/servers/${props.serverId}/channels/${props.channelId}`,
      {
        min_level: channelMinLevel.value,
        perm_min_level: channelPermMinLevel.value,
        logic_operator: channelLogicOperator.value,
        speak_min_level: channelSpeakMinLevel.value,
        speak_perm_min_level: channelSpeakPermMinLevel.value,
        speak_logic_operator: channelSpeakLogicOperator.value
      },
      { headers: { Authorization: `Bearer ${auth.accessToken}` } }
    )
    emit('save', {
      minLevel: channelMinLevel.value,
      permMinLevel: channelPermMinLevel.value,
      logicOperator: channelLogicOperator.value,
      speakMinLevel: channelSpeakMinLevel.value,
      speakPermMinLevel: channelSpeakPermMinLevel.value,
      speakLogicOperator: channelSpeakLogicOperator.value
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
  from { opacity: 0; }
  to { opacity: 1; }
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
  from { transform: translateY(20px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
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

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 20px 24px;
  border-top: 2px solid var(--color-border);
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

.btn-primary {
  background: var(--color-gradient-primary);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.2);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
</style>
