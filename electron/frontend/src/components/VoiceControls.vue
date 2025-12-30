<script setup lang="ts">
import { computed } from 'vue'
import { useVoiceStore } from '../stores/voice'
import { useAuthStore } from '../stores/auth'
import { Volume2, VolumeX, Mic, MicOff, Phone, Crown, Monitor, MonitorOff } from 'lucide-vue-next'

const voice = useVoiceStore()
const auth = useAuthStore()

// Check if current user is the host
const isCurrentUserHost = computed(() => 
  voice.hostModeHostId === String(auth.user?.id)
)

// Disable host button if someone else is hosting
const hostButtonDisabled = computed(() => 
  voice.hostModeEnabled && !isCurrentUserHost.value
)

// Check if current user is the screen sharer
const isCurrentUserScreenSharer = computed(() =>
  voice.screenSharerId === String(auth.user?.id)
)

// Disable screen share button if someone else is sharing
const screenShareButtonDisabled = computed(() =>
  voice.screenShareLocked && !isCurrentUserScreenSharer.value && !voice.isScreenSharing
)

// Screen share button tooltip
const screenShareTooltip = computed(() => {
  if (voice.isScreenSharing) return '停止共享屏幕'
  if (screenShareButtonDisabled.value) return `${voice.screenSharerName || '其他用户'} 正在共享屏幕`
  return '共享屏幕'
})
</script>

<template>
  <div v-if="voice.isConnected" class="voice-controls">
    <div class="voice-status">
      <div class="status-info">
        <Volume2 class="status-icon" :size="16" />
        <div class="status-text">
          <span class="status-label">通话中</span>
          <span class="channel-name">{{ voice.currentVoiceChannel?.name }}</span>
        </div>
      </div>
    </div>
    <div class="control-buttons">
      <button
        class="control-btn"
        :class="{ active: voice.isMuted }"
        @click="voice.toggleMute()"
        :title="voice.isMuted ? '取消静音' : '静音'"
      >
        <MicOff v-if="voice.isMuted" :size="16" />
        <Mic v-else :size="16" />
      </button>
      <button
        class="control-btn"
        :class="{ active: voice.isDeafened }"
        @click="voice.toggleDeafen()"
        :title="voice.isDeafened ? '打开扬声器' : '关闭扬声器'"
      >
        <VolumeX v-if="voice.isDeafened" :size="16" />
        <Volume2 v-else :size="16" />
      </button>
      <button
        v-if="auth.isAdmin"
        class="control-btn"
        :class="{ 
          'host-mode-active': voice.hostModeEnabled && isCurrentUserHost,
          'host-mode-disabled': hostButtonDisabled 
        }"
        :disabled="hostButtonDisabled"
        @click="voice.toggleHostMode()"
        :title="hostButtonDisabled ? '其他用户正在主持' : (voice.hostModeEnabled ? '关闭主持人模式' : '开启主持人模式')"
      >
        <Crown :size="16" />
      </button>
      <button
        class="control-btn"
        :class="{ 
          'screen-share-active': voice.isScreenSharing,
          'screen-share-disabled': screenShareButtonDisabled
        }"
        :disabled="screenShareButtonDisabled"
        @click="voice.toggleScreenShare()"
        :title="screenShareTooltip"
      >
        <MonitorOff v-if="voice.isScreenSharing" :size="16" />
        <Monitor v-else :size="16" />
      </button>
      <button
        class="control-btn disconnect"
        @click="voice.disconnect()"
        title="离开语音"
      >
        <Phone :size="16" />
      </button>
    </div>
    <div v-if="voice.hostModeEnabled" class="host-mode-banner">
      <Crown :size="12" />
      <span>{{ voice.hostModeHostName }} 正在主持</span>
    </div>
  </div>
</template>

<style scoped>
.voice-controls {
  padding: 8px;
  background: rgba(16, 185, 129, 0.15);
  border-radius: var(--radius-md);
  margin-bottom: 8px;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.voice-status {
  margin-bottom: 8px;
}

.status-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-icon {
  font-size: 16px;
}

.status-text {
  display: flex;
  flex-direction: column;
}

.status-label {
  font-size: 12px;
  font-weight: 600;
  color: #10b981;
}

.channel-name {
  font-size: 11px;
  color: var(--color-text-muted);
}

.control-buttons {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.control-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--surface-glass);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 16px;
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: center;
}

.control-btn:hover {
  background: var(--surface-glass-strong);
  transform: scale(1.1);
}

.control-btn.active {
  background: var(--color-error);
  border-color: var(--color-error);
}

.control-btn.disconnect {
  background: var(--color-error);
  border-color: var(--color-error);
}

.control-btn.disconnect svg {
  transform: rotate(135deg);
}

.control-btn.disconnect:hover {
  filter: brightness(0.9);
}

.control-btn.host-mode-active {
  background: linear-gradient(135deg, #f59e0b, #d97706);
  border-color: #f59e0b;
  color: white;
}

.control-btn.host-mode-active:hover {
  filter: brightness(1.1);
}

.control-btn.host-mode-disabled {
  background: var(--surface-glass);
  opacity: 0.5;
  cursor: not-allowed;
}

.control-btn.host-mode-disabled:hover {
  transform: none;
  background: var(--surface-glass);
}

.control-btn.screen-share-active {
  background: linear-gradient(135deg, #10b981, #059669);
  border-color: #10b981;
  color: white;
}

.control-btn.screen-share-active:hover {
  filter: brightness(1.1);
}

.control-btn.screen-share-disabled {
  background: var(--surface-glass);
  opacity: 0.5;
  cursor: not-allowed;
}

.control-btn.screen-share-disabled:hover {
  transform: none;
  background: var(--surface-glass);
}

.host-mode-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 8px;
  padding: 4px 8px;
  background: rgba(245, 158, 11, 0.2);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: #f59e0b;
}
</style>
