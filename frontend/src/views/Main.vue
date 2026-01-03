<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'
import { useMusicStore } from '../stores/music'
import { useSwipe } from '../composables/useSwipe'
import ServerList from '../components/ServerList.vue'
import ChannelList from '../components/ChannelList.vue'
import ChatArea from '../components/ChatArea.vue'
import VoicePanel from '../components/VoicePanel.vue'
import VoiceControls from '../components/VoiceControls.vue'
import MusicPanel from '../components/MusicPanel.vue'
import { Music, Menu, X } from 'lucide-vue-next'

const auth = useAuthStore()
const chat = useChatStore()
const voice = useVoiceStore()
// Initialize music store early so WebSocket auto-connects when joining voice
useMusicStore()

const showMusicPanel = ref(false)
const showMobileSidebar = ref(false)
const appContainer = ref<HTMLElement | null>(null)

// Swipe gesture for mobile sidebar
const { onSwipeLeft, onSwipeRight } = useSwipe(appContainer, { threshold: 50 })
onSwipeRight(() => {
  if (window.innerWidth <= 768) {
    showMobileSidebar.value = true
  }
})
onSwipeLeft(() => {
  if (window.innerWidth <= 768) {
    showMobileSidebar.value = false
  }
})

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

// Close mobile sidebar when channel is selected
watch(
  () => chat.currentChannel,
  () => {
    showMobileSidebar.value = false
  }
)
</script>

<template>
  <div ref="appContainer" class="app-container">
    <!-- Mobile Header -->
    <div class="mobile-header">
      <button class="mobile-menu-btn" @click="showMobileSidebar = !showMobileSidebar">
        <X v-if="showMobileSidebar" :size="24" />
        <Menu v-else :size="24" />
      </button>
      <span class="mobile-title">{{ chat.currentChannel?.name || '选择频道' }}</span>
    </div>

    <!-- Mobile Sidebar Overlay -->
    <div 
      v-if="showMobileSidebar" 
      class="mobile-overlay" 
      @click="showMobileSidebar = false"
    ></div>

    <!-- Sidebar Container -->
    <div class="sidebar-container" :class="{ 'mobile-open': showMobileSidebar }">
      <ServerList />
      <ChannelList />
      <div class="user-panel">
        <VoiceControls />
        <div class="user-info">
          <span class="username">{{ auth.user?.nickname || auth.user?.username }}</span>
          <button class="logout-btn" @click="auth.logout()">退出</button>
        </div>
      </div>
    </div>

    <div class="main-content">
      <ChatArea v-if="chat.currentChannel?.type === 'text'" />
      <VoicePanel v-else-if="chat.currentChannel?.type === 'voice'" />
      <div v-else class="no-channel">
        <p>选择一个频道开始聊天</p>
      </div>
    </div>
    
    <!-- Music Button (shown when connected to voice) -->
    <button 
      v-if="voice.isConnected" 
      class="music-toggle-btn glow-effect"
      @click="showMusicPanel = !showMusicPanel"
      :class="{ active: showMusicPanel }"
    >
      <Music :size="24" />
    </button>
    
    <!-- Music Panel Sidebar -->
    <Transition name="slide">
      <div v-if="showMusicPanel" class="music-sidebar">
        <MusicPanel />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  height: 100vh;
  height: 100dvh; /* Dynamic viewport height for mobile browsers */
  color: var(--color-text-main);
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
}

/* Mobile Header - Hidden on desktop */
.mobile-header {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 56px;
  background: var(--surface-glass-strong);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  z-index: 200;
  align-items: center;
  padding: 0 16px;
  gap: 12px;
}

.mobile-menu-btn {
  background: transparent;
  border: none;
  color: var(--color-text-main);
  cursor: pointer;
  padding: 8px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
}

.mobile-menu-btn:hover {
  background: var(--surface-glass);
}

.mobile-title {
  font-weight: 600;
  font-size: 16px;
  color: var(--color-text-main);
}

.mobile-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 149;
}

/* Sidebar Container */
.sidebar-container {
  display: flex;
  flex-shrink: 0;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
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
  background: transparent;
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

/* Music Toggle Button */
.music-toggle-btn {
  position: fixed;
  bottom: 80px;
  right: 20px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--color-gradient-primary);
  border: none;
  font-size: 24px;
  cursor: pointer;
  box-shadow: var(--shadow-glow);
  transition: all 0.3s ease;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}

.music-toggle-btn:hover {
  transform: scale(1.1);
}

.music-toggle-btn.active {
  background: var(--color-primary);
}

/* Music Sidebar */
.music-sidebar {
  position: fixed;
  top: 0;
  right: 0;
  width: 380px;
  height: 100vh;
  height: 100dvh;
  background: var(--surface-glass-strong, rgba(20, 20, 30, 0.95));
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  z-index: 99;
  display: flex;
  flex-direction: column;
}

/* Slide transition */
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.3s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .mobile-header {
    display: flex;
  }

  .mobile-overlay {
    display: block;
  }

  .app-container {
    flex-direction: column;
    padding-top: 56px;
  }

  .sidebar-container {
    position: fixed;
    top: 56px;
    left: 0;
    bottom: 0;
    width: 312px;
    z-index: 150;
    background: var(--surface-glass-strong);
    backdrop-filter: blur(var(--blur-strength));
    -webkit-backdrop-filter: blur(var(--blur-strength));
    transform: translateX(-100%);
    transition: transform 0.3s ease;
    flex-direction: row;
    border-right: 1px solid rgba(255, 255, 255, 0.1);
  }

  .sidebar-container.mobile-open {
    transform: translateX(0);
  }

  .user-panel {
    position: absolute;
    bottom: 0;
    left: 72px;
    width: 240px;
  }

  .main-content {
    flex: 1;
    height: calc(100vh - 56px);
    height: calc(100dvh - 56px);
  }

  .music-sidebar {
    width: 100%;
    max-width: 100%;
  }

  .music-toggle-btn {
    bottom: 20px;
    right: 16px;
    width: 48px;
    height: 48px;
  }
}

@media (max-width: 480px) {
  .sidebar-container {
    width: 100%;
  }

  .user-panel {
    left: 72px;
    width: calc(100% - 72px);
  }
}
</style>
