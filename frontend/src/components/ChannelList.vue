<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useVoiceStore } from '../stores/voice'
import { Volume2, MicOff, Crown } from 'lucide-vue-next'
import type { Channel } from '../types'

const chat = useChatStore()
const auth = useAuthStore()
const voice = useVoiceStore()
const showCreate = ref(false)
const newChannelName = ref('')
const newChannelType = ref<'text' | 'voice'>('text')
const contextMenu = ref<{ show: boolean; x: number; y: number; channelId: number | null }>({
  show: false, x: 0, y: 0, channelId: null
})

// Voice user context menu state
const userContextMenu = ref<{ show: boolean; x: number; y: number; channelId: number | null; userId: string | null }>({
  show: false, x: 0, y: 0, channelId: null, userId: null
})

// Refresh interval for voice channel users
let voiceUsersInterval: ReturnType<typeof setInterval> | null = null

function startVoiceUsersPolling() {
  stopVoiceUsersPolling()
  chat.fetchAllVoiceChannelUsers()
  // Also sync host mode status if connected to voice
  if (voice.isConnected) {
    voice.fetchHostModeStatus()
  }
  voiceUsersInterval = setInterval(() => {
    chat.fetchAllVoiceChannelUsers()
    if (voice.isConnected) {
      voice.fetchHostModeStatus()
    }
  }, 5000)
}

function stopVoiceUsersPolling() {
  if (voiceUsersInterval) {
    clearInterval(voiceUsersInterval)
    voiceUsersInterval = null
  }
}

watch(() => chat.currentServer, (server) => {
  if (server) {
    startVoiceUsersPolling()
  } else {
    stopVoiceUsersPolling()
  }
}, { immediate: true })

onUnmounted(() => {
  stopVoiceUsersPolling()
})

const textChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.type === 'text') || []
)

const voiceChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.type === 'voice') || []
)

function selectChannel(channel: Channel) {
  chat.setCurrentChannel(channel)
}

async function createChannel() {
  if (!newChannelName.value.trim() || !chat.currentServer) return
  await chat.createChannel(chat.currentServer.id, newChannelName.value.trim(), newChannelType.value)
  newChannelName.value = ''
  showCreate.value = false
}

function showContextMenu(event: MouseEvent, channelId: number) {
  event.preventDefault()
  contextMenu.value = { show: true, x: event.clientX, y: event.clientY, channelId }
}

function hideContextMenu() {
  contextMenu.value = { show: false, x: 0, y: 0, channelId: null }
  userContextMenu.value = { show: false, x: 0, y: 0, channelId: null, userId: null }
}

function showUserContextMenu(event: MouseEvent, channelId: number, userId: string) {
  event.preventDefault()
  event.stopPropagation()
  userContextMenu.value = { show: true, x: event.clientX, y: event.clientY, channelId, userId }
}

async function muteVoiceUser() {
  if (!userContextMenu.value.channelId || !userContextMenu.value.userId) return
  await voice.muteParticipant(userContextMenu.value.userId, true)
  hideContextMenu()
}

async function deleteChannel() {
  if (!contextMenu.value.channelId || !chat.currentServer) return
  if (confirm('确定要删除此频道吗？')) {
    await chat.deleteChannel(chat.currentServer.id, contextMenu.value.channelId)
  }
  hideContextMenu()
}
</script>

<template>
  <div class="channel-list" @click="hideContextMenu">
    <div class="server-header">
      <h2>{{ chat.currentServer?.name || '选择服务器' }}</h2>
    </div>

    <div class="channels">
      <div class="channel-category">
        <span class="category-name">文字频道</span>
        <button v-if="auth.isAdmin" class="add-channel" @click="showCreate = true; newChannelType = 'text'">+</button>
      </div>
      <div
        v-for="channel in textChannels"
        :key="channel.id"
        class="channel glow-effect"
        :class="{ active: chat.currentChannel?.id === channel.id }"
        @click="selectChannel(channel)"
        @contextmenu="auth.isAdmin ? showContextMenu($event, channel.id) : undefined"
      >
        <span class="channel-icon">#</span>
        <span class="channel-name">{{ channel.name }}</span>
      </div>

      <div class="channel-category">
        <span class="category-name">语音频道</span>
        <button v-if="auth.isAdmin" class="add-channel" @click="showCreate = true; newChannelType = 'voice'">+</button>
      </div>
      <div
        v-for="channel in voiceChannels"
        :key="channel.id"
        class="voice-channel-wrapper"
      >
        <div
          class="channel glow-effect"
          :class="{ active: chat.currentChannel?.id === channel.id }"
          @click="selectChannel(channel)"
          @contextmenu="auth.isAdmin ? showContextMenu($event, channel.id) : undefined"
        >
          <Volume2 class="channel-icon" :size="18" />
          <span class="channel-name">{{ channel.name }}</span>
          <span v-if="chat.getVoiceChannelUsers(channel.id).length > 0" class="user-count">
            {{ chat.getVoiceChannelUsers(channel.id).length }}
          </span>
        </div>
        <div
          v-if="chat.getVoiceChannelUsers(channel.id).length > 0"
          class="voice-users-list"
        >
          <div
            v-for="user in chat.getVoiceChannelUsers(channel.id)"
            :key="user.id"
            class="voice-user-item"
            @contextmenu="auth.isAdmin ? showUserContextMenu($event, channel.id, user.id) : undefined"
          >
            <div class="voice-user-avatar-wrapper">
              <span class="voice-user-avatar">{{ user.name.charAt(0).toUpperCase() }}</span>
              <Crown v-if="user.is_host" class="voice-user-host-badge" :size="10" />
            </div>
            <span class="voice-user-name">{{ user.name }}</span>
            <MicOff v-if="user.is_muted" class="voice-user-muted" :size="12" />
          </div>
        </div>
      </div>
    </div>

    <!-- Channel Context Menu -->
    <div
      v-if="contextMenu.show && auth.isAdmin"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item delete" @click="deleteChannel">删除频道</div>
    </div>

    <!-- Voice User Context Menu -->
    <div
      v-if="userContextMenu.show && auth.isAdmin"
      class="context-menu"
      :style="{ left: userContextMenu.x + 'px', top: userContextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item" @click="muteVoiceUser">静音麦克风</div>
    </div>

    <div v-if="showCreate" class="create-modal" @click.self="showCreate = false">
      <div class="modal-content">
        <h3>创建{{ newChannelType === 'text' ? '文字' : '语音' }}频道</h3>
        <input
          v-model="newChannelName"
          placeholder="频道名称"
          @keyup.enter="createChannel"
        />
        <div class="modal-actions">
          <button class="glow-effect" @click="showCreate = false">取消</button>
          <button class="primary glow-effect" @click="createChannel">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.channel-list {
  width: 240px;
  display: flex;
  flex-direction: column;
  border-right: 1px dashed rgba(128, 128, 128, 0.4);
}

