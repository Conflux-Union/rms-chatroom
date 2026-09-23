<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { useVoiceStore } from '../stores/voice'
import { useMusicStore } from '../stores/music'
import { useAuthStore } from '../stores/auth'
import { useSwipe } from '../composables/useSwipe'
import { useChatWebSocket } from '../composables/useChatWebSocket'
import { useGlobalWebSocket } from '../composables/useGlobalWebSocket'
import { useMentionNotification } from '../composables/useMentionNotification'
import { useDesktopNotifications } from '../composables/useDesktopNotifications'
import { fetchAndMergeServerPositions } from '../composables/useReadPosition'
import { printConsoleEasterEgg } from '../utils/consoleArt'
import { t } from '../i18n'
import { CHANGELOG, CHANGELOG_HISTORY, mergeChangelog } from '../changelog'
import { VERSION_CODE } from '../version'
import ServerList from '../components/ServerList.vue'
import ChannelList from '../components/ChannelList.vue'
import ChatArea from '../components/ChatArea.vue'
import VoicePanel from '../components/VoicePanel.vue'
import MusicPanel from '../components/MusicPanel.vue'
import ChangelogDialog from '../components/ChangelogDialog.vue'
import { Music, Menu, X } from 'lucide-vue-next'

const auth = useAuthStore()
const chat = useChatStore()
const voice = useVoiceStore()
const route = useRoute()
const router = useRouter()
// Initialize music store early so WebSocket auto-connects when joining voice
const music = useMusicStore()

// Initialize global chat WebSocket (persists across channel switches)
const chatWs = useChatWebSocket()
const { playMentionSound, refetchFromServer: refetchMentionFlags } = useMentionNotification()
const { showMessageNotification, setUnreadAttention } = useDesktopNotifications()

// Resolve a channel display name across all loaded servers (for toasts).
function findChannelName(channelId: number): string {
  for (const server of chat.servers) {
    const channel = server.channels?.find(c => c.id === channelId)
    if (channel) return channel.name
  }
  return t('app.newMessage')
}

// The window counts as "attended" only when it is both visible and focused.
function isWindowActive(): boolean {
  return document.hasFocus() && document.visibilityState === 'visible'
}

// Handle incoming chat WebSocket messages
chatWs.onMessage((data) => {
  if (data.type === 'message') {
    const messageChannelId = data.channel_id

    // Add message to store if it's for the current channel
    if (messageChannelId === chat.currentChannel?.id) {
      const newMessage = {
        id: data.id,
        channel_id: messageChannelId,
        user_id: data.user_id,
        username: data.username,
        avatar_url: data.avatar_url,
        content: data.content,
        created_at: data.created_at,
        attachments: data.attachments || [],
        is_deleted: false,
        deleted_by: undefined,
        deleted_by_username: undefined,
        edited_at: undefined,
        reply_to_id: data.reply_to_id,
        reply_to: data.reply_to,
        mentions: data.mentions || [],
        source_platform: data.source_platform,
        forward_meta: data.forward_meta,
      }
      chat.addMessage(newMessage)
    }

    const currentUserId = auth.user?.id
    const isOwnMessage = data.user_id === currentUserId
    const isCurrentChannel = messageChannelId === chat.currentChannel?.id

    // Attention for channels the user is not currently viewing. The unread
    // badge count itself is server-derived and arrives via /ws/global.
    if (!isOwnMessage && !isCurrentChannel) {
      setUnreadAttention(true)

      if (!isWindowActive()) {
        const channelName = findChannelName(messageChannelId)
        const preview = data.content
          ? `${data.username}: ${data.content}`
          : t('app.sentAttachment', { name: data.username })
        showMessageNotification(channelName, preview)
      }
    }

    // Check if current user is mentioned (for any channel). Broadcasts carry
    // either {id, username} objects (forwarded sources) or plain username
    // strings (in-app WS messages); the badge flag itself is server-derived.
    if (data.mentions && data.mentions.length > 0) {
      const ownUsername = auth.user?.username
      const isMentioned = data.mentions.some(
        (mention: { id: number; username: string } | string) =>
          typeof mention === 'string' ? mention === ownUsername : mention.id === currentUserId
      )

      if (isMentioned && !isOwnMessage) {
        // Play sound for mentions (even in current channel)
        if (document.visibilityState === 'visible') {
          playMentionSound(messageChannelId, data.id)
        }
      }
    }
  } else if (data.type === 'message_deleted') {
    const message = chat.messages.find(m => m.id === data.message_id)
    if (message) {
      message.is_deleted = true
      message.deleted_by = data.deleted_by
      message.deleted_by_username = data.deleted_by_username
    }
  } else if (data.type === 'message_edited') {
    const message = chat.messages.find(m => m.id === data.message_id)
    if (message) {
      message.content = data.content
      message.edited_at = data.edited_at
    }
  } else if (data.type === 'reaction_added') {
    const message = chat.messages.find(m => m.id === data.message_id)
    if (message) {
      if (!message.reactions) message.reactions = []
      const existingGroup = message.reactions.find(r => r.emoji === data.emoji)
      if (existingGroup) {
        if (!existingGroup.users.some(u => u.id === data.user_id)) {
          existingGroup.count++
          existingGroup.users.push({ id: data.user_id, username: data.username })
        }
      } else {
        message.reactions.push({
          emoji: data.emoji,
          count: 1,
          users: [{ id: data.user_id, username: data.username }],
        })
      }
    }
  } else if (data.type === 'reaction_removed') {
    const message = chat.messages.find(m => m.id === data.message_id)
    if (message && message.reactions) {
      const groupIndex = message.reactions.findIndex(r => r.emoji === data.emoji)
      if (groupIndex !== -1) {
        const group = message.reactions[groupIndex]
        const userIndex = group.users.findIndex(u => u.id === data.user_id)
        if (userIndex !== -1) {
          group.users.splice(userIndex, 1)
          group.count--
          if (group.count <= 0) {
            message.reactions.splice(groupIndex, 1)
          }
        }
      }
    }
  } else if (data.type === 'error' && data.code === 'muted') {
    // User is muted - update store so ChatArea can display it
    chat.setMutedByWs(true, data.message || t('app.youAreMuted'))
  }
})

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

