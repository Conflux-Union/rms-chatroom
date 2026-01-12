<script setup lang="ts">
import { ref, computed, watch, onUnmounted, onMounted, nextTick } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useVoiceStore } from '../stores/voice'
import { useMentionNotification } from '../composables/useMentionNotification'
import { Volume2, MicOff, Crown, ChevronDown, ChevronRight } from 'lucide-vue-next'
import { NDropdown, NModal, NInput, NButton, NSpace, NSelect } from 'naive-ui'
import type { DropdownOption, SelectOption } from 'naive-ui'
import type { Channel, ChannelGroup } from '../types'
import VoiceControls from '../components/VoiceControls.vue'
import { VueDraggable } from 'vue-draggable-plus'

const chat = useChatStore()
const auth = useAuthStore()
const voice = useVoiceStore()
const { channelMentions, loadChannelMentions, hasUnreadMention } = useMentionNotification()
const showCreate = ref(false)
const newItemName = ref('')
const newCreateType = ref<'text' | 'voice' | 'group'>('text') // 创建类型：文字频道、语音频道、频道组
const newChannelGroupId = ref<number | string | null>(null) // 新频道所属的频道组 ('none' 表示无)

const collapsedGroups = ref<Set<number>>(new Set()) // 折叠的频道组

// Channel context menu state (NDropdown)
const channelDropdown = ref<{ show: boolean; x: number; y: number; channelId: number | null }>({
  show: false, x: 0, y: 0, channelId: null
})

// Channel Group context menu state
const groupDropdown = ref<{ show: boolean; x: number; y: number; groupId: number | null }>({
  show: false, x: 0, y: 0, groupId: null
})

// Voice user context menu state (NDropdown)
const userDropdown = ref<{ show: boolean; x: number; y: number; channelId: number | null; userId: string | null }>({
  show: false, x: 0, y: 0, channelId: null, userId: null
})

// Move channel to group dialog state
const showMoveChannelDialog = ref(false)
const moveChannelId = ref<number | null>(null)
const moveToGroupId = ref<number | string | null>(null)

// Rename group dialog state
const showRenameGroupDialog = ref(false)
const renameGroupId = ref<number | null>(null)
const renameGroupName = ref('')

// Dropdown options - computed to include dynamic group options
const channelDropdownOptions = computed((): DropdownOption[] => {
  const options: DropdownOption[] = [
    { label: '移动到频道组', key: 'move' },
    { label: '删除频道', key: 'delete', props: { style: { color: 'var(--color-danger)' } } }
  ]
  return options
})

const groupDropdownOptions: DropdownOption[] = [
  { label: '删除频道组', key: 'delete', props: { style: { color: 'var(--color-danger)' } } }
]

const userDropdownOptions: DropdownOption[] = [
  { label: '静音麦克风', key: 'mute' },
  { label: '踢出频道', key: 'kick', props: { style: { color: 'var(--color-danger)' } } }
]

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
    // Fetch channel groups when server changes
    chat.fetchChannelGroups(server.id)
    // Start polling for mentions
    chat.startMentionPolling()
  } else {
    stopVoiceUsersPolling()
    // Stop polling when no server
    chat.stopMentionPolling()
  }
}, { immediate: true })

onMounted(() => {
  // Load mention notifications on mount
  loadChannelMentions()
})

onUnmounted(() => {
  stopVoiceUsersPolling()
  chat.stopMentionPolling()
})

onUnmounted(() => {
  stopVoiceUsersPolling()
  chat.stopMentionPolling()
})

// Channel groups
const channelGroups = computed(() => 
  chat.currentServer?.channelGroups?.sort((a, b) => a.position - b.position) || []
)

// Ungrouped channels (channels without a group) - mixed text and voice
// Use == null to match both null and undefined
// Sort by top_position for unified top-level ordering
const ungroupedChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.group_id == null)?.sort((a, b) => a.top_position - b.top_position) || []
)

// Get all channels for a specific group (mixed text and voice)
function getGroupChannels(groupId: number) {
  return chat.currentServer?.channels?.filter((c) => c.group_id === groupId)?.sort((a, b) => a.position - b.position) || []
}

// Unified list of groups and ungrouped channels, sorted by unified position
// Groups use position, ungrouped channels use top_position
type ListItem = { type: 'group'; data: ChannelGroup } | { type: 'channel'; data: Channel }
const mixedList = computed((): ListItem[] => {
  const items: ListItem[] = []
  
  // Add all channel groups
  for (const group of channelGroups.value) {
    items.push({ type: 'group', data: group })
  }
  
  // Add all ungrouped channels
  for (const channel of ungroupedChannels.value) {
    items.push({ type: 'channel', data: channel })
  }
  
  // Sort by unified position (groups use position, channels use top_position)
  items.sort((a, b) => {
    const posA = a.type === 'group' ? a.data.position : (a.data as Channel).top_position
    const posB = b.type === 'group' ? b.data.position : (b.data as Channel).top_position
    return posA - posB
  })
  
  return items
})