.server-header {
  padding: 12px 16px;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
}

.server-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.channels {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.channel-category {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 8px 4px 16px;
}

.category-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
}

.add-channel {
  background: transparent;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 16px;
  padding: 0 8px;
  transition: color var(--transition-fast);
}

.add-channel:hover {
  color: var(--color-primary);
}

.channel {
  display: flex;
  align-items: center;
  padding: 6px 8px;
  margin: 1px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-muted);
  transition: all var(--transition-fast);
}

.channel:hover {
  background: var(--surface-glass);
  color: var(--color-text-main);
}

.channel.active {
  background: var(--surface-glass-strong);
  color: var(--color-text-main);
}

.channel-icon {
  margin-right: 6px;
  font-size: 18px;
  opacity: 0.7;
}

.channel-name {
  font-size: 14px;
}

.create-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(10px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-content {
  background: var(--surface-glass-strong);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  padding: 24px;
  border-radius: var(--radius-lg);
  width: 300px;
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.modal-content h3 {
  margin: 0 0 16px;
  color: var(--color-text-main);
}

.modal-content input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  margin-bottom: 16px;
  box-sizing: border-box;
  transition: all var(--transition-fast);
}

.modal-content input:focus {
  background: var(--surface-glass-input-focus);
  border-color: rgba(255, 255, 255, 0.5);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.modal-actions button {
  padding: 10px 20px;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  background: var(--surface-glass);
  color: var(--color-text-main);
  transition: all var(--transition-fast);
}

.modal-actions button.primary {
  background: var(--color-gradient-primary);
  color: white;
  box-shadow: var(--shadow-glow);
}

.modal-actions button:hover {
  transform: translateY(-2px);
}

.modal-actions button.primary:hover {
  filter: brightness(1.1);
}

.context-menu {
  position: fixed;
  background: var(--surface-glass-strong);
  backdrop-filter: blur(var(--blur-strength));
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--radius-md);
  padding: 6px;
  min-width: 140px;
  z-index: 1001;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
}

.context-menu-item {
  padding: 8px 12px;
  cursor: pointer;
  border-radius: var(--radius-sm);
  color: var(--color-text-main);
  font-size: 14px;
  transition: background var(--transition-fast);
}

.context-menu-item:hover {
  background: var(--surface-glass);
}

.context-menu-item.delete {
  color: var(--color-danger);
}

.context-menu-item.delete:hover {
  background: rgba(237, 66, 69, 0.2);
}

.voice-channel-wrapper {
  margin-bottom: 2px;
}

.user-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-muted);
  background: var(--surface-glass);
  padding: 1px 6px;
  border-radius: 10px;
}

.voice-users-list {
  padding-left: 28px;
  margin-bottom: 4px;
}

.voice-user-item {
  display: flex;
  align-items: center;
  padding: 4px 8px;
  margin: 2px 8px 2px 0;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  font-size: 13px;
}

.voice-user-avatar-wrapper {
  position: relative;
  margin-right: 8px;
}

.voice-user-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--color-gradient-primary);
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: 600;
  color: #fff;
  font-size: 10px;
}

.voice-user-host-badge {
  position: absolute;
  bottom: -2px;
  right: -4px;
  color: #f59e0b;
  filter: drop-shadow(0 0 2px rgba(0, 0, 0, 0.5));
}

.voice-user-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-user-muted {
  font-size: 12px;
  margin-left: 4px;
}
</style>
