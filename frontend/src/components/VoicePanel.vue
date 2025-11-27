<script setup lang="ts">
import { ref, watch, onUnmounted, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useWebSocket } from '../composables/useWebSocket'
import { useWebRTC } from '../composables/useWebRTC'
import { useAudioRelay } from '../composables/useAudioRelay'
import type { VoiceUser } from '../types'

const chat = useChatStore()
const auth = useAuthStore()

const isConnected = ref(false)
const voiceUsers = ref<VoiceUser[]>([])
const audioElements = ref<Map<number, HTMLAudioElement>>(new Map())
const isRelayMode = ref(false)
const connectionStatus = ref<'connecting' | 'p2p' | 'relay'>('connecting')

let ws: ReturnType<typeof useWebSocket> | null = null

// Initialize WebRTC with connection failure callback
const webrtc = useWebRTC(playRemoteStream, handleP2PFailed)

// Initialize audio relay for server-mediated mode
const audioRelay = useAudioRelay()

// Display connection mode
const connectionModeText = computed(() => {
  if (!isConnected.value) return ''
  if (connectionStatus.value === 'connecting') return 'Connecting...'
  if (connectionStatus.value === 'p2p') return 'P2P'
  return 'Relay'
})

// Handle P2P connection failure - switch to relay mode
function handleP2PFailed(_userId: number) {
  if (!isRelayMode.value) {
    enableRelayMode()
  }
}

// Enable relay mode and notify server
async function enableRelayMode() {
  if (isRelayMode.value) return

  isRelayMode.value = true
  connectionStatus.value = 'relay'

  // Start audio capture for relay
  await audioRelay.startCapture((audioData) => {
    ws?.sendBinary(audioData)
  })

  // Notify server we're in relay mode
  ws?.send({ type: 'relay_mode', enabled: true })
}

// Play remote audio stream (P2P mode)
function playRemoteStream(userId: number, stream: MediaStream) {
  let audio = audioElements.value.get(userId)
  if (!audio) {
    audio = new Audio()
    audio.autoplay = true
    audioElements.value.set(userId, audio)
  }
  audio.srcObject = stream
  audio.play().catch((e) => console.error('Audio play failed:', e))
}

// Stop remote audio
function stopRemoteAudio(userId: number) {
  const audio = audioElements.value.get(userId)
  if (audio) {
    audio.srcObject = null
    audioElements.value.delete(userId)
  }
  audioRelay.stopRemoteAudio(userId)
}

async function joinVoice() {
  if (!chat.currentChannel) return

  // Start local stream for WebRTC P2P attempts
  const started = await webrtc.startLocalStream()
  if (!started) {
    alert('Failed to access microphone')
    return
  }

  connectionStatus.value = 'connecting'
  ws = useWebSocket(`/ws/voice/${chat.currentChannel.id}`)

  // Handle binary audio data (relay mode)
  ws.onBinaryMessage(async (blob: Blob) => {
    const buffer = await blob.arrayBuffer()
    // First 4 bytes are user_id (big endian)
    const view = new DataView(buffer)
    const userId = view.getUint32(0, false)
    const audioData = buffer.slice(4)

    if (userId !== auth.user?.id) {
      audioRelay.playRemoteAudio(userId, audioData)
    }
  })

  ws.onMessage(async (data) => {
    switch (data.type) {
      case 'users':
        voiceUsers.value = data.users
        // Create offers for existing users (attempt P2P)
        for (const user of data.users) {
          if (user.id !== auth.user?.id) {
            // If user is already in relay mode, skip P2P attempt
            if (user.relay_mode) {
              if (!isRelayMode.value) {
                enableRelayMode()
              }
              continue
            }
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
        voiceUsers.value.push({
          id: data.user_id,
          username: data.username,
          muted: false,
          deafened: false,
        })
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
        // Check if P2P succeeded after a moment
        setTimeout(() => {
          if (webrtc.hasAnyP2PConnection()) {
            connectionStatus.value = 'p2p'
          }
        }, 1000)
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

      case 'user_relay_mode':
        // Another user switched to relay mode - we may need to as well
        if (data.relay_mode && !isRelayMode.value) {
          enableRelayMode()
        }
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
  audioRelay.cleanup()
  voiceUsers.value = []
  isConnected.value = false
  isRelayMode.value = false
  connectionStatus.value = 'connecting'
}

function toggleMute() {
  const muted = isRelayMode.value ? audioRelay.toggleMute() : webrtc.toggleMute()
  ws?.send({ type: 'mute', muted })
}

function toggleDeafen() {
  const deafened = isRelayMode.value ? audioRelay.toggleDeafen() : webrtc.toggleDeafen()
  ws?.send({ type: 'deafen', deafened })
}

// Current mute/deafen state based on mode
const isMuted = computed(() => (isRelayMode.value ? audioRelay.isMuted.value : webrtc.isMuted.value))
const isDeafened = computed(() =>
  isRelayMode.value ? audioRelay.isDeafened.value : webrtc.isDeafened.value
)

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
      <span v-if="isConnected" class="connection-mode" :class="connectionStatus">
        {{ connectionModeText }}
      </span>
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
            :class="{ active: isMuted }"
            @click="toggleMute"
            title="Toggle Mute"
          >
            {{ isMuted ? '🔇' : '🎤' }}
          </button>
          <button
            class="control-btn glow-effect"
            :class="{ active: isDeafened }"
            @click="toggleDeafen"
            title="Toggle Deafen"
          >
            {{ isDeafened ? '🔕' : '🔊' }}
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

.connection-mode {
  margin-left: auto;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.connection-mode.connecting {
  background: var(--color-warning, #f59e0b);
  color: #000;
}

.connection-mode.p2p {
  background: var(--color-success, #10b981);
  color: #fff;
}

.connection-mode.relay {
  background: var(--color-primary, #6366f1);
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
