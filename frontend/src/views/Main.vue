<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import ServerList from '../components/ServerList.vue'
import ChannelList from '../components/ChannelList.vue'
import ChatArea from '../components/ChatArea.vue'
import VoicePanel from '../components/VoicePanel.vue'

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
  background: #36393f;
  color: #dcddde;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #36393f;
}

.no-channel {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #72767d;
}

.user-panel {
  position: fixed;
  bottom: 0;
  left: 72px;
  width: 240px;
  background: #292b2f;
  padding: 8px;
  border-top: 1px solid #202225;
}

.user-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.username {
  font-size: 14px;
  font-weight: 500;
}

.logout-btn {
  background: transparent;
  color: #b9bbbe;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  font-size: 12px;
}

.logout-btn:hover {
  color: #fff;
}
</style>
