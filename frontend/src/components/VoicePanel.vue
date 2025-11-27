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

let ws: ReturnType<typeof useWebSocket> | null = null
const webrtc = useWebRTC()

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
        <button class="join-btn" @click="joinVoice">Join Voice</button>
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
            class="control-btn"
            :class="{ active: webrtc.isMuted.value }"
            @click="toggleMute"
            title="Toggle Mute"
          >
            {{ webrtc.isMuted.value ? '🔇' : '🎤' }}
          </button>
          <button
            class="control-btn"
            :class="{ active: webrtc.isDeafened.value }"
            @click="toggleDeafen"
            title="Toggle Deafen"
          >
            {{ webrtc.isDeafened.value ? '🔕' : '🔊' }}
          </button>
          <button class="control-btn disconnect" @click="leaveVoice" title="Disconnect">
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
  border-bottom: 1px solid #202225;
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.2);
}

.channel-icon {
  font-size: 20px;
  margin-right: 8px;
}

.channel-name {
  font-weight: 600;
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
  color: #72767d;
  margin-bottom: 16px;
}

.join-btn {
  background: #3ba55c;
  color: #fff;
  border: none;
  padding: 12px 24px;
  font-size: 16px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.2s;
}

.join-btn:hover {
  background: #2d7d46;
}

.voice-connected {
  width: 100%;
  max-width: 400px;
}

.voice-users {
  background: #2f3136;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
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
  background: #5865f2;
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
  color: #dcddde;
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
  background: #4f545c;
  border: none;
  font-size: 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.control-btn:hover {
  background: #5d6269;
}

.control-btn.active {
  background: #ed4245;
}

.control-btn.disconnect {
  background: #ed4245;
  transform: rotate(135deg);
}

.control-btn.disconnect:hover {
  background: #c73639;
}
</style>
