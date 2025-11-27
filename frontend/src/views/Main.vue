<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import ServerList from '../components/ServerList.vue'
import ChannelList from '../components/ChannelList.vue'
import ChatArea from '../components/ChatArea.vue'
import VoicePanel from '../components/VoicePanel.vue'
import VoiceControls from '../components/VoiceControls.vue'

const auth = useAuthStore()
const chat = useChatStore()

onMounted(async () => {
  await chat.fetchServers()
  const firstServer = chat.servers[0]
  if (firstServer) {
    await chat.fetchServer(firstServer.id)
  }
})

watch(
  () => chat.currentServer,
  (server) => {
    if (server && server.channels && server.channels.length > 0) {
      const textChannel = server.channels.find((c) => c.type === 'text')
      if (textChannel) {
        chat.setCurrentChannel(textChannel)
      }
    }
  }
)
</script>

<template>
  <div class="app-container">
    <ServerList />
    <ChannelList />
    <div class="main-content">
      <ChatArea v-if="chat.currentChannel?.type === 'text'" />
      <VoicePanel v-else-if="chat.currentChannel?.type === 'voice'" />
      <div v-else class="no-channel">
        <p>Select a channel to start</p>
      </div>
    </div>
    <div class="user-panel">
      <VoiceControls />
      <div class="user-info">
        <span class="username">{{ auth.user?.nickname || auth.user?.username }}</span>
        <button class="logout-btn" @click="auth.logout()">Logout</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  height: 100vh;
  color: var(--color-text-main);
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.no-channel {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  color: var(--color-text-muted);
}

.user-panel {
  position: fixed;
  bottom: 0;
  left: 72px;
  width: 240px;
  padding: 8px;
  border-top: 1px dashed rgba(128, 128, 128, 0.4);
}

.user-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.username {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-main);
}

.logout-btn {
  background: transparent;
  color: var(--color-text-muted);
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  font-size: 12px;
  transition: color var(--transition-fast);
}

.logout-btn:hover {
  color: var(--color-primary);
}
</style>
