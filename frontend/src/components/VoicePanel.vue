<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'
import { Volume2, Mic, MicOff, Phone, AlertTriangle } from 'lucide-vue-next'

const chat = useChatStore()
const voice = useVoiceStore()

onMounted(() => {
  voice.enumerateDevices()
})

// Volume warning dialog state
const showVolumeWarning = ref(false)
const pendingVolumeParticipant = ref<string | null>(null)
const pendingVolumeValue = ref(100)

async function joinVoice() {
  if (!chat.currentChannel) return
  const success = await voice.joinVoice(chat.currentChannel)
  if (!success && voice.error) {
    alert(voice.error)
  }
}

function handleVolumeChange(participantId: string, event: Event) {
  const target = event.target as HTMLInputElement
  const newVolume = parseInt(target.value, 10)
  
  const result = voice.setUserVolume(participantId, newVolume)
  
  if (result.showWarning) {
    // Block at 100% and show warning
    target.value = '100'
    pendingVolumeParticipant.value = participantId
    pendingVolumeValue.value = newVolume
    showVolumeWarning.value = true
  }
}

function confirmVolumeWarning() {
  if (pendingVolumeParticipant.value) {
    voice.acknowledgeVolumeWarning(pendingVolumeParticipant.value)
    voice.setUserVolume(pendingVolumeParticipant.value, pendingVolumeValue.value, true)
  }
  closeVolumeWarning()
}

function closeVolumeWarning() {
  showVolumeWarning.value = false
  pendingVolumeParticipant.value = null
  pendingVolumeValue.value = 100
}
</script>

<template>
  <div class="voice-panel">
    <div class="voice-header">
      <Volume2 class="channel-icon" :size="20" />
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
      <span v-if="voice.isConnected" class="connection-mode connected">
        已连接
      </span>
    </div>

    <div class="voice-content">
      <!-- Device Selection (always visible) -->
      <div class="device-selection">
        <div class="device-group">
          <label class="device-label"><Mic :size="14" /> 输入设备</label>
          <select
            class="device-select"
            :value="voice.selectedAudioInput"
            @change="voice.setAudioInputDevice(($event.target as HTMLSelectElement).value)"
          >
            <option value="">系统默认</option>
            <option
              v-for="device in voice.audioInputDevices"
              :key="device.deviceId"
              :value="device.deviceId"
            >
              {{ device.label }}
            </option>
          </select>
        </div>
        <div class="device-group">
          <label class="device-label"><Volume2 :size="14" /> 输出设备</label>
          <select
            class="device-select"
            :value="voice.selectedAudioOutput"
            @change="voice.setAudioOutputDevice(($event.target as HTMLSelectElement).value)"
          >
            <option value="">系统默认</option>
            <option
              v-for="device in voice.audioOutputDevices"
              :key="device.deviceId"
              :value="device.deviceId"
            >
              {{ device.label }}
            </option>
          </select>
        </div>
      </div>

      <div v-if="!voice.isConnected" class="voice-connect">
        <p>点击加入语音频道</p>
        <button
          class="join-btn glow-effect"
          :disabled="voice.isConnecting"
          @click="joinVoice"
        >
          {{ voice.isConnecting ? '连接中...' : '加入语音' }}
        </button>
      </div>

      <div v-else class="voice-connected">
        <div class="voice-users">
          <div
            v-for="participant in voice.participants"
            :key="participant.id"
            class="voice-user"
            :class="{ speaking: participant.isSpeaking }"
          >
            <div class="user-info">
              <div class="user-avatar">
                {{ participant.name.charAt(0).toUpperCase() }}
              </div>
              <span class="user-name">
                {{ participant.name }}
                <span v-if="participant.isLocal" class="local-tag">(你)</span>
              </span>
              <MicOff v-if="participant.isMuted" class="status-icon" :size="14" />
              <Mic v-if="participant.isSpeaking" class="speaking-icon" :size="14" />
            </div>
            <div v-if="!participant.isLocal" class="volume-control">
              <Volume2 class="volume-icon" :size="14" />
              <input
                type="range"
                class="volume-slider"
                min="0"
                max="300"
                :value="participant.volume"
                @input="handleVolumeChange(participant.id, $event)"
              />
              <span class="volume-value">{{ participant.volume }}%</span>
            </div>
          </div>
        </div>

        <div class="voice-controls">
          <button
            class="control-btn glow-effect"
            :class="{ active: voice.isMuted }"
            @click="voice.toggleMute()"
            title="切换静音"
          >
            <MicOff v-if="voice.isMuted" :size="20" />
            <Mic v-else :size="20" />
          </button>
          <button
            class="control-btn disconnect glow-effect"
            @click="voice.disconnect()"
            title="断开连接"
          >
            <Phone :size="20" />
          </button>
        </div>
      </div>
    </div>

    <!-- Volume Warning Dialog -->
    <Teleport to="body">
      <div v-if="showVolumeWarning" class="volume-warning-overlay" @click.self="closeVolumeWarning">
        <div class="volume-warning-dialog">
          <AlertTriangle class="warning-icon" :size="48" />
          <h3 class="warning-title">高音量警告</h3>
          <p class="warning-message">
            高音量可能损害您的听力和音频设备。
          </p>
          <div class="warning-actions">
            <button class="warning-btn cancel" @click="closeVolumeWarning">取消</button>
            <button class="warning-btn confirm" @click="confirmVolumeWarning">我已了解</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.voice-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.voice-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
}

