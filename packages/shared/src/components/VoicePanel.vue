<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick, Transition } from 'vue'
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'
import { useAuthStore } from '../stores/auth'
import { Volume2, VolumeX, Mic, MicOff, Phone, AlertTriangle, Crown, Link, Copy, Check, UserX, Monitor, MonitorOff, MessageSquare } from 'lucide-vue-next'
import { NModal, NButton, NSpace, NInput, NDropdown, NSpin } from 'naive-ui'
import type { DropdownOption } from 'naive-ui'
import TranscriptionPanel from './TranscriptionPanel.vue'

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
  console.log('[VoicePanel] Component mounted - Initial state report')
  console.log('[VoicePanel] - API_BASE:', API_BASE)
  console.log('[VoicePanel] - User:', auth.user)
  console.log('[VoicePanel] - User permission level:', auth.user?.permission_level)
  console.log('[VoicePanel] - Can view transcription sidebar:', canViewTranscriptionSidebar?.value)
  console.log('[VoicePanel] - Can use transcription button:', canUseTranscription?.value)
  console.log('[VoicePanel] - Current channel:', chat.currentChannel)
  console.log('[VoicePanel] - Token exists:', !!auth.token)
  console.log('[VoicePanel] - Token preview:', auth.token?.substring(0, 20) + '...')
  
  voice.enumerateDevices()
  
  // Check transcription lock status when channel changes
  if (chat.currentChannel) {
    console.log('[VoicePanel] Checking transcription lock on mount')
    checkTranscriptionLock()
  } else {
    console.warn('[VoicePanel] No current channel on mount')
  }
  
  // Periodic status check every 30 seconds
  setInterval(() => {
    console.log('[VoicePanel] Periodic status check')
    console.log('[VoicePanel] - Connected to voice:', voice.isConnected)
    console.log('[VoicePanel] - Current channel:', chat.currentChannel?.id)
    console.log('[VoicePanel] - Transcription active:', transcriptionActive.value)
    console.log('[VoicePanel] - Transcription locked:', transcriptionLocked.value)
    console.log('[VoicePanel] - Transcription expanded:', transcriptionExpanded.value)
  }, 30000)
})

// Watch for channel changes to update transcription lock status
watch(() => chat.currentChannel, (newChannel, oldChannel) => {
  console.log('[VoicePanel] Channel changed')
  console.log('[VoicePanel] - Old channel:', oldChannel?.id, oldChannel?.name)
  console.log('[VoicePanel] - New channel:', newChannel?.id, newChannel?.name)
  
  if (newChannel) {
    checkTranscriptionLock()
  }
})

// Host mode computed
const isCurrentUserHost = computed(() => 
  voice.hostModeHostId === String(auth.user?.id)
)
const hostButtonDisabled = computed(() => 
  voice.hostModeEnabled && !isCurrentUserHost.value
)

// Transcription computed
// 权限调整：转录侧边栏可见性下调到权限等级 >= 0（所有用户可见）
// 而实际启用/使用转录功能的按钮权限下调到权限等级 >= 3
const canViewTranscriptionSidebar = computed(() => {
  const result = (auth.user?.permission_level ?? 0) >= 0
  console.log('[VoicePanel] canViewTranscriptionSidebar computed:', {
    permission_level: auth.user?.permission_level,
    result: result
  })
  return result
})

const canUseTranscription = computed(() => {
  const result = (auth.user?.permission_level ?? 0) >= 3
  console.log('[VoicePanel] canUseTranscription computed (button permission):', {
    permission_level: auth.user?.permission_level,
    result: result
  })
  return result
})

const transcriptionButtonDisabled = computed(() => {
  const result = transcriptionLocked.value && !transcriptionActive.value
  console.log('[VoicePanel] transcriptionButtonDisabled computed:', {
    locked: transcriptionLocked.value,
    active: transcriptionActive.value,
    disabled: result
  })
  return result
})

// Volume warning dialog state
const showVolumeWarning = ref(false)
const pendingVolumeParticipant = ref<string | null>(null)
const pendingVolumeValue = ref(100)