// Toggle group collapse
function toggleGroupCollapse(groupId: number) {
  const newSet = new Set(collapsedGroups.value)
  if (newSet.has(groupId)) {
    newSet.delete(groupId)
  } else {
    newSet.add(groupId)
  }
  collapsedGroups.value = newSet
}

// Group select options for create channel modal
const groupSelectOptions = computed((): SelectOption[] => {
  const options: SelectOption[] = [{ label: '无 (独立频道)', value: 'none' }]
  for (const group of channelGroups.value) {
    options.push({ label: group.name, value: group.id })
  }
  return options
})

// Create type options
const createTypeOptions: SelectOption[] = [
  { label: '文字频道', value: 'text' },
  { label: '语音频道', value: 'voice' },
  { label: '频道组', value: 'group' }
]

const textChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.type === 'text') || []
)

const voiceChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.type === 'voice') || []
)

function selectChannel(channel: Channel) {
  chat.setCurrentChannel(channel)
}

// Unified create function for channel or group
async function createItem() {
  if (!newItemName.value.trim() || !chat.currentServer) return
  
  if (newCreateType.value === 'group') {
    // Create channel group
    await chat.createChannelGroup(chat.currentServer.id, newItemName.value.trim())
  } else {
    // Create channel (text or voice)
    const groupId = newChannelGroupId.value === 'none' ? null : (newChannelGroupId.value as number | null)
    await chat.createChannel(chat.currentServer.id, newItemName.value.trim(), newCreateType.value, groupId)
  }
  
  newItemName.value = ''
  newChannelGroupId.value = null
  newCreateType.value = 'text'
  showCreate.value = false
}

function showGroupContextMenu(event: MouseEvent, groupId: number) {
  event.preventDefault()
  event.stopPropagation()
  groupDropdown.value = { show: true, x: event.clientX, y: event.clientY, groupId }
}

async function handleGroupDropdownSelect(key: string) {
  if (key === 'delete') {
    await deleteChannelGroup()
  }
  groupDropdown.value.show = false
}

async function deleteChannelGroup() {
  if (!groupDropdown.value.groupId || !chat.currentServer) return
  if (confirm('确定要删除此频道组吗？组内的频道将变为独立频道。')) {
    await chat.deleteChannelGroup(chat.currentServer.id, groupDropdown.value.groupId)
  }
}

// Rename group dialog functions
function openRenameGroupDialog(groupId: number) {
  const group = channelGroups.value.find(g => g.id === groupId)
  if (!group) return
  renameGroupId.value = group.id
  renameGroupName.value = group.name
  showRenameGroupDialog.value = true
}

async function confirmRenameGroup() {
  if (!renameGroupId.value || !renameGroupName.value.trim() || !chat.currentServer) return
  await chat.updateChannelGroup(chat.currentServer.id, renameGroupId.value, { name: renameGroupName.value.trim() })
  showRenameGroupDialog.value = false
  renameGroupId.value = null
  renameGroupName.value = ''
}

// Move channel to group functions
function openMoveChannelDialog() {
  if (!channelDropdown.value.channelId) return
  const channel = chat.currentServer?.channels?.find(c => c.id === channelDropdown.value.channelId)
  if (!channel) return
  moveChannelId.value = channel.id
  moveToGroupId.value = channel.group_id ?? 'none'
  showMoveChannelDialog.value = true
}

async function confirmMoveChannel() {
  if (!moveChannelId.value || !chat.currentServer) return
  // Convert 'none' to -1 for API (ungroup)
  const groupId = moveToGroupId.value === 'none' ? -1 : (moveToGroupId.value as number)
  await chat.updateChannel(chat.currentServer.id, moveChannelId.value, { group_id: groupId })
  showMoveChannelDialog.value = false
  moveChannelId.value = null
  moveToGroupId.value = null
}

function showChannelContextMenu(event: MouseEvent, channelId: number) {
  event.preventDefault()
  channelDropdown.value = { show: true, x: event.clientX, y: event.clientY, channelId }
}

function hideAllDropdowns() {
  channelDropdown.value = { show: false, x: 0, y: 0, channelId: null }
  userDropdown.value = { show: false, x: 0, y: 0, channelId: null, userId: null }
  groupDropdown.value = { show: false, x: 0, y: 0, groupId: null }
}

function showUserContextMenu(event: MouseEvent, channelId: number, userId: string) {
  event.preventDefault()
  event.stopPropagation()
  userDropdown.value = { show: true, x: event.clientX, y: event.clientY, channelId, userId }
}

async function handleChannelDropdownSelect(key: string) {
  if (key === 'delete') {
    await deleteChannel()
  } else if (key === 'move') {
    openMoveChannelDialog()
  }
  channelDropdown.value.show = false
}