// ---------------------------------------------------------------------------
// Channel / message deep links & URL sync
//
// `/:serverId/:channelId(/:messageId)` opens a specific channel (and message;
// the message part is consumed by ChatArea). Selection changes mirror back
// into the URL: a server-driven default selection replaces the history entry,
// a user click pushes one — so the browser back button returns to the
// previously viewed channel.
// ---------------------------------------------------------------------------

// True while a route target is being resolved into store state. Suppresses the
// default first-channel selection and URL mirroring for the intermediate
// states, which would otherwise push spurious history entries.
const isResolvingRouteChannel = ref(false)

function routeChannelTarget(): { serverId: number; channelId: number } | null {
  if (route.name !== 'ChannelPath' && route.name !== 'MessagePath') return null
  const serverId = Number(route.params.serverId)
  const channelId = Number(route.params.channelId)
  if (!Number.isInteger(serverId) || !Number.isInteger(channelId)) return null
  return { serverId, channelId }
}

// Select the channel targeted by the current route. Redirects to NotFound when
// the target does not exist or is not accessible — unless the server list
// itself failed to load, then fall through to the plain boot state.
async function openChannelFromRoute(): Promise<void> {
  const target = routeChannelTarget()
  if (!target) return
  if (chat.currentServer?.id === target.serverId && chat.currentChannel?.id === target.channelId) return

  isResolvingRouteChannel.value = true
  try {
    const serverListLoaded = chat.servers.length > 0
    if (chat.currentServer?.id !== target.serverId) {
      const server = await chat.fetchServer(target.serverId)
      if (!server) {
        if (serverListLoaded) router.replace({ name: 'NotFound' })
        return
      }
    }
    const channel = chat.currentServer?.channels?.find(c => c.id === target.channelId)
    if (!channel) {
      if (serverListLoaded) router.replace({ name: 'NotFound' })
      return
    }
    chat.setCurrentChannel(channel)
  } finally {
    isResolvingRouteChannel.value = false
  }
}

// --- WebSocket reconnect resync -------------------------------------------
// The sockets only deliver what arrives while connected; on every reconnect,
// re-pull server-authoritative state so the outage window leaves no gap.
const globalWs = useGlobalWebSocket()

let chatWsHadConnected = false
watch(chatWs.isConnected, (connected) => {
  if (!connected) return
  if (!chatWsHadConnected) {
    chatWsHadConnected = true
    return
  }
  chat.backfillAfterReconnect()
}, { immediate: true })

let globalWsHadConnected = false
watch(globalWs.isConnected, (connected) => {
  if (!connected) return
  if (!globalWsHadConnected) {
    globalWsHadConnected = true
    return
  }
  // Voice presence pushes resume only on the next change; read marks and
  // mention flags are server-authoritative overlays on local state.
  if (chat.currentServer) chat.fetchAllVoiceChannelUsers()
  fetchAndMergeServerPositions()
  refetchMentionFlags()
}, { immediate: true })