// Mobile swipe state
const swipedUserId = ref<string | null>(null)
const touchStartX = ref(0)
const touchCurrentX = ref(0)

// Desktop context menu state (NDropdown)
const participantDropdown = ref<{ show: boolean; x: number; y: number; participantId: string }>({
  show: false, x: 0, y: 0, participantId: ''
})

// Dropdown options for participant context menu
const participantDropdownOptions: DropdownOption[] = [
  { label: '静音麦克风', key: 'mute', icon: () => h(MicOff, { size: 14 }) },
  { label: '踢出频道', key: 'kick', props: { style: { color: 'var(--color-error, #ef4444)' } } }
]

import { h } from 'vue'

// Screen share state
const screenShareExpanded = ref(true)
const screenShareContainer = ref<HTMLElement | null>(null)
const localScreenShareContainer = ref<HTMLElement | null>(null)

// Transcription state
const transcriptionExpanded = ref(false)
const transcriptionPanel = ref<InstanceType<typeof TranscriptionPanel> | null>(null)
const transcriptionLocked = ref(false)
const transcriptionActive = ref(false)
const longPressTimer = ref<number | null>(null)
const LONG_PRESS_DURATION = 1000 // 1 second for long press

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
  hideParticipantDropdown()
}

function showParticipantContextMenu(event: MouseEvent, participantId: string) {
  event.preventDefault()
  event.stopPropagation()
  participantDropdown.value = { show: true, x: event.clientX, y: event.clientY, participantId }
}

function hideParticipantDropdown() {
  participantDropdown.value = { show: false, x: 0, y: 0, participantId: '' }
}

