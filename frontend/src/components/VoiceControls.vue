<script setup lang="ts">
import { useVoiceStore } from '../stores/voice'
import { Volume2, VolumeX, Mic, MicOff, Phone } from 'lucide-vue-next'

const voice = useVoiceStore()
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
        :title="voice.isDeafened ? '取消耳聋' : '耳聋'"
      >
        <VolumeX v-if="voice.isDeafened" :size="16" />
        <Volume2 v-else :size="16" />
      </button>
      <button
        class="control-btn disconnect"
        @click="voice.disconnect()"
        title="离开语音"
      >
        <Phone :size="16" />
      </button>
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
</style>
