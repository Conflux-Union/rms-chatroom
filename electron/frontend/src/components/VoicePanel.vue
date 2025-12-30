<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'
import { useAuthStore } from '../stores/auth'
import { Volume2, VolumeX, Mic, MicOff, Phone, AlertTriangle, Crown, Link, Copy, Check, UserX, Monitor, MonitorOff } from 'lucide-vue-next'

// Detect iOS devices
const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)

const API_BASE = import.meta.env.VITE_API_BASE || ''

const chat = useChatStore()
const voice = useVoiceStore()
const auth = useAuthStore()

// Invite link state
const showInviteDialog = ref(false)
const inviteUrl = ref('')
const inviteCopied = ref(false)
const inviteLoading = ref(false)
const inviteError = ref('')

onMounted(() => {
  voice.enumerateDevices()
})

// Host mode computed
const isCurrentUserHost = computed(() => 
  voice.hostModeHostId === String(auth.user?.id)
)
const hostButtonDisabled = computed(() => 
  voice.hostModeEnabled && !isCurrentUserHost.value
)

// Volume warning dialog state
const showVolumeWarning = ref(false)
const pendingVolumeParticipant = ref<string | null>(null)
const pendingVolumeValue = ref(100)

// Mobile swipe state
const swipedUserId = ref<string | null>(null)
const touchStartX = ref(0)
const touchCurrentX = ref(0)

// Desktop context menu state
const contextMenu = ref<{ show: boolean; x: number; y: number; participantId: string }>({
  show: false, x: 0, y: 0, participantId: ''
})

// Screen share state
const screenShareExpanded = ref(true)
const screenShareContainer = ref<HTMLElement | null>(null)
const localScreenShareContainer = ref<HTMLElement | null>(null)

// Computed: first remote screen share (show one at a time)
const activeRemoteScreenShare = computed(() => {
  const shares = voice.remoteScreenShares
  if (shares.size === 0) return null
  return shares.values().next().value
})

// Watch for remote screen share changes and attach video
watch(activeRemoteScreenShare, async (newShare) => {
  await nextTick()
  if (newShare && screenShareContainer.value) {
    voice.attachScreenShare(newShare.participantId, screenShareContainer.value)
  }
})

// Watch for local screen share changes
watch(() => voice.isScreenSharing, async (sharing) => {
  await nextTick()
  if (sharing && localScreenShareContainer.value) {
    voice.attachLocalScreenShare(localScreenShareContainer.value)
  }
})

function handleTouchStart(event: TouchEvent, _participantId: string) {
  const touch = event.touches[0]
  if (touch) {
    touchStartX.value = touch.clientX
    touchCurrentX.value = touch.clientX
  }
}

function handleTouchMove(event: TouchEvent, participantId: string) {
  const touch = event.touches[0]
  if (touch) {
    touchCurrentX.value = touch.clientX
    const diff = touchStartX.value - touchCurrentX.value
    if (diff > 50) {
      swipedUserId.value = participantId
    } else if (diff < -30) {
      swipedUserId.value = null
    }
  }
}

function handleTouchEnd() {
  touchStartX.value = 0
  touchCurrentX.value = 0
}

async function muteParticipant(participantId: string) {
  await voice.muteParticipant(participantId, true)
  swipedUserId.value = null
}

async function kickParticipant(participantId: string) {
  await voice.kickParticipant(participantId)
  swipedUserId.value = null
  hideContextMenu()
}

function showContextMenu(event: MouseEvent, participantId: string) {
  event.preventDefault()
  event.stopPropagation()
  contextMenu.value = { show: true, x: event.clientX, y: event.clientY, participantId }
}

function hideContextMenu() {
  contextMenu.value = { show: false, x: 0, y: 0, participantId: '' }
}

async function contextMuteParticipant() {
  if (!contextMenu.value.participantId) return
  await voice.muteParticipant(contextMenu.value.participantId, true)
  hideContextMenu()
}

async function contextKickParticipant() {
  if (!contextMenu.value.participantId) return
  await voice.kickParticipant(contextMenu.value.participantId)
  hideContextMenu()
}

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