async function handleParticipantDropdownSelect(key: string) {
  if (key === 'mute') {
    await voice.muteParticipant(participantDropdown.value.participantId, true)
  } else if (key === 'kick') {
    await voice.kickParticipant(participantDropdown.value.participantId)
  }
  hideParticipantDropdown()
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

// Transcription methods
async function checkTranscriptionLock() {
  console.log('[Transcription] Checking transcription lock status')
  console.log('[Transcription] - Current channel:', chat.currentChannel?.id)
  
  if (!chat.currentChannel) {
    console.log('[Transcription] No current channel, skipping lock check')
    return
  }
  
  const checkUrl = `${API_BASE}/api/voice-recognition/status`
  console.log('[Transcription] - Checking URL:', checkUrl)
  
  try {
    const response = await fetch(checkUrl, {
      headers: {
        Authorization: `Bearer ${auth.token}`,
      },
    })
    
    console.log('[Transcription] - Response status:', response.status)
    
    if (response.ok) {
      const data = await response.json()
      console.log('[Transcription] - Lock status data:', data)
      
      // Check if global_lock exists and is locked
      const isGlobalLocked = data.global_lock?.is_locked || false
      const lockedRoomId = data.global_lock?.active_room_id || null
      
      transcriptionLocked.value = isGlobalLocked && lockedRoomId !== chat.currentChannel.id
      console.log('[Transcription] - Locked for this channel:', transcriptionLocked.value)
      console.log('[Transcription] - Global locked:', isGlobalLocked)
      console.log('[Transcription] - Locked room ID:', lockedRoomId)
      console.log('[Transcription] - Current channel ID:', chat.currentChannel.id)
    } else {
      console.warn('[Transcription] Failed to get lock status, response not OK')
      const errorText = await response.text()
      console.warn('[Transcription] Error response:', errorText)
    }
  } catch (e) {
    console.error('[Transcription] ❌ Failed to check transcription lock:', e)
    console.error('[Transcription] Error details:', {
      message: e instanceof Error ? e.message : String(e),
      stack: e instanceof Error ? e.stack : undefined
    })
  }
}

function toggleTranscriptionPanel() {
  transcriptionExpanded.value = !transcriptionExpanded.value
}

function handleTranscriptionMouseDown() {
  console.log('[Transcription] Mouse down event triggered')
  console.log('[Transcription] - canUseTranscription:', canUseTranscription.value)
  console.log('[Transcription] - transcriptionButtonDisabled:', transcriptionButtonDisabled.value)
  console.log('[Transcription] - transcriptionActive:', transcriptionActive.value)
  console.log('[Transcription] - transcriptionLocked:', transcriptionLocked.value)
  console.log('[Transcription] - hasTranscriptionTask:', transcriptionPanel.value?.hasTranscriptionTask)
  
  if (!canUseTranscription.value || transcriptionButtonDisabled.value) {
    console.log('[Transcription] Button disabled, ignoring click')
    return
  }
  
  if (!transcriptionActive.value && !transcriptionPanel.value?.hasTranscriptionTask) {
    // First click or no existing task - start immediately
    console.log('[Transcription] Starting new transcription session')
    startTranscription()
  } else {
    // Set up long press for stop
    console.log('[Transcription] Setting up long press to stop (1s)')
    longPressTimer.value = window.setTimeout(() => {
      console.log('[Transcription] Long press detected, stopping transcription')
      stopTranscription()
      longPressTimer.value = null
    }, LONG_PRESS_DURATION)
  }
}

function handleTranscriptionMouseUp() {
  if (longPressTimer.value) {
    clearTimeout(longPressTimer.value)
    longPressTimer.value = null
  }
}

function handleTranscriptionMouseLeave() {
  if (longPressTimer.value) {
    clearTimeout(longPressTimer.value)
    longPressTimer.value = null
  }
}

async function startTranscription() {
  console.log('[Transcription] startTranscription called')
  console.log('[Transcription] - Panel ref exists:', !!transcriptionPanel.value)
  console.log('[Transcription] - Current channel:', chat.currentChannel?.id)
  console.log('[Transcription] - User ID:', auth.user?.id)
  console.log('[Transcription] - API_BASE:', API_BASE)
  
  // Auto-expand panel BEFORE starting transcription
  transcriptionExpanded.value = true
  console.log('[Transcription] - Panel expanded:', transcriptionExpanded.value)
  
  // Wait for next tick to ensure panel is rendered
  await nextTick()
  
  if (!transcriptionPanel.value) {
    console.error('[Transcription] Panel ref not available')
    return
  }
  
  try {
    console.log('[Transcription] Calling panel.startTranscription()...')
    await transcriptionPanel.value.startTranscription()
    transcriptionActive.value = true
    console.log('[Transcription] ✅ Transcription started successfully')
    console.log('[Transcription] - Active:', transcriptionActive.value)
    console.log('[Transcription] - Expanded:', transcriptionExpanded.value)
  } catch (e) {
    console.error('[Transcription] ❌ Failed to start transcription:', e)
    console.error('[Transcription] Error details:', {
      message: e instanceof Error ? e.message : String(e),
      stack: e instanceof Error ? e.stack : undefined
    })
  }
}

async function stopTranscription() {
  console.log('[Transcription] stopTranscription called')
  console.log('[Transcription] - Panel ref exists:', !!transcriptionPanel.value)
  console.log('[Transcription] - Active before stop:', transcriptionActive.value)
  
  if (!transcriptionPanel.value) {
    console.error('[Transcription] Panel ref not available')
    return
  }
  
  try {
    console.log('[Transcription] Calling panel.stopTranscription()...')
    await transcriptionPanel.value.stopTranscription()
    transcriptionActive.value = false
    console.log('[Transcription] ✅ Transcription stopped successfully')
    console.log('[Transcription] - Active:', transcriptionActive.value)
  } catch (e) {
    console.error('[Transcription] ❌ Failed to stop transcription:', e)
    console.error('[Transcription] Error details:', {
      message: e instanceof Error ? e.message : String(e),
      stack: e instanceof Error ? e.stack : undefined
    })
  }
}
</script>

<template>
  <div class="voice-panel" @click="hideParticipantDropdown">
    <div class="voice-header">
      <Volume2 class="channel-icon" :size="20" />
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
      <span v-if="voice.isConnected" class="connection-mode connected">
        已连接
      </span>
    </div>

    <div class="voice-content">
      <!-- Device Selection (moved to Settings) -->
      <!--
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
      -->

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
        <!-- 主要内容区域：用户和转录面板并排 -->
        <div class="voice-main-content" :class="{ 'has-transcription': canViewTranscriptionSidebar && transcriptionExpanded }">
          <!-- 左侧：语音用户列表 -->
          <div class="voice-users-container">
            <div class="voice-users-header">
              <h4>语音用户 ({{ voice.participants.length }})</h4>
              <button
                v-if="canViewTranscriptionSidebar"
                class="transcription-toggle-btn"
                @click="toggleTranscriptionPanel"
                :class="{ 'expanded': transcriptionExpanded }"
                title="展开/收起语音转文字面板"
              >
                {{ transcriptionExpanded ? '‹' : '›' }}
              </button>
            </div>
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
                  @contextmenu="!participant.isLocal && auth.isAdmin ? showParticipantContextMenu($event, participant.id) : null"
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

            <!-- 语音控制按钮（移到用户容器内） -->
            <div class="voice-controlss">
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
                v-if="canUseTranscription"
                class="control-btn glow-effect"
                :class="{ 
                  'transcription-active': transcriptionActive,
                  'transcription-disabled': transcriptionButtonDisabled 
                }"
                :disabled="transcriptionButtonDisabled"
                @mousedown="handleTranscriptionMouseDown"
                @mouseup="handleTranscriptionMouseUp"
                @mouseleave="handleTranscriptionMouseLeave"
                :title="transcriptionActive ? '长按停止转录' : (transcriptionButtonDisabled ? '其他频道正在使用' : '开始语音转文字')"
              >
                <MessageSquare :size="20" />
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
          </div>
          
          <!-- 右侧：转录面板（仅在展开时显示）-->
          <div v-if="canViewTranscriptionSidebar && transcriptionExpanded" class="transcription-panel-container">
            <TranscriptionPanel
              ref="transcriptionPanel"
              :is-expanded="true"
              @toggle-expand="toggleTranscriptionPanel"
            />
          </div>
        </div>

        <!-- Host mode banner -->
        <div v-if="voice.hostModeEnabled" class="host-mode-banner">
          <Crown :size="14" />
          <span>{{ voice.hostModeHostName }} 正在主持</span>
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

    <!-- Invite Link Dialog (NModal) -->
    <NModal
      v-model:show="showInviteDialog"
      preset="card"
      title="邀请访客"
      style="width: 440px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <template #header-extra>
        <Link class="invite-icon" :size="24" />
      </template>

      <div v-if="inviteLoading" class="invite-loading">
        <NSpin size="medium" />
        <p>正在生成链接...</p>
      </div>

      <div v-else-if="inviteError" class="invite-error">
        <p>{{ inviteError }}</p>
      </div>

      <div v-else-if="inviteUrl" class="invite-content">
        <p class="invite-note">此链接仅可使用一次，访客离开后无法再次加入。</p>
        <NSpace>
          <NInput :value="inviteUrl" readonly style="flex: 1; font-family: monospace;" />
          <NButton @click="copyInviteLink" :type="inviteCopied ? 'success' : 'primary'">
            <template #icon>
              <Check v-if="inviteCopied" :size="18" />
              <Copy v-else :size="18" />
            </template>
          </NButton>
        </NSpace>
      </div>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeInviteDialog">关闭</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- Volume Warning Dialog (NModal) -->
    <NModal
      v-model:show="showVolumeWarning"
      preset="card"
      title="高音量警告"
      style="width: 400px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <template #header-extra>
        <AlertTriangle class="warning-icon" :size="24" style="color: var(--color-warning, #f59e0b);" />
      </template>

      <p class="warning-message">
        高音量可能损害您的听力和音频设备。
      </p>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeVolumeWarning">取消</NButton>
          <NButton type="warning" @click="confirmVolumeWarning">我已了解</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- Participant Context Menu (NDropdown) -->
    <NDropdown
      placement="bottom-start"
      trigger="manual"
      :x="participantDropdown.x"
      :y="participantDropdown.y"
      :options="participantDropdownOptions"
      :show="participantDropdown.show && auth.isAdmin"
      @select="handleParticipantDropdownSelect"
      @clickoutside="participantDropdown.show = false"
    />
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
  align-items: center;
  justify-content: center;
  padding: 24px;
  min-height: 0;
  overflow-y: auto;
}