// Logged out: drop the sockets — they keep operating under the revoked
// identity, and with no token getUrl() can never reconnect them anyway.
watch(() => auth.token, (token) => {
  if (token) return
  chatWs.disconnect()
  globalWs.disconnect()
  music.disconnectMusicWs()
})

// First open of a new release: web and desktop both learn their version at
// build time (version.ts / changelog.ts are generated per release), so a
// stored-version mismatch means this is the first launch after an update.
// The stored code also marks where the user came from, so skipped versions
// merge into one dialog.
const showChangelog = ref(false)
const CHANGELOG_SEEN_KEY = 'rms-changelog-seen-code'

const mergedChangelog = ref(CHANGELOG)

function maybeShowChangelog() {
  if (VERSION_CODE <= 0) return // dev placeholder builds carry no changelog
  if (CHANGELOG.code !== VERSION_CODE) return
  if (!CHANGELOG.improvements.length && !CHANGELOG.fixes.length) return
  let seen: number | null = null
  try {
    const raw = localStorage.getItem(CHANGELOG_SEEN_KEY)
    if (raw != null && raw !== String(VERSION_CODE)) {
      const parsed = Number(raw)
      seen = Number.isFinite(parsed) ? parsed : null
    } else if (raw === String(VERSION_CODE)) {
      return // already saw this version
    }
  } catch {
    // Storage unavailable (private mode): skip rather than nag every launch.
    return
  }
  mergedChangelog.value = mergeChangelog(CHANGELOG_HISTORY, seen, VERSION_CODE)
  if (!mergedChangelog.value.improvements.length && !mergedChangelog.value.fixes.length) return
  showChangelog.value = true
}

function closeChangelog() {
  showChangelog.value = false
  try {
    localStorage.setItem(CHANGELOG_SEEN_KEY, String(VERSION_CODE))
  } catch {
    // Same as above: without storage the dialog is skipped anyway.
  }
}

onMounted(async () => {
  // Connect global chat WebSocket
  chatWs.connect()

  maybeShowChangelog()

  await chat.fetchServers()
  if (routeChannelTarget()) {
    await openChannelFromRoute()
  } else {
    const firstServer = chat.servers[0]
    if (firstServer) {
      await chat.fetchServer(firstServer.id)
    }
  }

  printConsoleEasterEgg()
})

watch(
  () => chat.currentServer,
  (server) => {
    // A route deep link owns the selection while resolving; the default
    // first-channel pick must not race it.
    if (isResolvingRouteChannel.value) return
    if (server && server.channels && server.channels.length > 0) {
      // 按照频道列表的排序逻辑选择第一个文字频道
      // 1. 首先检查独立频道（按 top_position 排序）
      // 2. 然后检查频道组内的频道（按组的 position 和频道的 position 排序）
      
      const channelGroups = server.channelGroups?.sort((a, b) => a.position - b.position) || []
      const ungroupedChannels = server.channels
        .filter((c) => c.group_id == null)
        .sort((a, b) => a.top_position - b.top_position)
      
      // 构建混合列表（与 ChannelList.vue 中的 mixedList 逻辑一致）
      type ListItem = { type: 'group'; data: typeof channelGroups[0] } | { type: 'channel'; data: typeof server.channels[0] }
      const items: ListItem[] = []
      
      for (const group of channelGroups) {
        items.push({ type: 'group', data: group })
      }
      for (const channel of ungroupedChannels) {
        items.push({ type: 'channel', data: channel })
      }
      
      // 按统一位置排序
      items.sort((a, b) => {
        const posA = a.type === 'group' ? a.data.position : a.data.top_position
        const posB = b.type === 'group' ? b.data.position : b.data.top_position
        return posA - posB
      })
      
      // 遍历排序后的列表，找到第一个文字频道
      for (const item of items) {
        if (item.type === 'channel' && item.data.type === 'TEXT') {
          // 找到独立的文字频道
          chat.setCurrentChannel(item.data)
          return
        } else if (item.type === 'group') {
          // 检查频道组内的频道
          const groupChannels = server.channels
            .filter((c) => c.group_id === item.data.id)
            .sort((a, b) => a.position - b.position)
          
          const firstTextChannel = groupChannels.find((c) => c.type === 'TEXT')
          if (firstTextChannel) {
            chat.setCurrentChannel(firstTextChannel)
            return
          }
        }
      }
      
      // 如果上面都没找到，回退到任意文字频道
      const anyTextChannel = server.channels.find((c) => c.type === 'TEXT')
      if (anyTextChannel) {
        chat.setCurrentChannel(anyTextChannel)
      }
    }
  }
)

