<script setup lang="ts">
import { ref, computed, watch, onUnmounted, onMounted, nextTick } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useVoiceStore } from '../stores/voice'
import { Volume2, MicOff, Crown, ChevronDown, ChevronRight } from 'lucide-vue-next'
import { NDropdown, NModal, NInput, NButton, NSpace, NSelect } from 'naive-ui'
import type { DropdownOption, SelectOption } from 'naive-ui'
import type { Channel, ChannelGroup } from '../types'
import VoiceControls from '../components/VoiceControls.vue'

const chat = useChatStore()
const auth = useAuthStore()
const voice = useVoiceStore()
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
  { label: '重命名', key: 'rename' },
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
  } else {
    stopVoiceUsersPolling()
  }
}, { immediate: true })

onUnmounted(() => {
  stopVoiceUsersPolling()
})

// Channel groups
const channelGroups = computed(() => 
  chat.currentServer?.channelGroups?.sort((a, b) => a.position - b.position) || []
)

// Ungrouped channels (channels without a group) - mixed text and voice
// Use == null to match both null and undefined
const ungroupedChannels = computed(() => 
  chat.currentServer?.channels?.filter((c) => c.group_id == null)?.sort((a, b) => a.position - b.position) || []
)

// Get all channels for a specific group (mixed text and voice)
function getGroupChannels(groupId: number) {
  return chat.currentServer?.channels?.filter((c) => c.group_id === groupId)?.sort((a, b) => a.position - b.position) || []
}

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
  } else if (key === 'rename') {
    openRenameGroupDialog()
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
function openRenameGroupDialog() {
  if (!groupDropdown.value.groupId) return
  const group = channelGroups.value.find(g => g.id === groupDropdown.value.groupId)
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

// Inline edit state
const editingChannelId = ref<number | null>(null)
const editedName = ref('')

// Drag & drop state
const dragSourceId = ref<number | null>(null)
const dragSourceType = ref<'channel' | 'group' | null>(null)
const dragSourceGroupId = ref<number | null>(null) // The group the dragged channel belongs to

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

// Clear editing state when leaving edit mode
watch(editMode, (val) => {
  if (!val) {
    // blur active element so inputs commit and focus doesn't keep inline edit active
    try { (document.activeElement as HTMLElement | null)?.blur() } catch {}
    editingChannelId.value = null
    editedName.value = ''
  }
})

// function isFirstInType(channel: Channel) {
//   if (!chat.currentServer) return false
//   const same = (chat.currentServer.channels || []).filter(c => c.type === channel.type).sort((a, b) => a.position - b.position)
//   if (same.length === 0) return false
//   const first = same[0]
//   return !!first && first.id === channel.id
// }

// function isLastInType(channel: Channel) {
//   if (!chat.currentServer) return false
//   const same = (chat.currentServer.channels || []).filter(c => c.type === channel.type).sort((a, b) => a.position - b.position)
//   if (same.length === 0) return false
//   const last = same[same.length - 1]
//   return !!last && last.id === channel.id
// }

// async function moveChannel(channel: Channel, delta: number) {
//   if (!chat.currentServer) return
//   const all = [...(chat.currentServer.channels || [])].sort((a, b) => a.position - b.position)
//   const type = channel.type
//   const typeList = all.filter(c => c.type === type)
//   const srcIdx = typeList.findIndex(c => c.id === channel.id)
//   if (srcIdx === -1) return
//   const newIdx = srcIdx + delta
//   if (newIdx < 0 || newIdx >= typeList.length) return

//   const moved = typeList.splice(srcIdx, 1)[0]
//   if (!moved) return
//   typeList.splice(newIdx, 0, moved)

//   const newAll: typeof all = []
//   let pos = 0
//   for (const c of all) {
//     if (c.type === type) {
//       const r = typeList[pos++]
//       if (r) newAll.push(r)
//     } else {
//       newAll.push(c)
//     }
//   }

//   // Optimistic UI update
//   try {
//     if (chat.currentServer && (chat.currentServer as any).channels) {
//       ;(chat.currentServer as any).channels = newAll
//     }
//   } catch (e) {
//     console.warn('Optimistic channel update failed', e)
//   }

//   const ids = newAll.map(c => c.id)
//   console.debug('moveChannel reorder ids:', ids)
//   await chat.reorderChannels(chat.currentServer.id, ids)
// }

// Channel drag functions
function onChannelDragStart(event: DragEvent, channelId: number, groupId: number | null = null) {
  if (!auth.isAdmin || !editMode.value) return
  dragSourceId.value = channelId
  dragSourceType.value = 'channel'
  dragSourceGroupId.value = groupId
  try { 
    event.dataTransfer?.setData('text/plain', JSON.stringify({ type: 'channel', id: channelId, groupId })) 
  } catch {}
}

// Group drag functions
function onGroupDragStart(event: DragEvent, groupId: number) {
  if (!auth.isAdmin || !editMode.value) return
  dragSourceId.value = groupId
  dragSourceType.value = 'group'
  dragSourceGroupId.value = null
  try { 
    event.dataTransfer?.setData('text/plain', JSON.stringify({ type: 'group', id: groupId })) 
  } catch {}
}

function onDragOver(event: DragEvent) {
  if (!auth.isAdmin || !editMode.value) return
  event.preventDefault()
}

// Drop on a channel (reorder channels within same group or ungrouped)
async function onChannelDrop(event: DragEvent, targetId: number, targetGroupId: number | null = null) {
  if (!auth.isAdmin || !editMode.value) return
  event.preventDefault()
  event.stopPropagation()
  
  // Only handle channel drops
  if (dragSourceType.value !== 'channel') {
    resetDragState()
    return
  }
  
  const srcId = dragSourceId.value
  const srcGroupId = dragSourceGroupId.value
  resetDragState()
  
  if (!chat.currentServer || srcId === null || srcId === targetId) return
  
  // Get channels in the same group (or ungrouped)
  const allChannels = [...(chat.currentServer.channels || [])].sort((a, b) => a.position - b.position)
  
  // If source and target are in different groups, we need to move the channel first
  if (srcGroupId !== targetGroupId) {
    // Move channel to target group first
    await chat.updateChannel(chat.currentServer.id, srcId, { group_id: targetGroupId === null ? -1 : targetGroupId })
    return // The server will refresh and show the new order
  }
  
  // Same group - just reorder
  const groupChannels = allChannels.filter(c => 
    targetGroupId === null ? c.group_id == null : c.group_id === targetGroupId
  )
  
  const srcIdx = groupChannels.findIndex(c => c.id === srcId)
  const tgtIdx = groupChannels.findIndex(c => c.id === targetId)
  if (srcIdx === -1 || tgtIdx === -1) return
  
  const moved = groupChannels.splice(srcIdx, 1)[0]
  if (!moved) return
  groupChannels.splice(tgtIdx, 0, moved)
  
  // Rebuild full channel list maintaining order
  const newAll: typeof allChannels = []
  const groupedChannels = new Map<number | null, typeof allChannels>()
  
  // Group channels by group_id
  for (const c of allChannels) {
    const gid = c.group_id ?? null
    if (!groupedChannels.has(gid)) groupedChannels.set(gid, [])
    groupedChannels.get(gid)!.push(c)
  }
  
  // Replace the reordered group
  groupedChannels.set(targetGroupId, groupChannels)
  
  // Flatten back - groups first, then ungrouped
  for (const group of channelGroups.value) {
    const gChannels = groupedChannels.get(group.id) || []
    newAll.push(...gChannels)
  }
  // Add ungrouped channels
  const ungrouped = groupedChannels.get(null) || []
  newAll.push(...ungrouped)
  
  // Optimistic UI update
  try {
    if (chat.currentServer && (chat.currentServer as any).channels) {
      ;(chat.currentServer as any).channels = newAll
    }
  } catch (e) {
    console.warn('Optimistic channel update failed', e)
  }
  
  const ids = newAll.map(c => c.id)
  await chat.reorderChannels(chat.currentServer.id, ids)
}

// Drop on a group header (reorder groups)
async function onGroupDrop(event: DragEvent, targetGroupId: number) {
  if (!auth.isAdmin || !editMode.value) return
  event.preventDefault()
  event.stopPropagation()
  
  // Handle group reordering
  if (dragSourceType.value === 'group') {
    const srcId = dragSourceId.value
    resetDragState()
    
    if (!chat.currentServer || srcId === null || srcId === targetGroupId) return
    
    const groups = [...channelGroups.value]
    const srcIdx = groups.findIndex(g => g.id === srcId)
    const tgtIdx = groups.findIndex(g => g.id === targetGroupId)
    if (srcIdx === -1 || tgtIdx === -1) return
    
    const moved = groups.splice(srcIdx, 1)[0]
    if (!moved) return
    groups.splice(tgtIdx, 0, moved)
    
    // Optimistic UI update
    try {
      if (chat.currentServer && (chat.currentServer as any).channelGroups) {
        ;(chat.currentServer as any).channelGroups = groups
      }
    } catch (e) {
      console.warn('Optimistic group update failed', e)
    }
    
    const ids = groups.map(g => g.id)
    await chat.reorderChannelGroups(chat.currentServer.id, ids)
    return
  }
  
  // Handle channel drop on group (move channel to group)
  if (dragSourceType.value === 'channel') {
    const srcId = dragSourceId.value
    const srcGroupId = dragSourceGroupId.value
    resetDragState()
    
    if (!chat.currentServer || srcId === null) return
    if (srcGroupId === targetGroupId) return // Already in this group
    
    await chat.updateChannel(chat.currentServer.id, srcId, { group_id: targetGroupId })
  }
}

function resetDragState() {
  dragSourceId.value = null
  dragSourceType.value = null
  dragSourceGroupId.value = null
}

// Legacy function for backward compatibility
function onDragStart(event: DragEvent, channelId: number) {
  onChannelDragStart(event, channelId, null)
}

async function onDrop(event: DragEvent, targetId: number, type: 'text' | 'voice') {
  await onChannelDrop(event, targetId, null)
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
        <h2 class="server-title">{{ chat.currentServer?.name || '选择服务器' }}</h2>
        <div class="server-controls">
          <button v-if="auth.isAdmin" class="edit-toggle" @click.stop="editMode = !editMode">{{ editMode ? '退出编辑' : '编辑' }}</button>
        </div>
      </div>

      <div class="channels">
        <!-- Channel Groups -->
        <div v-for="group in channelGroups" :key="'group-' + group.id" class="channel-group">
          <div 
            class="channel-group-header"
            :draggable="auth.isAdmin && editMode"
            @click="toggleGroupCollapse(group.id)"
            @contextmenu="auth.isAdmin && editMode ? showGroupContextMenu($event, group.id) : undefined"
            @dragstart="onGroupDragStart($event, group.id)"
            @dragover="onDragOver($event)"
            @drop="onGroupDrop($event, group.id)"
          >
            <span v-if="editMode" class="drag-handle">☰</span>
            <ChevronDown v-if="!collapsedGroups.has(group.id)" :size="14" class="collapse-icon" />
            <ChevronRight v-else :size="14" class="collapse-icon" />
            <span class="group-name">{{ group.name }}</span>
            <button v-if="auth.isAdmin && editMode" class="add-channel" @click.stop="showCreate = true; newCreateType = 'text'; newChannelGroupId = group.id">+</button>
          </div>
          
          <!-- Group channels (mixed text and voice) -->
          <div v-if="!collapsedGroups.has(group.id)" class="group-channels">
            <template v-for="channel in getGroupChannels(group.id)" :key="channel.id">
              <!-- Text channel -->
              <div
                v-if="channel.type === 'text'"
                class="channel glow-effect"
                :class="{ active: chat.currentChannel?.id === channel.id }"
                @click="selectChannel(channel)"
                @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
                :draggable="auth.isAdmin && editMode"
                @dragstart="onChannelDragStart($event, channel.id, group.id)"
                @dragover="onDragOver($event)"
                @drop="onChannelDrop($event, channel.id, group.id)"
              >
                <span class="channel-icon">#</span>
                <span v-if="editMode" class="drag-handle">☰</span>
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
                <div v-if="editMode" class="edit-actions" @click.stop>
                  <button v-if="editingChannelId !== channel.id" class="small" @click="renameChannel(channel)">重命名</button>
                </div>
              </div>
              
              <!-- Voice channel -->
              <div v-else class="voice-channel-wrapper">
                <div
                  class="channel glow-effect"
                  :class="{ active: chat.currentChannel?.id === channel.id }"
                  @click="selectChannel(channel)"
                  @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
                  :draggable="auth.isAdmin && editMode"
                  @dragstart="onChannelDragStart($event, channel.id, group.id)"
                  @dragover="onDragOver($event)"
                  @drop="onChannelDrop($event, channel.id, group.id)"
                >
                  <span v-if="editMode" class="drag-handle">☰</span>
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
                      <span class="voice-user-avatar">{{ user.name.charAt(0).toUpperCase() }}</span>
                      <Crown v-if="user.is_host" class="voice-user-host-badge" :size="10" />
                    </div>
                    <span class="voice-user-name">{{ user.name }}</span>
                    <MicOff v-if="user.is_muted" class="voice-user-muted" :size="12" />
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- Ungrouped Channels (mixed text and voice) -->
        <div class="channel-category">
          <span class="category-name">频道</span>
          <button v-if="auth.isAdmin" class="add-channel-btn" @click.stop="showCreate = true; newCreateType = 'text'; newChannelGroupId = 'none'" title="创建频道/频道组">+</button>
        </div>
        <template v-for="channel in ungroupedChannels" :key="channel.id">
          <!-- Text channel -->
          <div
            v-if="channel.type === 'text'"
            class="channel glow-effect"
            :class="{ active: chat.currentChannel?.id === channel.id }"
            @click="selectChannel(channel)"
            @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
            :draggable="auth.isAdmin && editMode"
            @dragstart="onChannelDragStart($event, channel.id, null)"
            @dragover="onDragOver($event)"
            @drop="onChannelDrop($event, channel.id, null)"
          >
            <span class="channel-icon">#</span>
            <span v-if="editMode" class="drag-handle">☰</span>
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
            <div v-if="editMode" class="edit-actions" @click.stop>
              <button v-if="editingChannelId !== channel.id" class="small" @click="renameChannel(channel)">重命名</button>
            </div>
          </div>
          
          <!-- Voice channel -->
          <div v-else class="voice-channel-wrapper">
            <div
              class="channel glow-effect"
              :class="{ active: chat.currentChannel?.id === channel.id }"
              @click="selectChannel(channel)"
              @contextmenu="auth.isAdmin && editMode ? showChannelContextMenu($event, channel.id) : undefined"
              :draggable="auth.isAdmin && editMode"
              @dragstart="onChannelDragStart($event, channel.id, null)"
              @dragover="onDragOver($event)"
              @drop="onChannelDrop($event, channel.id, null)"
            >
              <span v-if="editMode" class="drag-handle">☰</span>
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
                  <span class="voice-user-avatar">{{ user.name.charAt(0).toUpperCase() }}</span>
                  <Crown v-if="user.is_host" class="voice-user-host-badge" :size="10" />
                </div>
                <span class="voice-user-name">{{ user.name }}</span>
                <MicOff v-if="user.is_muted" class="voice-user-muted" :size="12" />
              </div>
            </div>
          </div>
        </template>
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
  padding: 6px 8px;
  margin: 1px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-muted);
  transition: all var(--transition-fast);
  min-width: 0; /* important so flex children can shrink */
}

.channel-name {
  font-size: 14px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

/* remove marquee/automatic scrolling — we use ellipsis only */
/* .channel-name.scrolling { animation: marquee 6s linear infinite; } */

.inline-edit {
  flex: 1;
  padding: 6px 8px;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.06);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  min-width: 0; /* allow shrink */
  outline: none;
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

.server-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.server-controls {
  display: flex;
  align-items: center;
  gap: 8px;
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
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.drag-handle {
  width: 28px;
  height: 28px;
  margin-left: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  color: var(--color-text-muted);
  user-select: none;
}

.edit-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
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

.inline-edit {
  flex: 1;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.06);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  min-width: 0; /* allow shrink */
  outline: none;
  margin-right: 8px;
  box-shadow: none;
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
  padding: 8px 12px;
  margin: 4px 8px;
  cursor: pointer;
  color: var(--color-text-main);
  font-size: 13px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, rgba(88, 101, 242, 0.15) 0%, rgba(88, 101, 242, 0.05) 100%);
  border-left: 3px solid var(--color-primary);
  transition: all var(--transition-fast);
}

.channel-group-header:hover {
  background: linear-gradient(135deg, rgba(88, 101, 242, 0.25) 0%, rgba(88, 101, 242, 0.1) 100%);
  color: var(--color-text-bright);
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
  opacity: 0.8;
  color: var(--color-primary);
  transition: transform var(--transition-fast);
}

.channel-group-header .group-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-group-header .add-channel {
  opacity: 0;
  background: var(--color-primary);
  color: white;
  border: none;
  border-radius: 4px;
  width: 20px;
  height: 20px;
  font-size: 14px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.channel-group-header .add-channel:hover {
  background: var(--color-primary-hover);
  transform: scale(1.1);
}

.channel-group-header:hover .add-channel {
  opacity: 1;
}

.group-channels {
  padding-left: 12px;
  margin-left: 8px;
  border-left: 1px solid rgba(88, 101, 242, 0.2);
}
</style>
