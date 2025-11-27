<script setup lang="ts">
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'

const chat = useChatStore()
const voice = useVoiceStore()

async function joinVoice() {
  if (!chat.currentChannel) return
  const success = await voice.joinVoice(chat.currentChannel)
  if (!success && voice.error) {
    alert(voice.error)
  }
}
</script>

<template>
  <div class="voice-panel">
    <div class="voice-header">
      <span class="channel-icon">🔊</span>
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
      <span v-if="voice.isConnected" class="connection-mode connected">
        Connected
      </span>
    </div>

    <div class="voice-content">
      <div v-if="!voice.isConnected" class="voice-connect">
        <p>Click to join the voice channel</p>
        <button
          class="join-btn glow-effect"
          :disabled="voice.isConnecting"
          @click="joinVoice"
        >
          {{ voice.isConnecting ? 'Connecting...' : 'Join Voice' }}
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
            <div class="user-avatar">
              {{ participant.name.charAt(0).toUpperCase() }}
            </div>
            <span class="user-name">
              {{ participant.name }}
              <span v-if="participant.isLocal" class="local-tag">(You)</span>
            </span>
            <span v-if="participant.isMuted" class="status-icon">🔇</span>
            <span v-if="participant.isSpeaking" class="speaking-icon">🎙️</span>
          </div>
        </div>

        <div class="voice-controls">
          <button
            class="control-btn glow-effect"
            :class="{ active: voice.isMuted }"
            @click="voice.toggleMute()"
            title="Toggle Mute"
          >
            {{ voice.isMuted ? '🔇' : '🎤' }}
          </button>
          <button
            class="control-btn disconnect glow-effect"
            @click="voice.disconnect()"
            title="Disconnect"
          >
            📞
          </button>
        </div>
      </div>
    </div>
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
  justify-content: center;
  align-items: center;
  padding: 24px;
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
  align-items: center;
  padding: 8px 0;
  transition: all 0.2s ease;
}

.voice-user.speaking {
  background: rgba(16, 185, 129, 0.1);
  border-radius: 8px;
  padding: 8px;
  margin: 0 -8px;
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
  transform: rotate(135deg);
}

.control-btn.disconnect:hover {
  filter: brightness(0.9);
  transform: rotate(135deg) scale(1.1);
}
</style>
