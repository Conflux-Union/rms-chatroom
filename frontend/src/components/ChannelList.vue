<script setup lang="ts">
import { ref, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import type { Channel } from '../types'

const chat = useChatStore()
const auth = useAuthStore()
const showCreate = ref(false)
const newChannelName = ref('')
const newChannelType = ref<'text' | 'voice'>('text')

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
</script>

<template>
  <div class="channel-list">
    <div class="server-header">
      <h2>{{ chat.currentServer?.name || 'Select Server' }}</h2>
    </div>

    <div class="channels">
      <div class="channel-category">
        <span class="category-name">TEXT CHANNELS</span>
        <button v-if="auth.isAdmin" class="add-channel" @click="showCreate = true; newChannelType = 'text'">+</button>
      </div>
      <div
        v-for="channel in textChannels"
        :key="channel.id"
        class="channel"
        :class="{ active: chat.currentChannel?.id === channel.id }"
        @click="selectChannel(channel)"
      >
        <span class="channel-icon">#</span>
        <span class="channel-name">{{ channel.name }}</span>
      </div>

      <div class="channel-category">
        <span class="category-name">VOICE CHANNELS</span>
        <button v-if="auth.isAdmin" class="add-channel" @click="showCreate = true; newChannelType = 'voice'">+</button>
      </div>
      <div
        v-for="channel in voiceChannels"
        :key="channel.id"
        class="channel"
        :class="{ active: chat.currentChannel?.id === channel.id }"
        @click="selectChannel(channel)"
      >
        <span class="channel-icon">🔊</span>
        <span class="channel-name">{{ channel.name }}</span>
      </div>
    </div>

    <div v-if="showCreate" class="create-modal" @click.self="showCreate = false">
      <div class="modal-content">
        <h3>Create {{ newChannelType === 'text' ? 'Text' : 'Voice' }} Channel</h3>
        <input
          v-model="newChannelName"
          placeholder="Channel name"
          @keyup.enter="createChannel"
        />
        <div class="modal-actions">
          <button @click="showCreate = false">Cancel</button>
          <button class="primary" @click="createChannel">Create</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.channel-list {
  width: 240px;
  background: #2f3136;
  display: flex;
  flex-direction: column;
}

.server-header {
  padding: 12px 16px;
  border-bottom: 1px solid #202225;
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.2);
}

.server-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
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
  color: #8e9297;
  text-transform: uppercase;
}

.add-channel {
  background: transparent;
  border: none;
  color: #8e9297;
  cursor: pointer;
  font-size: 16px;
  padding: 0 8px;
}

.add-channel:hover {
  color: #dcddde;
}

.channel {
  display: flex;
  align-items: center;
  padding: 6px 8px;
  margin: 1px 8px;
  border-radius: 4px;
  cursor: pointer;
  color: #8e9297;
}

.channel:hover {
  background: #34373c;
  color: #dcddde;
}

.channel.active {
  background: #393c43;
  color: #fff;
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
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-content {
  background: #36393f;
  padding: 24px;
  border-radius: 8px;
  width: 300px;
}

.modal-content h3 {
  margin: 0 0 16px;
  color: #fff;
}

.modal-content input {
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 4px;
  background: #202225;
  color: #fff;
  margin-bottom: 16px;
  box-sizing: border-box;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.modal-actions button {
  padding: 8px 16px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  background: #4f545c;
  color: #fff;
}

.modal-actions button.primary {
  background: #5865f2;
}
</style>
