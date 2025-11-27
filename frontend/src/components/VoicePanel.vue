<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useWebSocket } from '../composables/useWebSocket'
import { useWebRTC } from '../composables/useWebRTC'
import type { VoiceUser } from '../types'

const chat = useChatStore()
const auth = useAuthStore()

const isConnected = ref(false)
const voiceUsers = ref<VoiceUser[]>([])
const audioElements = ref<Map<number, HTMLAudioElement>>(new Map())

let ws: ReturnType<typeof useWebSocket> | null = null
const webrtc = useWebRTC(playRemoteStream)

// Play remote audio stream
function playRemoteStream(userId: number, stream: MediaStream) {
  let audio = audioElements.value.get(userId)
  if (!audio) {
    audio = new Audio()
    audio.autoplay = true
    audioElements.value.set(userId, audio)
  }
  audio.srcObject = stream
  audio.play().catch(e => console.error('Audio play failed:', e))
}

// Stop remote audio
function stopRemoteAudio(userId: number) {
  const audio = audioElements.value.get(userId)
  if (audio) {
    audio.srcObject = null
    audioElements.value.delete(userId)
  }
}

async function joinVoice() {
  if (!chat.currentChannel) return

  const started = await webrtc.startLocalStream()
  if (!started) {
    alert('Failed to access microphone')
    return
  }

  ws = useWebSocket(`/ws/voice/${chat.currentChannel.id}`)

  ws.onMessage(async (data) => {
    switch (data.type) {
      case 'users':
        voiceUsers.value = data.users
        // Create offers for existing users
        for (const user of data.users) {
          if (user.id !== auth.user?.id) {
            const offer = await webrtc.createOffer(user.id, (candidate) => {
              ws?.send({
                type: 'ice-candidate',
                target_user_id: user.id,
                candidate: candidate.toJSON(),
              })
            })
            ws?.send({
              type: 'offer',
              target_user_id: user.id,
              sdp: offer,
            })
          }
        }
        break

      case 'user_joined':
        voiceUsers.value.push({ id: data.user_id, username: data.username, muted: false, deafened: false })
        break

      case 'user_left':
        voiceUsers.value = voiceUsers.value.filter((u) => u.id !== data.user_id)
        stopRemoteAudio(data.user_id)
        webrtc.closePeerConnection(data.user_id)
        break

      case 'offer':
        const answer = await webrtc.handleOffer(data.from_user_id, data.sdp, (candidate) => {
          ws?.send({
            type: 'ice-candidate',
            target_user_id: data.from_user_id,
            candidate: candidate.toJSON(),
          })
        })
        ws?.send({
          type: 'answer',
          target_user_id: data.from_user_id,
          sdp: answer,
        })
        break

      case 'answer':
        await webrtc.handleAnswer(data.from_user_id, data.sdp)
        break

      case 'ice-candidate':
        await webrtc.handleIceCandidate(data.from_user_id, data.candidate)
        break

      case 'user_mute':
        const mutedUser = voiceUsers.value.find((u) => u.id === data.user_id)
        if (mutedUser) mutedUser.muted = data.muted
        break

      case 'user_deafen':
        const deafenedUser = voiceUsers.value.find((u) => u.id === data.user_id)
        if (deafenedUser) deafenedUser.deafened = data.deafened
        break
    }
  })

  ws.connect()
  isConnected.value = true
}

function leaveVoice() {
  if (ws) {
    ws.disconnect()
    ws = null
  }
  // Clean up all audio elements
  audioElements.value.forEach((audio) => {
    audio.srcObject = null
  })
  audioElements.value.clear()
  webrtc.closeAllConnections()
  voiceUsers.value = []
  isConnected.value = false
}

function toggleMute() {
  const muted = webrtc.toggleMute()
  ws?.send({ type: 'mute', muted })
}

function toggleDeafen() {
  const deafened = webrtc.toggleDeafen()
  ws?.send({ type: 'deafen', deafened })
}

watch(
  () => chat.currentChannel,
  () => {
    if (isConnected.value) {
      leaveVoice()
    }
  }
)

onUnmounted(() => {
  leaveVoice()
})
</script>

<template>
  <div class="voice-panel">
    <div class="voice-header">
      <span class="channel-icon">🔊</span>
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
    </div>

    <div class="voice-content">
      <div v-if="!isConnected" class="voice-connect">
        <p>Click to join the voice channel</p>
        <button class="join-btn glow-effect" @click="joinVoice">Join Voice</button>
      </div>

      <div v-else class="voice-connected">
        <div class="voice-users">
          <div v-for="user in voiceUsers" :key="user.id" class="voice-user">
            <div class="user-avatar">{{ user.username.charAt(0).toUpperCase() }}</div>
            <span class="user-name">{{ user.username }}</span>
            <span v-if="user.muted" class="status-icon">🔇</span>
            <span v-if="user.deafened" class="status-icon">🔕</span>
          </div>
        </div>

        <div class="voice-controls">
          <button
            class="control-btn glow-effect"
            :class="{ active: webrtc.isMuted.value }"
            @click="toggleMute"
            title="Toggle Mute"
          >
            {{ webrtc.isMuted.value ? '🔇' : '🎤' }}
          </button>
          <button
            class="control-btn glow-effect"
            :class="{ active: webrtc.isDeafened.value }"
            @click="toggleDeafen"
            title="Toggle Deafen"
          >
            {{ webrtc.isDeafened.value ? '🔕' : '🔊' }}
          </button>
          <button class="control-btn disconnect glow-effect" @click="leaveVoice" title="Disconnect">
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

.join-btn:hover {
  transform: translateY(-2px);
  filter: brightness(1.1);
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
}

.user-name {
  flex: 1;
  color: var(--color-text-main);
}

.status-icon {
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