async function handleUserDropdownSelect(key: string) {
  if (key === 'mute') {
    await muteVoiceUser()
  } else if (key === 'kick') {
    await kickVoiceUser()
  }
  userDropdown.value.show = false
}

// Edit mode (only when admin toggles it on)
const editMode = ref(false)

// Server name rename dialog state
const showRenameServerDialog = ref(false)
const renameServerName = ref('')

// Inline edit state
const editingChannelId = ref<number | null>(null)
const editedName = ref('')

// Draggable list data (writable refs for vue-draggable-plus)
const draggableMixedList = ref<ListItem[]>([])
const draggableGroupChannels = ref<Map<number, Channel[]>>(new Map())
// Flag to prevent watch from overwriting during drag operations
const isReordering = ref(false)

// Sidebar width state for a full-height right-edge resizer
const width = ref<number>(300)

function startResizing(e: MouseEvent) {
  e.preventDefault()
  document.body.style.userSelect = 'none'
  const startX = e.clientX
  const startW = width.value
  function onMove(ev: MouseEvent) {
    const dx = ev.clientX - startX
    const newW = Math.min(520, Math.max(200, startW + dx))
    width.value = newW
  }
  function onUp() {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

onMounted(() => {
  // no-op for now but keeps lifecycle symmetric
})

function startInlineEdit(channel: Channel) {
  editingChannelId.value = channel.id
  editedName.value = channel.name
}

async function saveInlineEdit(channel: Channel) {
  if (!chat.currentServer || editingChannelId.value !== channel.id) {
    editingChannelId.value = null
    return
  }
  const name = editedName.value.trim()
  if (!name || name === channel.name) {
    editingChannelId.value = null
    return
  }
  await chat.updateChannel(chat.currentServer.id, channel.id, { name })
  editingChannelId.value = null
}

function cancelInlineEdit() {
  editingChannelId.value = null
  editedName.value = ''
}

function renameChannel(channel: Channel) {
  startInlineEdit(channel)
}

function openRenameServerDialog() {
  if (!chat.currentServer) return
  renameServerName.value = chat.currentServer.name
  showRenameServerDialog.value = true
}

async function confirmRenameServer() {
  if (!chat.currentServer || !renameServerName.value.trim()) return
  const name = renameServerName.value.trim()
  if (name === chat.currentServer.name) {
    showRenameServerDialog.value = false
    return
  }
  await chat.updateServer(chat.currentServer.id, { name })
  showRenameServerDialog.value = false
  renameServerName.value = ''
}

// Clear editing state when leaving edit mode
watch(editMode, (val) => {
  if (!val) {
    // blur active element so inputs commit and focus doesn't keep inline edit active
    try { (document.activeElement as HTMLElement | null)?.blur() } catch {}
    editingChannelId.value = null
    editedName.value = ''
    showRenameServerDialog.value = false
    renameServerName.value = ''
  }
})

// ============================================
// Draggable system using vue-draggable-plus
// ============================================

// Sync draggable mixed list with computed mixedList
watch(mixedList, (newList) => {
  // Skip sync during reordering to prevent race condition
  if (isReordering.value) return
  draggableMixedList.value = [...newList]
}, { immediate: true, deep: true })

// Sync group channels when server changes
watch([() => chat.currentServer?.channels, channelGroups], () => {
  // Skip sync during reordering to prevent race condition
  if (isReordering.value) return
  const map = new Map<number, Channel[]>()
  for (const group of channelGroups.value) {
    map.set(group.id, [...getGroupChannels(group.id)])
  }
  draggableGroupChannels.value = map
}, { immediate: true, deep: true })

// Get draggable channels for a group (returns writable array)
function getDraggableGroupChannels(groupId: number): Channel[] {
  if (!draggableGroupChannels.value.has(groupId)) {
    draggableGroupChannels.value.set(groupId, [...getGroupChannels(groupId)])
  }
  return draggableGroupChannels.value.get(groupId) || []
}

// Handle mixed list reorder (top-level: groups + ungrouped channels)
async function onMixedListEnd() {
  if (!chat.currentServer) return
  isReordering.value = true
  try {
    const newOrder = draggableMixedList.value.map(item => ({
      type: item.type,
      id: item.data.id
    }))
    await chat.reorderTopLevel(chat.currentServer.id, newOrder)
  } finally {
    isReordering.value = false
  }
}

// Handle group channels reorder (within a specific group)
async function onGroupChannelsEnd(groupId: number) {
  if (!chat.currentServer) return
  isReordering.value = true
  try {
    const channels = draggableGroupChannels.value.get(groupId) || []
    const channelIds = channels.map(c => c.id)
    await chat.reorderGroupChannels(chat.currentServer.id, groupId, channelIds)
  } finally {
    isReordering.value = false
  }
}

async function muteVoiceUser() {
  if (!userDropdown.value.channelId || !userDropdown.value.userId) return
  await voice.muteParticipant(userDropdown.value.userId, true)
  hideAllDropdowns()
}

async function kickVoiceUser() {
  if (!userDropdown.value.channelId || !userDropdown.value.userId) return
  await voice.kickParticipant(userDropdown.value.userId)
  hideAllDropdowns()
}

async function deleteChannel() {
  if (!channelDropdown.value.channelId || !chat.currentServer) return
  if (confirm('确定要删除此频道吗？')) {
    await chat.deleteChannel(chat.currentServer.id, channelDropdown.value.channelId)
  }
  hideAllDropdowns()
}
</script>

<template>
  <div class="channel-list" @click="hideAllDropdowns" :style="{ width: width + 'px' }">
    <div class="channel-list-content">
      <!-- Right-edge resizer covers full height of the channel-list -->
      <div class="resizer" @mousedown.stop.prevent="startResizing" title="拖拽调整侧栏宽度"></div>

      <div class="server-header">
        <div class="server-name-section">
          <h2 class="server-title">{{ chat.currentServer?.name || '选择服务器' }}</h2>
          <button 
            v-if="auth.isAdmin && editMode" 
            class="rename-server-btn"
            @click.stop="openRenameServerDialog"
          >
            重命名
          </button>
        </div>
        <div class="server-controls">
          <button v-if="auth.isAdmin" class="edit-toggle" @click.stop="editMode = !editMode">{{ editMode ? '退出编辑' : '编辑' }}</button>
        </div>
      </div>

      <div class="channels">
        <!-- Header with add button -->
        <div class="channel-category">
          <span class="category-name">频道</span>
          <button v-if="auth.isAdmin" class="add-channel-btn" @click.stop="showCreate = true; newCreateType = 'text'; newChannelGroupId = 'none'" title="创建频道/频道组">+</button>
        </div>

        <!-- Draggable mixed list of channel groups and ungrouped channels -->
        <VueDraggable
          v-model="draggableMixedList"
          :disabled="!auth.isAdmin || !editMode"
          :animation="200"
          handle=".drag-handle-group"
          ghost-class="drag-ghost"
          chosen-class="drag-chosen"
          drag-class="drag-dragging"
          @end="onMixedListEnd"
          class="draggable-list"
        >
          <!-- Channel Group -->
          <template v-for="item in draggableMixedList" :key="item.type + '-' + item.data.id">
          <div v-if="item.type === 'group'" class="channel-group" :class="{ collapsed: collapsedGroups.has(item.data.id) }">
            <div 
              class="channel-group-header glow-effect"
              @click.stop="toggleGroupCollapse(item.data.id)"
              @contextmenu.prevent="auth.isAdmin && editMode ? showGroupContextMenu($event, item.data.id) : undefined"
            >
              <ChevronDown v-if="!collapsedGroups.has(item.data.id)" :size="14" class="collapse-icon" />
              <ChevronRight v-else :size="14" class="collapse-icon" />
              <span class="group-name">{{ item.data.name }}</span>
              <span class="channel-count">({{ getGroupChannels(item.data.id).length }})</span>
              <button v-if="auth.isAdmin && editMode" class="rename-group-btn" @click.stop="openRenameGroupDialog(item.data.id)">重命名</button>
              <span v-if="editMode" class="drag-handle drag-handle-group" @click.stop>☰</span>
            </div>
            
            <!-- Group channels (mixed text and voice) -->
            <Transition name="slide-down">
              <VueDraggable
                v-if="!collapsedGroups.has(item.data.id)"
                :model-value="getDraggableGroupChannels(item.data.id)"
                @update:model-value="(val: Channel[]) => draggableGroupChannels.set(item.data.id, val)"
                :disabled="!auth.isAdmin || !editMode"
                :animation="200"
                handle=".drag-handle-channel"
                ghost-class="drag-ghost"
                chosen-class="drag-chosen"
                drag-class="drag-dragging"
                @end="() => onGroupChannelsEnd(item.data.id)"
                class="group-channels"
              >
                <template v-for="channel in getDraggableGroupChannels(item.data.id)" :key="channel.id">
                <!-- Text channel in group -->
                <div
                  v-if="channel.type === 'text'"
                  class="channel glow-effect"
                  :class="{ active: chat.currentChannel?.id === channel.id }"
                  @click="selectChannel(channel)"
                  @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
                >
                  <svg class="channel-icon" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 24 24"><g fill="none"><path d="M12 3a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2V3zm2.5 1a.5.5 0 0 0 0 1h6a.5.5 0 0 0 0-1h-6zm0 3a.5.5 0 0 0 0 1h6a.5.5 0 0 0 0-1h-6z" fill="currentColor"></path><path d="M5.25 3H11v1.5H5.25A1.75 1.75 0 0 0 3.5 6.25v8.5c0 .966.784 1.75 1.75 1.75h2.249v3.75l5.015-3.75h6.236a1.75 1.75 0 0 0 1.75-1.75V12h.5c.35 0 .687-.06 1-.17v2.92A3.25 3.25 0 0 1 18.75 18h-5.738L8 21.75a1.25 1.25 0 0 1-1.999-1V18h-.75A3.25 3.25 0 0 1 2 14.75v-8.5A3.25 3.25 0 0 1 5.25 3z" fill="currentColor"></path></g></svg>
                  <template v-if="editingChannelId === channel.id">
                    <input
                      class="inline-edit custom-input"
                      v-model="editedName"
                      @keyup.enter="saveInlineEdit(channel)"
                      @keyup.esc="cancelInlineEdit"
                      @blur="saveInlineEdit(channel)"
                      @click.stop
                      autofocus
                    />
                  </template>
                  <template v-else>
                    <span class="channel-name" @dblclick.stop="auth.isAdmin && editMode ? startInlineEdit(channel) : undefined">{{ channel.name }}</span>
                  </template>
                  <span v-if="hasUnreadMention(channel.id)" class="mention-badge">有人@我</span>
                  <div v-if="editMode" class="edit-actions" @click.stop>
                    <button v-if="editingChannelId !== channel.id" class="small" @click="renameChannel(channel)">重命名</button>
                    <span class="drag-handle drag-handle-channel">☰</span>
                  </div>
                </div>
                
                <!-- Voice channel in group -->
                <div v-else class="voice-channel-wrapper">
                  <div
                    class="channel glow-effect"
                    :class="{ active: chat.currentChannel?.id === channel.id }"
                    @click="selectChannel(channel)"
                    @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
                  >
                    <Volume2 class="channel-icon" :size="18" />
                    <template v-if="editingChannelId === channel.id">
                      <input
                        class="inline-edit custom-input"
                        v-model="editedName"
                        @keyup.enter="saveInlineEdit(channel)"
                        @keyup.esc="cancelInlineEdit"
                        @blur="saveInlineEdit(channel)"
                        @click.stop
                        autofocus
                      />
                    </template>
                    <template v-else>
                      <span class="channel-name" @dblclick.stop="auth.isAdmin && editMode ? startInlineEdit(channel) : undefined">{{ channel.name }}</span>
                    </template>
                    <span v-if="chat.getVoiceChannelUsers(channel.id).length > 0" class="user-count">
                      {{ chat.getVoiceChannelUsers(channel.id).length }}
                    </span>
                    <div v-if="editMode" class="edit-actions" @click.stop>
                      <button v-if="editingChannelId !== channel.id" class="small" @click="renameChannel(channel)">重命名</button>
                      <span class="drag-handle drag-handle-channel">☰</span>
                    </div>
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
                        <img
                          v-if="user.avatar_url"
                          :src="user.avatar_url"
                          :alt="user.name"
                          class="voice-user-avatar-img"
                          @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                        />
                        <span v-else class="voice-user-avatar">{{ user.name.charAt(0).toUpperCase() }}</span>
                        <Crown v-if="user.is_host" class="voice-user-host-badge" :size="10" />
                      </div>
                      <span class="voice-user-name">{{ user.name }}</span>
                      <MicOff v-if="user.is_muted" class="voice-user-muted" :size="12" />
                    </div>
                  </div>
                </div>
              </template>
              </VueDraggable>
            </Transition>
          </div>

          <!-- Ungrouped Text Channel -->
          <div
            v-else-if="item.type === 'channel' && item.data.type === 'text'"
            class="channel glow-effect"
            :class="{ active: chat.currentChannel?.id === item.data.id }"
            @click="selectChannel(item.data)"
            @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, item.data.id) : undefined"
          >
            <svg class="channel-icon" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 24 24"><g fill="none"><path d="M12 3a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2V3zm2.5 1a.5.5 0 0 0 0 1h6a.5.5 0 0 0 0-1h-6zm0 3a.5.5 0 0 0 0 1h6a.5.5 0 0 0 0-1h-6z" fill="currentColor"></path><path d="M5.25 3H11v1.5H5.25A1.75 1.75 0 0 0 3.5 6.25v8.5c0 .966.784 1.75 1.75 1.75h2.249v3.75l5.015-3.75h6.236a1.75 1.75 0 0 0 1.75-1.75V12h.5c.35 0 .687-.06 1-.17v2.92A3.25 3.25 0 0 1 18.75 18h-5.738L8 21.75a1.25 1.25 0 0 1-1.999-1V18h-.75A3.25 3.25 0 0 1 2 14.75v-8.5A3.25 3.25 0 0 1 5.25 3z" fill="currentColor"></path></g></svg>
            <template v-if="editingChannelId === item.data.id">
              <input
                class="inline-edit custom-input"
                v-model="editedName"
                @keyup.enter="saveInlineEdit(item.data)"
                @keyup.esc="cancelInlineEdit"
                @blur="saveInlineEdit(item.data)"
                @click.stop
                autofocus
              />
            </template>
            <template v-else>
              <span class="channel-name" @dblclick.stop="auth.isAdmin && editMode ? startInlineEdit(item.data) : undefined">{{ item.data.name }}</span>
            </template>
            <span v-if="hasUnreadMention(item.data.id)" class="mention-badge">有人@我</span>
            <div v-if="editMode" class="edit-actions" @click.stop>
              <button v-if="editingChannelId !== item.data.id" class="small" @click="renameChannel(item.data)">重命名</button>
              <span class="drag-handle drag-handle-group">☰</span>
            </div>
          </div>
          
          <!-- Ungrouped Voice Channel -->
          <div v-else-if="item.type === 'channel' && item.data.type === 'voice'" class="voice-channel-wrapper">
            <div
              class="channel glow-effect"
              :class="{ active: chat.currentChannel?.id === item.data.id }"
              @click="selectChannel(item.data)"
              @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, item.data.id) : undefined"
            >
              <Volume2 class="channel-icon" :size="18" />
              <template v-if="editingChannelId === item.data.id">
                <input
                  class="inline-edit custom-input"
                  v-model="editedName"
                  @keyup.enter="saveInlineEdit(item.data)"
                  @keyup.esc="cancelInlineEdit"
                  @blur="saveInlineEdit(item.data)"
                  @click.stop
                  autofocus
                />
              </template>
              <template v-else>
                <span class="channel-name" @dblclick.stop="auth.isAdmin && editMode ? startInlineEdit(item.data) : undefined">{{ item.data.name }}</span>
              </template>
              <span v-if="chat.getVoiceChannelUsers(item.data.id).length > 0" class="user-count">
                {{ chat.getVoiceChannelUsers(item.data.id).length }}
              </span>
              <div v-if="editMode" class="edit-actions" @click.stop>
                <button v-if="editingChannelId !== item.data.id" class="small" @click="renameChannel(item.data)">重命名</button>
                <span class="drag-handle drag-handle-group">☰</span>
              </div>
            </div>
            <div
              v-if="chat.getVoiceChannelUsers(item.data.id).length > 0"
              class="voice-users-list"
            >
              <div
                v-for="user in chat.getVoiceChannelUsers(item.data.id)"
                :key="user.id"
                class="voice-user-item"
                @contextmenu="auth.isAdmin ? showUserContextMenu($event, item.data.id, user.id) : undefined"
              >
                <div class="voice-user-avatar-wrapper">
                  <img
                    v-if="user.avatar_url"
                    :src="user.avatar_url"
                    :alt="user.name"
                    class="voice-user-avatar-img"
                    @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                  />
                  <span v-else class="voice-user-avatar">{{ user.name.charAt(0).toUpperCase() }}</span>
                  <Crown v-if="user.is_host" class="voice-user-host-badge" :size="10" />
                </div>
                <span class="voice-user-name">{{ user.name }}</span>
                <MicOff v-if="user.is_muted" class="voice-user-muted" :size="12" />
              </div>
            </div>
          </div>
          </template>
        </VueDraggable>
      </div>
      
      <div class="user-panel">
        <span v-if="editMode" class="drag-hint">提示：可拖拽改变顺序</span>
        <VoiceControls />
        <div class="user-info">
          <span class="username">{{ auth.user?.nickname || auth.user?.username }}</span>
          <button class="logout-btn" @click="auth.logout()">退出</button>
        </div>
      </div>
    </div>

    <!-- Channel Context Menu (NDropdown) -->
    <NDropdown
      placement="bottom-start"
      trigger="manual"
      :x="channelDropdown.x"
      :y="channelDropdown.y"
      :options="channelDropdownOptions"
      :show="channelDropdown.show && auth.isAdmin"
      @select="handleChannelDropdownSelect"
      @clickoutside="channelDropdown.show = false"
    />

    <!-- Channel Group Context Menu (NDropdown) -->
    <NDropdown
      placement="bottom-start"
      trigger="manual"
      :x="groupDropdown.x"
      :y="groupDropdown.y"
      :options="groupDropdownOptions"
      :show="groupDropdown.show && auth.isAdmin"
      @select="handleGroupDropdownSelect"
      @clickoutside="groupDropdown.show = false"
    />

    <!-- Voice User Context Menu (NDropdown) -->
    <NDropdown
      placement="bottom-start"
      trigger="manual"
      :x="userDropdown.x"
      :y="userDropdown.y"
      :options="userDropdownOptions"
      :show="userDropdown.show && auth.isAdmin"
      @select="handleUserDropdownSelect"
      @clickoutside="userDropdown.show = false"
    />

    <!-- Create Channel/Group Modal -->
    <NModal
      v-model:show="showCreate"
      preset="card"
      title="创建频道/频道组"
      style="width: 360px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <NSpace vertical>
        <NSelect
          v-model:value="newCreateType"
          :options="createTypeOptions"
          placeholder="选择类型"
        />
        <NInput
          v-model:value="newItemName"
          :placeholder="newCreateType === 'group' ? '频道组名称' : '频道名称'"
          @keyup.enter="createItem"
        />
        <NSelect
          v-if="newCreateType !== 'group' && channelGroups.length > 0"
          v-model:value="newChannelGroupId"
          :options="groupSelectOptions"
          placeholder="选择频道组（可选）"
          clearable
        />
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showCreate = false">取消</NButton>
          <NButton type="primary" @click="createItem">创建</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- Rename Channel Group Modal -->
    <NModal
      v-model:show="showRenameGroupDialog"
      preset="card"
      title="重命名频道组"
      style="width: 360px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <NInput
        v-model:value="renameGroupName"
        placeholder="新名称"
        @keyup.enter="confirmRenameGroup"
      />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showRenameGroupDialog = false">取消</NButton>
          <NButton type="primary" @click="confirmRenameGroup">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- Rename Server Modal -->
    <NModal
      v-model:show="showRenameServerDialog"
      preset="card"
      title="重命名服务器"
      style="width: 360px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <NInput
        v-model:value="renameServerName"
        placeholder="新名称"
        @keyup.enter="confirmRenameServer"
      />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showRenameServerDialog = false">取消</NButton>
          <NButton type="primary" @click="confirmRenameServer">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- Move Channel to Group Modal -->
    <NModal
      v-model:show="showMoveChannelDialog"
      preset="card"
      title="移动频道到频道组"
      style="width: 360px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <NSelect
        v-model:value="moveToGroupId"
        :options="groupSelectOptions"
        placeholder="选择目标频道组"
      />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showMoveChannelDialog = false">取消</NButton>
          <NButton type="primary" @click="confirmMoveChannel">确定</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.channel-list {
  /* replaced browser resize with custom resizer */
  position: relative;
  overflow: auto;
  min-width: 272px;
  max-width: 360px;
  border-right: 2px solid rgba(255, 166, 133, 0.50);
}