.channel-icon {
  font-size: 20px;
  margin-right: 8px;
}

.channel-name {
  font-weight: 600;
  color: var(--color-text-main);
}

.connection-mode {
  margin-left: auto;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.connection-mode.connected {
  background: var(--color-success, #10b981);
  color: #fff;
}

.voice-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  align-items: center;
  padding: 24px;
}

.device-selection {
  width: 100%;
  max-width: 400px;
  margin-bottom: 24px;
}

.device-group {
  margin-bottom: 12px;
}

.device-group:last-child {
  margin-bottom: 0;
}

.device-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-main);
  margin-bottom: 8px;
}

.device-select {
  width: 100%;
  padding: 12px 16px;
  font-size: 14px;
  color: var(--color-text-main);
  background: var(--surface-glass-input);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12'%3E%3Cpath fill='%23666' d='M6 8L1 3h10z'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 14px center;
  padding-right: 36px;
}

.device-select:hover {
  background: var(--surface-glass-input-focus);
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}

.device-select:focus {
  outline: none;
  background: var(--surface-glass-input-focus);
  border-color: rgba(252, 121, 97, 0.5);
  box-shadow: 0 0 0 3px rgba(252, 121, 97, 0.15);
  transform: translateY(-1px);
}

.device-select option {
  background: #fff;
  color: var(--color-text-main);
  padding: 8px;
}

.voice-connect {
  text-align: center;
}

.voice-connect p {
  color: var(--color-text-muted);
  margin-bottom: 16px;
}

.join-btn {
  background: var(--color-gradient-primary);
  color: #fff;
  border: none;
  padding: 14px 28px;
  font-size: 16px;
  font-weight: 600;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-normal);
  box-shadow: var(--shadow-glow);
}

.join-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  filter: brightness(1.1);
}

.join-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.voice-connected {
  width: 100%;
  max-width: 400px;
}

.voice-users {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.voice-user {
  display: flex;
  flex-direction: column;
  padding: 8px 0;
  transition: all 0.2s ease;
}

.voice-user.speaking {
  background: rgba(16, 185, 129, 0.1);
  border-radius: 8px;
  padding: 8px;
  margin: 0 -8px;
}

.user-info {
  display: flex;
  align-items: center;
  width: 100%;
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--color-gradient-primary);
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: 600;
  color: #fff;
  margin-right: 12px;
  font-size: 14px;
  transition: box-shadow 0.2s ease;
}

.voice-user.speaking .user-avatar {
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.5);
}

.user-name {
  flex: 1;
  color: var(--color-text-main);
}

.local-tag {
  font-size: 12px;
  color: var(--color-text-muted);
}

.status-icon,
.speaking-icon {
  margin-left: 8px;
  font-size: 14px;
}

.volume-control {
  display: flex;
  align-items: center;
  margin-top: 6px;
  padding-left: 44px;
  gap: 8px;
}

.volume-icon {
  font-size: 14px;
}

.volume-slider {
  flex: 1;
  height: 4px;
  -webkit-appearance: none;
  appearance: none;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
  cursor: pointer;
}

.volume-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--color-primary, #6366f1);
  cursor: pointer;
  transition: transform 0.15s ease;
}

.volume-slider::-webkit-slider-thumb:hover {
  transform: scale(1.2);
}

.volume-slider::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border: none;
  border-radius: 50%;
  background: var(--color-primary, #6366f1);
  cursor: pointer;
}

.volume-value {
  font-size: 12px;
  color: var(--color-text-muted);
  min-width: 40px;
  text-align: right;
}

.voice-controls {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.control-btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--surface-glass);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 20px;
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
  transform: scale(1.1);
}

/* Volume Warning Dialog Styles */
.volume-warning-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
  backdrop-filter: blur(4px);
}

.volume-warning-dialog {
  background: var(--surface-glass-strong, rgba(30, 30, 40, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: var(--radius-lg, 16px);
  padding: 24px;
  max-width: 400px;
  text-align: center;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.4);
}

.warning-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.warning-title {
  color: var(--color-warning, #f59e0b);
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 12px 0;
}

.warning-message {
  color: var(--color-text-muted, #9ca3af);
  font-size: 14px;
  line-height: 1.5;
  margin: 0 0 20px 0;
}

.warning-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.warning-btn {
  padding: 10px 20px;
  border-radius: var(--radius-md, 8px);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
}

.warning-btn.cancel {
  background: rgba(255, 255, 255, 0.1);
  color: var(--color-text-main, #fff);
}

.warning-btn.cancel:hover {
  background: rgba(255, 255, 255, 0.2);
}

.warning-btn.confirm {
  background: var(--color-warning, #f59e0b);
  color: #000;
}

.warning-btn.confirm:hover {
  filter: brightness(1.1);
}

/* Mobile Responsive */
@media (max-width: 768px) {
  .voice-content {
    padding: 16px;
  }

  .device-selection {
    max-width: 100%;
  }

  .voice-connected {
    max-width: 100%;
  }

  .join-btn {
    padding: 12px 24px;
    font-size: 15px;
  }

  .user-avatar {
    width: 28px;
    height: 28px;
    font-size: 12px;
  }

  .volume-control {
    padding-left: 40px;
  }

  .control-btn {
    width: 44px;
    height: 44px;
  }
}
</style>