.voice-content:has(.voice-connect) {
  justify-content: center;
  align-items: center;
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
  align-self: center;
  justify-self: center;
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
  max-width: 100%;
  display: flex;
  flex-direction: column;
  flex: 1; /* 占满剩余空间 */
}

/* 主要内容区域：水平布局 */
.voice-main-content {
  display: flex;
  flex-direction: row;
  gap: 16px;
  align-items: center; /* 垂直居中对齐 */
  justify-content: center; /* 水平居中对齐 */
  width: 100%;
  height: 100%; /* 占满父容器高度 */
}

/* 当转录面板展开时，保持居中但左右分布 */
.voice-main-content.has-transcription {
  justify-content: center;
  align-items: center; /* 保持垂直居中 */
}

/* 用户容器 */
.voice-users-container {
  flex: 0 0 400px;
  display: flex;
  flex-direction: column;
  transition: none; /* 移除可能导致抽搐的过渡效果 */
}

/* 当没有转录面板时，用户容器独占空间 */
.voice-main-content:not(.has-transcription) .voice-users-container {
  flex: 1 1 auto;
  max-width: 400px;
}

/* 用户列表标题 */
.voice-users-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  margin-bottom: 8px;
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.voice-users-header h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-main);
}

/* 展开/收起按钮 */
.transcription-toggle-btn {
  background: var(--surface-glass);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  color: var(--color-text-main);
  font-size: 14px;
  font-weight: bold;
  padding: 4px 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  min-width: 24px;
  text-align: center;
}