.channel-list-content {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-content: space-around;
  justify-content: space-between
}

/* vertical resizer on right edge */
.resizer {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 10px;
  cursor: col-resize;
  z-index: 10;
}

/* ensure flex children can shrink without pushing action buttons */
.channel {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  margin: 1px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-muted);
  transition: all var(--transition-fast);
  min-width: 0; /* important so flex children can shrink */
  min-height: 32px;
}

.channel:hover {
  background: rgba(255, 166, 133, 0.15);
  color: var(--color-text-main);
}

.channel.active {
  background: rgba(255, 166, 133, 0.6);
  color: var(--color-text-bright);
}

/* remove marquee/automatic scrolling — we use ellipsis only */
/* .channel-name.scrolling { animation: marquee 6s linear infinite; } */

.inline-edit {
  flex: 1;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.06);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  font-size: 12px;
  min-width: 0;
  outline: none;
  margin-right: 8px;
  box-shadow: none;
}

/* custom input appearance */
.custom-input {
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.08);
  background: linear-gradient(180deg, rgba(255,255,255,0.02), rgba(255,255,255,0.01));
}

.custom-input:focus {
  border-color: rgba(255,255,255,0.24);
  background: var(--surface-glass-input-focus);
}

.edit-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}

.server-header {
  padding: 12px 16px;
  height: 48px;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.server-name-section {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.server-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rename-server-btn {
  margin-left: auto;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: var(--color-text-muted);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.rename-server-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--color-text-bright);
  border-color: rgba(255, 255, 255, 0.3);
}

.server-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.channels {
  transition: all 0.5s linear;
}

.drag-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
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

.add-channel-btn {
  background: transparent;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 18px;
  font-weight: 500;
  padding: 0 8px;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all var(--transition-fast);
}

.add-channel-btn:hover {
  color: var(--color-text-bright);
  background: rgba(255, 255, 255, 0.1);
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

.channel-icon {
  width: 18px;
  height: 18px;
  margin-right: 6px;
  opacity: 0.7;
  flex-shrink: 0;
}

.channel-icon svg {
  width: 100%;
  height: 100%;
}

.channel-name {
  font-size: 12px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.drag-handle {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  color: var(--color-text-muted);
  user-select: none;
  font-size: 14px;
  opacity: 0.6;
  transition: opacity var(--transition-fast);
  flex-shrink: 0;
}

.drag-handle:hover {
  opacity: 1;
}

.drag-handle:active {
  cursor: grabbing;
}

.edit-actions .small {
  font-size: 12px;
  padding: 4px 6px;
  border-radius: 6px;
  border: none;
  background-color: rgba(0, 0, 0, 0);
  color: var(--color-text-main);
  cursor: pointer;
}

.edit-actions .small:hover {
  background: var(--surface-glass-strong);
}

.user-info {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 4px;
  padding: 0px 8px;
}

.user-panel {
  width: 100%; /* 左右撑满父父容器 */ 
  margin-top: auto; /* 自动推到底部 */ 
  display: flex; 
  justify-content: space-between; 
  border-top: 1px dashed rgba(128,128,128,0.4); 
  padding: 8px; 
  background: transparent;
  flex-direction: column;
  align-items: center;
}

.logout-btn {
  background: transparent;
  color: var(--color-text-muted);
  border: none;
  cursor: pointer;
  padding: 4px 4px;
  font-size: 12px;
  transition: color var(--transition-fast);
}

.logout-btn:hover {
  color: var(--color-primary);
}

.voice-channel-wrapper {
  margin-bottom: 2px;
  transition: all 0.5s linear;
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

.voice-user-avatar-img {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  object-fit: cover;
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

.edit-toggle {
  margin-left: 8px;
  background: transparent;
  border: 1px solid rgba(255,255,255,0.08);
  color: var(--color-text-muted);
  padding: 4px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

/* Channel Group Styles */
.channel-group {
  margin-bottom: 4px;
}

.channel-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  margin: 0 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-main);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  transition: all var(--transition-fast);
  user-select: none;
}

.channel-group-header:hover {
  background: rgba(255, 166, 133, 0.15);
  color: var(--color-text-main);
}

.channel-group-header .drag-handle {
  cursor: grab;
  color: var(--color-text-muted);
  font-size: 12px;
  opacity: 0.6;
  transition: opacity var(--transition-fast);
}

.channel-group-header .drag-handle:hover {
  opacity: 1;
}

.channel-group-header .drag-handle:active {
  cursor: grabbing;
}

.channel-group-header .collapse-icon {
  flex-shrink: 0;
  opacity: 0.9;
  color: var(--color-primary);
  transition: transform 0.3s ease;
}

.channel-group.collapsed .channel-group-header .collapse-icon {
  transform: rotate(0deg);
}

.channel-group:not(.collapsed) .channel-group-header .collapse-icon {
  transform: rotate(0deg);
}

.channel-group-header .group-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-group-header .channel-count {
  font-size: 11px;
  font-weight: 500;
  color: var(--color-text-muted);
  opacity: 0.7;
}

.channel-group-header .rename-group-btn {
  margin-left: auto;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: var(--color-text-muted);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.channel-group-header .rename-group-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--color-text-bright);
  border-color: rgba(255, 255, 255, 0.3);
}

.group-channels {
  padding: 2px 0 0 0;
  overflow: hidden;
  transition: all 0.5s linear;
}

/* Slide down animation for channel groups */
.slide-down-enter-active {
  transition: max-height 0.35s ease-out, opacity 0.35s ease-out;
  overflow: hidden;
  will-change: max-height, opacity;
}

.slide-down-leave-active {
  transition: max-height 0.2s ease-in, opacity 0.2s ease-in;
  overflow: hidden;
  will-change: max-height, opacity;
}

.slide-down-enter-from,
.slide-down-leave-to {
  max-height: 0 !important;
  opacity: 0;
}

.slide-down-enter-to,
.slide-down-leave-from {
  max-height: 100vh;
  opacity: 1;
}

.group-channels .channel {
  margin: 0 8px 0 24px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.05em;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
}

.group-channels .channel .channel-icon {
  font-size: 16px;
  opacity: 0.8;
}

.group-channels .channel:hover {
  background: rgba(255, 166, 133, 0.15);
  color: var(--color-text-main);
}

.group-channels .channel.active {
  background: rgba(255, 166, 133, 0.6);
  color: var(--color-text-bright);
}

.group-channels .voice-channel-wrapper {
  margin: 0 8px 0 24px;
}

.group-channels .voice-channel-wrapper .channel {
  margin: 0;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.05em;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
}

.group-channels .voice-channel-wrapper .channel:hover {
  background: rgba(255, 166, 133, 0.15);
  color: var(--color-text-main);
}

.group-channels .voice-channel-wrapper .channel.active {
  background: rgba(255, 166, 133, 0.6);
  color: var(--color-text-bright);
}

/* Draggable styles */
.draggable-list {
  min-height: 20px;
}

.drag-ghost {
  opacity: 0.5;
  background: rgba(255, 166, 133, 0.3) !important;
  border-radius: var(--radius-sm);
}

.drag-chosen {
  background: rgba(255, 166, 133, 0.2) !important;
}

.drag-dragging {
  opacity: 0.8;
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.sortable-fallback {
  opacity: 0.8 !important;
  background: var(--surface-glass) !important;
}

/* Mention Badge */
.mention-badge {
  background: linear-gradient(135deg, #ff6b6b 0%, #ee5a6f 100%);
  color: white;
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 10px;
  margin-left: auto;
  white-space: nowrap;
  box-shadow: 0 2px 4px rgba(255, 107, 107, 0.3);
  animation: pulse-mention 2s ease-in-out infinite;
}

@keyframes pulse-mention {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.8;
    transform: scale(0.98);
  }
}
</style>