async function createInviteLink() {
  if (!chat.currentChannel) return
  
  inviteLoading.value = true
  inviteError.value = ''
  inviteUrl.value = ''
  inviteCopied.value = false
  showInviteDialog.value = true

  try {
    const response = await fetch(
      `${API_BASE}/api/voice/${chat.currentChannel.id}/invite`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${auth.token}`,
        },
      }
    )

    if (!response.ok) {
      const err = await response.json()
      throw new Error(err.detail || 'Failed to create invite')
    }

    const data = await response.json()
    inviteUrl.value = data.invite_url
  } catch (e) {
    inviteError.value = e instanceof Error ? e.message : 'Failed to create invite'
  } finally {
    inviteLoading.value = false
  }
}

async function copyInviteLink() {
  if (!inviteUrl.value) return
  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    inviteCopied.value = true
    setTimeout(() => { inviteCopied.value = false }, 2000)
  } catch {
    // Fallback for older browsers
    const input = document.createElement('input')
    input.value = inviteUrl.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    inviteCopied.value = true
    setTimeout(() => { inviteCopied.value = false }, 2000)
  }
}

function closeInviteDialog() {
  showInviteDialog.value = false
  inviteUrl.value = ''
  inviteError.value = ''
  inviteCopied.value = false
}
</script>

<template>
  <div class="voice-panel" @click="hideContextMenu">
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
            class="voice-user-wrapper"
            :class="{ swiped: swipedUserId === participant.id && !participant.isLocal && auth.isAdmin }"
          >
            <div
              class="voice-user"
              :class="{ speaking: participant.isSpeaking }"
              @touchstart="!participant.isLocal && auth.isAdmin ? handleTouchStart($event, participant.id) : null"
              @touchmove="!participant.isLocal && auth.isAdmin ? handleTouchMove($event, participant.id) : null"
              @touchend="handleTouchEnd"
              @contextmenu="!participant.isLocal && auth.isAdmin ? showContextMenu($event, participant.id) : null"
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
                <!-- iOS: show mute toggle (volume control not supported) -->
                <template v-if="isIOS">
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
                </template>
                <!-- Non-iOS: show volume slider -->
                <template v-else>
                  <Volume2 class="volume-icon" :size="14" />
                  <input
                    type="range"
                    class="volume-slider"
                    min="0"
                    max="100"
                    :value="participant.volume"
                    @input="handleVolumeChange(participant.id, $event)"
                  />
                  <span class="volume-value">{{ participant.volume }}%</span>
                </template>
              </div>
            </div>
            <!-- Swipe action buttons -->
            <div v-if="!participant.isLocal && auth.isAdmin" class="swipe-actions">
              <button 
                class="swipe-action-btn"
                @click="muteParticipant(participant.id)"
              >
                <MicOff :size="18" />
                <span>静音</span>
              </button>
              <button 
                class="swipe-action-btn kick"
                @click="kickParticipant(participant.id)"
              >
                <UserX :size="18" />
                <span>踢出</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Host mode banner -->
        <div v-if="voice.hostModeEnabled" class="host-mode-banner">
          <Crown :size="14" />
          <span>{{ voice.hostModeHostName }} 正在主持</span>
        </div>

        <div class="voice-controls">
          <button
            class="control-btn glow-effect"
            :class="{ active: voice.isMuted }"
            @click="voice.toggleMute()"
            :title="voice.isMuted ? '取消静音' : '静音'"
          >
            <MicOff v-if="voice.isMuted" :size="20" />
            <Mic v-else :size="20" />
          </button>
          <button
            class="control-btn glow-effect"
            :class="{ active: voice.isDeafened }"
            @click="voice.toggleDeafen()"
            :title="voice.isDeafened ? '打开扬声器' : '关闭扬声器'"
          >
            <VolumeX v-if="voice.isDeafened" :size="20" />
            <Volume2 v-else :size="20" />
          </button>
          <button
            v-if="auth.isAdmin"
            class="control-btn glow-effect"
            :class="{ 
              'host-mode-active': voice.hostModeEnabled && isCurrentUserHost,
              'host-mode-disabled': hostButtonDisabled 
            }"
            :disabled="hostButtonDisabled"
            @click="voice.toggleHostMode()"
            :title="hostButtonDisabled ? '其他用户正在主持' : (voice.hostModeEnabled ? '关闭主持人模式' : '开启主持人模式')"
          >
            <Crown :size="20" />
          </button>
          <button
            v-if="auth.isAdmin"
            class="control-btn glow-effect invite-btn"
            @click="createInviteLink"
            title="创建邀请链接"
          >
            <Link :size="20" />
          </button>
          <button
            class="control-btn glow-effect"
            :class="{ 'screen-share-active': voice.isScreenSharing }"
            @click="voice.toggleScreenShare()"
            :title="voice.isScreenSharing ? '停止共享屏幕' : '共享屏幕'"
          >
            <MonitorOff v-if="voice.isScreenSharing" :size="20" />
            <Monitor v-else :size="20" />
          </button>
          <button
            class="control-btn disconnect glow-effect"
            @click="voice.disconnect()"
            title="断开连接"
          >
            <Phone :size="20" />
          </button>
        </div>

        <!-- Screen Share Display -->
        <div v-if="activeRemoteScreenShare || voice.isScreenSharing" class="screen-share-section">
          <div class="screen-share-header" @click="screenShareExpanded = !screenShareExpanded">
            <Monitor :size="16" />
            <span v-if="activeRemoteScreenShare">
              {{ activeRemoteScreenShare.participantName }} 正在共享屏幕
            </span>
            <span v-else>你正在共享屏幕</span>
            <button class="screen-share-toggle">
              {{ screenShareExpanded ? '收起' : '展开' }}
            </button>
          </div>
          <div v-show="screenShareExpanded" class="screen-share-video">
            <div
              v-if="activeRemoteScreenShare"
              ref="screenShareContainer"
              class="video-container"
            ></div>
            <div
              v-else-if="voice.isScreenSharing"
              ref="localScreenShareContainer"
              class="video-container local-preview"
            >
              <div class="local-preview-label">预览</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Invite Link Dialog -->
    <Teleport to="body">
      <div v-if="showInviteDialog" class="invite-overlay" @click.self="closeInviteDialog">
        <div class="invite-dialog">
          <Link class="invite-icon" :size="48" />
          <h3 class="invite-title">邀请访客</h3>
          
          <div v-if="inviteLoading" class="invite-loading">
            <div class="loading-spinner"></div>
            <p>正在生成链接...</p>
          </div>
          
          <div v-else-if="inviteError" class="invite-error">
            <p>{{ inviteError }}</p>
          </div>
          
          <div v-else-if="inviteUrl" class="invite-content">
            <p class="invite-note">此链接仅可使用一次，访客离开后无法再次加入。</p>
            <div class="invite-url-box">
              <input type="text" class="invite-url-input" :value="inviteUrl" readonly />
              <button class="invite-copy-btn" @click="copyInviteLink" :title="inviteCopied ? '已复制' : '复制链接'">
                <Check v-if="inviteCopied" :size="18" />
                <Copy v-else :size="18" />
              </button>
            </div>
          </div>
          
          <div class="invite-actions">
            <button class="invite-btn-close" @click="closeInviteDialog">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>

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

    <!-- Desktop Context Menu -->
    <div
      v-if="contextMenu.show && auth.isAdmin"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item" @click="contextMuteParticipant">
        <MicOff :size="14" />
        <span>静音麦克风</span>
      </div>
      <div class="context-menu-item delete" @click="contextKickParticipant">
        <UserX :size="14" />
        <span>踢出频道</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.voice-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
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
  min-height: 0;
  overflow-y: auto;
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

.voice-user-wrapper {
  position: relative;
  overflow: hidden;
  margin-bottom: 4px;
}

.voice-user {
  display: flex;
  flex-direction: column;
  padding: 8px 0;
  transition: all 0.2s ease, transform 0.3s ease;
  position: relative;
  z-index: 1;
  background: transparent;
}

.voice-user.speaking {
  background: rgba(16, 185, 129, 0.1);
  border-radius: 8px;
  padding: 8px;
  margin: 0 -8px;
}

.swipe-actions {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  opacity: 0;
  transform: translateX(100%);
  transition: all 0.3s ease;
}

.swipe-action-btn {
  width: 60px;
  background: #f59e0b;
  border: none;
  color: white;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  font-size: 12px;
  cursor: pointer;
}

.swipe-action-btn:first-child {
  border-radius: 8px 0 0 8px;
}

.swipe-action-btn:last-child {
  border-radius: 0 8px 8px 0;
}

.swipe-action-btn.kick {
  background: var(--color-error, #ef4444);
}

.voice-user-wrapper.swiped .voice-user {
  transform: translateX(-120px);
}

.voice-user-wrapper.swiped .swipe-actions {
  opacity: 1;
  transform: translateX(0);
}

.user-info {
  display: flex;
  align-items: center;
  width: 100%;
  left: 5px;
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

/* iOS mute button styles */
.ios-mute-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--surface-glass);
  border: 1px solid rgba(255, 255, 255, 0.2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.ios-mute-btn:hover {
  background: rgba(255, 255, 255, 0.15);
}

.ios-mute-btn.muted {
  background: var(--color-error, #ef4444);
  border-color: var(--color-error, #ef4444);
}

.ios-volume-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-left: 8px;
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

/* Invite Button */
.control-btn.invite-btn {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  border-color: #3b82f6;
  color: white;
}

.control-btn.invite-btn:hover {
  filter: brightness(1.1);
}

/* Screen Share Button */
.control-btn.screen-share-active {
  background: linear-gradient(135deg, #10b981, #059669);
  border-color: #10b981;
  color: white;
}

.control-btn.screen-share-active:hover {
  filter: brightness(1.1);
}

/* Screen Share Section */
.screen-share-section {
  margin-top: 16px;
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 255, 255, 0.15);
  overflow: hidden;
}

.screen-share-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: rgba(16, 185, 129, 0.1);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  cursor: pointer;
  font-size: 14px;
  color: #10b981;
}

.screen-share-header:hover {
  background: rgba(16, 185, 129, 0.15);
}

.screen-share-toggle {
  margin-left: auto;
  padding: 4px 8px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  cursor: pointer;
}

.screen-share-toggle:hover {
  background: rgba(255, 255, 255, 0.2);
}

.screen-share-video {
  padding: 8px;
}

.video-container {
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #000;
  border-radius: var(--radius-md);
  overflow: hidden;
  position: relative;
}

.video-container.local-preview {
  opacity: 0.8;
}

.local-preview-label {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 4px 8px;
  background: rgba(0, 0, 0, 0.6);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: #fff;
  z-index: 1;
}

/* Invite Dialog Styles */
.invite-overlay {
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

.invite-dialog {
  background: var(--surface-glass-strong, rgba(30, 30, 40, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: var(--radius-lg, 16px);
  padding: 24px;
  max-width: 440px;
  width: 90%;
  text-align: center;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.4);
}

.invite-icon {
  color: #3b82f6;
  margin-bottom: 12px;
}

.invite-title {
  color: var(--color-text-main);
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 16px 0;
}

.invite-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 20px 0;
}

.invite-loading .loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid rgba(255, 255, 255, 0.2);
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.invite-loading p {
  color: var(--color-text-muted);
  margin: 0;
}

.invite-error p {
  color: var(--color-error, #ef4444);
  margin: 0;
}

.invite-content {
  text-align: left;
}

.invite-note {
  color: var(--color-text-muted);
  font-size: 13px;
  margin: 0 0 12px 0;
}

.invite-url-box {
  display: flex;
  gap: 8px;
}

.invite-url-input {
  flex: 1;
  padding: 12px 14px;
  font-size: 13px;
  color: var(--color-text-main);
  background: var(--surface-glass-input);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  font-family: monospace;
}

.invite-url-input:focus {
  outline: none;
}

.invite-copy-btn {
  padding: 12px 14px;
  background: #3b82f6;
  border: none;
  border-radius: var(--radius-md);
  color: white;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.invite-copy-btn:hover {
  filter: brightness(1.1);
}

.invite-actions {
  margin-top: 20px;
}

.invite-btn-close {
  padding: 10px 24px;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  border-radius: var(--radius-md);
  color: var(--color-text-main);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.invite-btn-close:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* Mobile Responsive */
/* Context Menu */
.context-menu {
  position: fixed;
  background: var(--surface-glass-strong, rgba(30, 30, 40, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: var(--radius-md, 8px);
  padding: 4px;
  min-width: 160px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  z-index: 9999;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
}

.context-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  font-size: 14px;
  color: var(--color-text-main);
  cursor: pointer;
  border-radius: var(--radius-sm, 6px);
  transition: background 0.15s ease;
}

.context-menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
}

.context-menu-item.delete {
  color: var(--color-error, #ef4444);
}

.context-menu-item.delete:hover {
  background: rgba(239, 68, 68, 0.15);
}

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

  .context-menu {
    display: none;
  }
}
</style>