// Mirror selection changes into the URL. Server switches land on their default
// channel as one update → replace (back returns to the previous server's
// channel); clicks within a server → push (back returns to the previous
// channel).
watch(
  [() => chat.currentServer, () => chat.currentChannel],
  ([server, channel], [prevServer]) => {
    if (isResolvingRouteChannel.value) return
    if (!server || !channel) return
    // Already reflected in the URL (deep-link mount, back/forward navigation,
    // or a re-select of the same channel).
    if (
      (route.name === 'ChannelPath' || route.name === 'MessagePath') &&
      Number(route.params.channelId) === channel.id
    ) {
      return
    }
    const target = {
      name: 'ChannelPath',
      params: { serverId: String(server.id), channelId: String(channel.id) },
    }
    if (server !== prevServer) {
      router.replace(target)
    } else {
      router.push(target)
    }
  }
)

// Route → state: browser back/forward (or a manual URL edit) must select the
// channel the path points at, across servers if needed.
watch(
  () => [route.name, route.params.serverId, route.params.channelId],
  () => {
    openChannelFromRoute()
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
      <span class="mobile-title">{{ chat.currentChannel?.name || t('app.selectChannel') }}</span>
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
      <!-- <div class="user-panel">
        <VoiceControls />
        <div class="user-info">
          <span class="username">{{ auth.user?.nickname || auth.user?.username }}</span>
          <button class="logout-btn" @click="auth.logout()">退出</button>
        </div>
      </div> -->
    </div>

    <div class="main-content">
      <ChatArea v-if="chat.currentChannel?.type === 'TEXT' || chat.currentChannel?.type === 'FORWARD'" />
      <VoicePanel v-else-if="chat.currentChannel?.type === 'VOICE'" />
      <div v-else class="no-channel">
        <p>{{ t('app.noChannelPrompt') }}</p>
      </div>
    </div>
    
    <!-- Music Button (shown when connected to voice) -->
    <button 
      v-if="voice.isConnected" 
      class="music-toggle-btn "
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

    <!-- First open of a new release: show what changed (merged across
         skipped versions) -->
    <ChangelogDialog
      v-if="showChangelog"
      :show="true"
      :changelog="mergedChangelog"
      @close="closeChangelog"
    />
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  height: 100vh;
  height: 100dvh;
  color: var(--color-text-main);
  /* Stay transparent: the ink-wash shows vivid here. Readability is handled
     per-region — frosted rails and frosted message bubbles — so the ink can
     breathe across the chat canvas instead of being washed out globally. */
  background: transparent;
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
  border-bottom: 1px solid var(--zhimo-border-strong);
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
  /* Frosted rail group: server + channel rails are dense small text, so they
     sit on one frosted sheet (ink ghosts through, stays readable). */
  background: var(--zhimo-surface-bg);
  backdrop-filter: var(--zhimo-surface-blur);
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

.no-channel p {
  margin: 0;
  padding: 12px 24px;
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-surface-bg);
  backdrop-filter: var(--zhimo-surface-blur);
}

/* .user-panel {
  position: fixed;
  bottom: 0;
  margin: 8px;
  padding: 8px;
  border-top: 1px dashed rgba(128, 128, 128, 0.4);
  background: transparent;
} */

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
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-seal);
  color: var(--zhimo-accent-fg);
  border: 1px solid var(--zhimo-seal-hover);
  font-size: 24px;
  cursor: pointer;
  box-shadow: var(--zhimo-shadow-sm);
  transition: background var(--transition-fast), border-color var(--transition-fast), transform var(--transition-fast);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}

.music-toggle-btn:hover {
  background: var(--zhimo-seal-hover);
  transform: translateY(-1px);
}

.music-toggle-btn.active {
  background: var(--zhimo-accent);
  border-color: var(--zhimo-accent);
}

/* Music Sidebar */
.music-sidebar {
  position: fixed;
  top: 0;
  right: 0;
  width: 380px;
  height: 100vh;
  height: 100dvh;
  background: var(--zhimo-surface-bg);
  backdrop-filter: var(--zhimo-surface-blur);
  border-left: 1px solid var(--zhimo-border-strong);
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
    transform: translateX(-100%);
    transition: transform 0.3s ease;
    flex-direction: row;
    box-shadow: var(--zhimo-shadow-md);
    border-right: 1px solid var(--zhimo-border-strong);
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
