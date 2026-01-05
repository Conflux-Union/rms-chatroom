<script setup lang="ts">
import { ref, onMounted, onUnmounted, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { Room, RoomEvent, Track, RemoteParticipant, AudioPresets } from 'livekit-client'
import { Volume2, VolumeX, Mic, MicOff, Phone, AlertCircle, UserPlus, Crown } from 'lucide-vue-next'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const route = useRoute()
const token = route.params.token as string

// Page states
type PageState = 'loading' | 'invalid' | 'form' | 'connecting' | 'connected' | 'ended'
const pageState = ref<PageState>('loading')
const errorMessage = ref('')

// Invite info
const channelName = ref('')
const serverName = ref('')
const channelId = ref<number | null>(null)

// User input
const username = ref('')

// Voice state
const room = shallowRef<Room | null>(null)
const isMuted = ref(false)
const isDeafened = ref(false)

// Host mode state
const hostModeEnabled = ref(false)
const hostModeHostName = ref<string | null>(null)

// Server-side mute state cache
const serverMuteState = ref<Map<string, boolean>>(new Map())
let syncInterval: ReturnType<typeof setInterval> | null = null

interface GuestParticipant {
  id: string
  name: string
  isMuted: boolean
  isSpeaking: boolean
  isLocal: boolean
}
const participants = ref<GuestParticipant[]>([])

// Check invite validity on mount
onMounted(async () => {
  try {
    const resp = await fetch(`${API_BASE}/api/voice/invite/${token}`)
    const data = await resp.json()
    
    if (data.valid) {
      channelName.value = data.channel_name || '语音频道'
      serverName.value = data.server_name || '服务器'
      pageState.value = 'form'
    } else {
      pageState.value = 'invalid'
      errorMessage.value = '此邀请链接无效或已被使用。'
    }
  } catch {
    pageState.value = 'invalid'
    errorMessage.value = '验证邀请链接失败。'
  }
})

onUnmounted(() => {
  stopSyncInterval()
  if (room.value) {
    room.value.disconnect()
  }
})

function stopSyncInterval() {
  if (syncInterval) {
    clearInterval(syncInterval)
    syncInterval = null
  }
}

function startSyncInterval() {
  stopSyncInterval()
  syncFromServer()
  syncInterval = setInterval(() => {
    syncFromServer()
  }, 2000)
}

async function syncFromServer() {
  if (!channelId.value || pageState.value !== 'connected') return

  try {
    // Sync participants mute state
    const usersResp = await fetch(`${API_BASE}/api/voice/${channelId.value}/users`)
    if (usersResp.ok) {
      const users: Array<{ id: string; is_muted: boolean; is_host: boolean }> = await usersResp.json()
      const newMuteState = new Map<string, boolean>()
      for (const user of users) {
        newMuteState.set(user.id, user.is_muted)
      }
      serverMuteState.value = newMuteState
      updateParticipants()
    }

    // Sync host mode status
    const hostResp = await fetch(`${API_BASE}/api/voice/${channelId.value}/host-mode`)
    if (hostResp.ok) {
      const data = await hostResp.json()
      hostModeEnabled.value = data.enabled
      hostModeHostName.value = data.host_name
    }
  } catch (e) {
    console.error('Failed to sync from server:', e)
  }
}

function updateParticipants() {
  if (!room.value) {
    participants.value = []
    return
  }

  const list: GuestParticipant[] = []
  const local = room.value.localParticipant
  
  // Use server mute state for local participant if available
  const localServerMuted = serverMuteState.value.get(local.identity)
  const localMuted = localServerMuted !== undefined ? localServerMuted : !local.isMicrophoneEnabled
  
  list.push({
    id: local.identity,
    name: local.name || local.identity,
    isMuted: localMuted,
    isSpeaking: local.isSpeaking,
    isLocal: true,
  })

  room.value.remoteParticipants.forEach((p) => {
    // Prefer server-side mute state
    const serverMuted = serverMuteState.value.get(p.identity)
    const pMuted = serverMuted !== undefined ? serverMuted : !p.isMicrophoneEnabled
    
    list.push({
      id: p.identity,
      name: p.name || p.identity,
      isMuted: pMuted,
      isSpeaking: p.isSpeaking,
      isLocal: false,
    })
  })

  participants.value = list
}

async function joinVoice() {
  if (!username.value.trim()) {
    errorMessage.value = '请输入你的名字'
    return
  }

  pageState.value = 'connecting'
  errorMessage.value = ''

  try {
    const resp = await fetch(`${API_BASE}/api/voice/invite/${token}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value.trim() }),
    })

    if (!resp.ok) {
      const err = await resp.json()
      throw new Error(err.detail || 'Failed to join')
    }

    const data = await resp.json()
    channelName.value = data.channel_name
    
    // Extract channel ID from room_name (format: voice_{channelId})
    const roomNameMatch = data.room_name.match(/^voice_(\d+)$/)
    if (roomNameMatch) {
      channelId.value = parseInt(roomNameMatch[1], 10)
    }

    // Create and connect to LiveKit room
    room.value = new Room({
      adaptiveStream: true,
      dynacast: true,
      audioCaptureDefaults: {
        autoGainControl: true,
        noiseSuppression: true,
        echoCancellation: true,
      },
      publishDefaults: {
        audioPreset: AudioPresets.musicHighQualityStereo,
      },
    })

    room.value.on(RoomEvent.ParticipantConnected, () => updateParticipants())
    room.value.on(RoomEvent.ParticipantDisconnected, () => updateParticipants())
    room.value.on(RoomEvent.TrackMuted, (publication, participant) => {
      // Sync local muted state when server mutes local participant's mic
      if (participant === room.value?.localParticipant && publication.source === Track.Source.Microphone) {
        isMuted.value = true
      }
      updateParticipants()
    })
    room.value.on(RoomEvent.TrackUnmuted, (publication, participant) => {
      // Sync local muted state when server unmutes local participant's mic
      if (participant === room.value?.localParticipant && publication.source === Track.Source.Microphone) {
        isMuted.value = false
      }
      updateParticipants()
    })
    room.value.on(RoomEvent.ActiveSpeakersChanged, () => updateParticipants())
    room.value.on(RoomEvent.Disconnected, () => {
      stopSyncInterval()
      pageState.value = 'ended'
      participants.value = []
    })

    room.value.on(RoomEvent.TrackSubscribed, (track, _pub, participant) => {
      if (track.kind === Track.Kind.Audio && participant instanceof RemoteParticipant) {
        const audioElement = track.attach()
        audioElement.dataset.livekitAudio = 'true'
        audioElement.dataset.participantId = participant.identity
        document.body.appendChild(audioElement)
      }
    })

    room.value.on(RoomEvent.TrackUnsubscribed, (track) => {
      track.detach().forEach((el) => el.remove())
    })

    await room.value.connect(data.url, data.token)
    await room.value.localParticipant.setMicrophoneEnabled(true)

    isMuted.value = false
    isDeafened.value = false
    pageState.value = 'connected'
    updateParticipants()
    
    // Start periodic sync from server
    startSyncInterval()
  } catch (e) {
    pageState.value = 'form'
    errorMessage.value = e instanceof Error ? e.message : '连接失败'
  }
}

async function toggleMute() {
  if (!room.value) return
  const newMuted = !isMuted.value
  await room.value.localParticipant.setMicrophoneEnabled(!newMuted)
  isMuted.value = newMuted
  updateParticipants()
}

function toggleDeafen() {
  isDeafened.value = !isDeafened.value
  const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
  audioElements.forEach((el) => {
    ;(el as HTMLAudioElement).muted = isDeafened.value
  })
}

function disconnect() {
  stopSyncInterval()
  serverMuteState.value.clear()
  if (room.value) {
    room.value.disconnect()
    room.value = null
  }
  pageState.value = 'ended'
}
</script>

<template>
  <div class="page-shell">
    <div class="page-surface">
      <div class="page-surface__inner">
        <!-- Loading -->
        <div v-if="pageState === 'loading'" class="page-content">
          <div class="loading-spinner"></div>
          <p class="subtitle">正在验证邀请链接...</p>
        </div>

        <!-- Invalid invite -->
        <div v-else-if="pageState === 'invalid'" class="page-content">
          <AlertCircle class="error-icon" :size="64" />
          <h1 class="title">邀请无效</h1>
          <p class="subtitle">{{ errorMessage }}</p>
        </div>

        <!-- Username form -->
        <div v-else-if="pageState === 'form'" class="page-content">
          <UserPlus class="header-icon" :size="48" />
          <h1 class="title">加入语音频道</h1>
          <p class="subtitle">
            <strong>{{ serverName }}</strong> / {{ channelName }}
          </p>
          
          <div class="form-group">
            <label class="form-label">你的名字</label>
            <input
              v-model="username"
              type="text"
              class="form-input"
              placeholder="请输入你的显示名称"
              maxlength="50"
              @keyup.enter="joinVoice"
            />
          </div>

          <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>

          <button class="btn glow-effect" @click="joinVoice">
            加入语音
          </button>

          <p class="note">此邀请链接仅可使用一次，离开后无法再次加入。</p>
        </div>

        <!-- Connecting -->
        <div v-else-if="pageState === 'connecting'" class="page-content">
          <div class="loading-spinner"></div>
          <p class="subtitle">正在连接 {{ channelName }}...</p>
        </div>

        <!-- Connected -->
        <div v-else-if="pageState === 'connected'" class="voice-panel">
          <div class="voice-header">
            <Volume2 class="channel-icon" :size="20" />
            <span class="channel-name">{{ channelName }}</span>
            <span class="connection-badge">已连接</span>
          </div>

          <div class="voice-users">
            <div
              v-for="participant in participants"
              :key="participant.id"
              class="voice-user"
              :class="{ speaking: participant.isSpeaking }"
            >
              <div class="user-avatar">
                {{ participant.name.replace('[Guest] ', '').charAt(0).toUpperCase() }}
              </div>
              <span class="user-name">
                {{ participant.name }}
                <span v-if="participant.isLocal" class="local-tag">(你)</span>
              </span>
              <MicOff v-if="participant.isMuted" class="status-icon" :size="14" />
              <Mic v-if="participant.isSpeaking" class="speaking-icon" :size="14" />
            </div>
          </div>

          <!-- Host mode banner -->
          <div v-if="hostModeEnabled" class="host-mode-banner">
            <Crown :size="14" />
            <span>{{ hostModeHostName }} 正在主持</span>
          </div>

          <div class="voice-controls">
            <button
              class="control-btn glow-effect"
              :class="{ active: isMuted }"
              @click="toggleMute"
              :title="isMuted ? '取消静音' : '静音'"
            >
              <MicOff v-if="isMuted" :size="20" />
              <Mic v-else :size="20" />
            </button>
            <button
              class="control-btn glow-effect"
              :class="{ active: isDeafened }"
              @click="toggleDeafen"
              :title="isDeafened ? '打开扬声器' : '关闭扬声器'"
            >
              <VolumeX v-if="isDeafened" :size="20" />
              <Volume2 v-else :size="20" />
            </button>
            <button
              class="control-btn disconnect glow-effect"
              @click="disconnect"
              title="断开连接"
            >
              <Phone :size="20" />
            </button>
          </div>

          <p class="note">断开连接后无法再次加入。</p>
        </div>

        <!-- Session ended -->
        <div v-else-if="pageState === 'ended'" class="page-content">
          <AlertCircle class="info-icon" :size="64" />
          <h1 class="title">会话已结束</h1>
          <p class="subtitle">感谢参与，你可以关闭此页面。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-shell {
  min-height: 100vh;
  min-height: 100dvh;
  width: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: var(--spacing-md);
}

.page-surface {
  width: 100%;
  max-width: 480px;
  padding: var(--spacing-xxl) var(--spacing-xl);
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  border-radius: var(--radius-lg);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.page-surface__inner {
  width: 100%;
}

.page-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-lg);
  text-align: center;
}

.loading-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.header-icon {
  color: var(--color-primary);
}

.error-icon {
  color: var(--color-error, #ef4444);
}

.info-icon {
  color: var(--color-text-muted);
}

.title {
  color: var(--color-text-main);
  font-size: 1.75rem;
  font-weight: 700;
  margin: 0;
}

.subtitle {
  color: var(--color-text-muted);
  font-size: 1rem;
  margin: 0;
}

.form-group {
  width: 100%;
  text-align: left;
}

.form-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-main);
  margin-bottom: var(--spacing-sm);
}

.form-input {
  width: 100%;
  padding: 14px 16px;
  font-size: 1rem;
  color: var(--color-text-main);
  background: var(--surface-glass-input);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.form-input:focus {
  outline: none;
  background: var(--surface-glass-input-focus);
  border-color: rgba(252, 121, 97, 0.5);
  box-shadow: 0 0 0 3px rgba(252, 121, 97, 0.15);
}

.error-text {
  color: var(--color-error, #ef4444);
  font-size: 0.875rem;
  margin: 0;
}

.btn {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-gradient-primary);
  color: white;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-normal);
  box-shadow: var(--shadow-glow);
}

.btn:hover {
  transform: translateY(-2px);
  filter: brightness(1.1);
}

.btn:active {
  transform: translateY(0) scale(0.98);
}

.note {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin: 0;
}

/* Voice Panel Styles */
.voice-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.voice-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
}

.channel-icon {
  color: var(--color-primary);
}

.channel-name {
  font-weight: 600;
  color: var(--color-text-main);
  flex: 1;
}

.connection-badge {
  font-size: 0.75rem;
  padding: 4px 10px;
  border-radius: 10px;
  background: var(--color-success, #10b981);
  color: #fff;
  font-weight: 500;
}

.voice-users {
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.voice-user {
  display: flex;
  align-items: center;
  padding: 8px 0;
  transition: background 0.2s ease;
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
  color: var(--color-text-muted);
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
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-main);
}

.control-btn:hover {
  background: var(--surface-glass-strong);
  transform: scale(1.1);
}

.control-btn.active {
  background: var(--color-error);
  border-color: var(--color-error);
  color: #fff;
}

.control-btn.disconnect {
  background: var(--color-error);
  border-color: var(--color-error);
  color: #fff;
}

.control-btn.disconnect svg {
  transform: rotate(135deg);
}

.control-btn.disconnect:hover {
  filter: brightness(0.9);
}

.host-mode-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: rgba(245, 158, 11, 0.2);
  border-radius: var(--radius-md);
  font-size: 13px;
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.3);
}

@media (max-width: 600px) {
  .page-surface {
    padding: var(--spacing-xl) var(--spacing-md);
  }
}
</style>