.transcription-toggle-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
}

.transcription-toggle-btn.expanded {
  background: var(--color-primary, #6366f1);
  color: white;
  border-color: var(--color-primary, #6366f1);
}

/* 转录面板容器 */
.transcription-panel-container {
  width: 400px;
  min-height: 300px;
  max-height: 75vh;
  opacity: 1;
  visibility: visible;
}

.voice-users {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  padding: 12px 8px;
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
  padding: 16px 16px;
  transition: all 0.2s ease, transform 0.3s ease;
  border-radius: var(--radius-lg);
  position: relative;
  z-index: 1;
  background: transparent;
}

.voice-user.speaking {
  background: rgba(16, 185, 129, 0.1);
  border-radius: 8px;
  padding: 16px 12px;
  margin: 0px 4px;
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
  background: rgba(80, 80, 80, 0.5);
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

.voice-controlss {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 12px;
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

.warning-message {
  color: var(--color-text-muted, #9ca3af);
  font-size: 14px;
  line-height: 1.5;
  margin: 0;
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

/* Transcription Button */
.control-btn.transcription-active {
  background: linear-gradient(135deg, #f59e0b, #d97706);
  border-color: #f59e0b;
  color: white;
  animation: pulse-transcription 2s infinite;
}

.control-btn.transcription-active:hover {
  filter: brightness(1.1);
}

.control-btn.transcription-disabled {
  background: var(--surface-glass);
  opacity: 0.5;
  cursor: not-allowed;
}

.control-btn.transcription-disabled:hover {
  transform: none;
  background: var(--surface-glass);
}

@keyframes pulse-transcription {
  0%, 100% { 
    box-shadow: 0 0 0 0 rgba(245, 158, 11, 0.5);
  }
  50% { 
    box-shadow: 0 0 0 8px rgba(245, 158, 11, 0);
  }
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

/* Invite Dialog Styles (minimal - NModal handles most) */
.invite-icon {
  color: #3b82f6;
}

.invite-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 20px 0;
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

  /* 移动端时改为垂直布局 */
  .voice-main-content {
    flex-direction: column;
    gap: 12px;
  }

  .voice-users-container {
    flex: 1 1 auto;
  }

  .transcription-panel-container {
    flex: 1 1 auto;
    min-width: 0;
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
