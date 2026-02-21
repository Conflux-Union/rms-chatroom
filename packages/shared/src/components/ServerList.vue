<script setup lang="ts">
import { ref, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { NModal, NInput, NButton, NSpace, NDropdown } from 'naive-ui'
import type { DropdownOption } from 'naive-ui'
import Settings from './Setting.vue'
import ServerPermissionModal from './ServerPermissionModal.vue'

const chat = useChatStore()
const auth = useAuthStore()

const showCreate = ref(false)
const newServerName = ref('')
const showSettings = ref(false)

// Server Permission Modal state
const showServerPermissionModal = ref(false)
const selectedServerForPermission = ref<number | null>(null)

// Context menu state (NDropdown)
const serverDropdown = ref<{ show: boolean; x: number; y: number; serverId: number | null }>({
  show: false, x: 0, y: 0, serverId: null
})

// Dropdown options - 根据用户权限动态生成
const serverDropdownOptions = computed((): DropdownOption[] => {
  const options: DropdownOption[] = [
    { label: '权限设置', key: 'permissions' },
    { label: '删除服务器', key: 'delete', props: { style: { color: 'var(--color-danger)' } } }
  ]
  return options
})

function canShowContextMenu(): boolean {
  // 只有权限3-4的用户才能看到右键菜单
  const userPermLevel = auth.user?.permission_level || 1
  return userPermLevel >= 3
}

async function selectServer(serverId: number) {
  await chat.fetchServer(serverId)
}

async function createServer() {
  if (!newServerName.value.trim()) return
  await chat.createServer(newServerName.value.trim())
  newServerName.value = ''
  showCreate.value = false
}

function showContextMenu(event: MouseEvent, serverId: number) {
  event.preventDefault()
  // 只有权限3-4的用户才能显示自定义右键菜单
  if (!canShowContextMenu()) {
    return
  }
  serverDropdown.value = { show: true, x: event.clientX, y: event.clientY, serverId }
}

function hideDropdown() {
  serverDropdown.value.show = false
}

async function handleDropdownSelect(key: string) {
  if (key === 'permissions' && serverDropdown.value.serverId) {
    selectedServerForPermission.value = serverDropdown.value.serverId
    showServerPermissionModal.value = true
  } else if (key === 'delete' && serverDropdown.value.serverId) {
    await chat.deleteServer(serverDropdown.value.serverId)
  }
  serverDropdown.value.show = false
}

function onServerPermissionSaved() {
  showServerPermissionModal.value = false
  selectedServerForPermission.value = null
}
</script>

<template>
  <div class="server-list" @click="hideDropdown">
    <div>
      <div
        v-for="server in chat.servers"
        :key="server.id"
        class="server-icon glow-effect"
        :class="{ active: chat.currentServer?.id === server.id }"
        @click="selectServer(server.id)"
        @contextmenu="canShowContextMenu() ? showContextMenu($event, server.id) : undefined"
        :title="server.name"
      >
        {{ server.name.charAt(0).toUpperCase() }}
      </div>

      <div v-if="auth.isAdmin" class="server-icon add-server glow-effect" @click="showCreate = true" title="创建服务器">
        +
      </div>

      <!-- Context Menu (NDropdown) -->
      <NDropdown
        placement="bottom-start"
        trigger="manual"
        :x="serverDropdown.x"
        :y="serverDropdown.y"
        :options="serverDropdownOptions"
        :show="serverDropdown.show && canShowContextMenu()"
        @select="handleDropdownSelect"
        @clickoutside="serverDropdown.show = false"
      />

      <!-- Create Server Modal (NModal) -->
      <NModal
        v-model:show="showCreate"
        preset="card"
        title="创建服务器"
        style="width: 360px"
        :segmented="{ content: true, footer: 'soft' }"
      >
        <NInput
          v-model:value="newServerName"
          placeholder="服务器名称"
          @keyup.enter="createServer"
        />
        <template #footer>
          <NSpace justify="end">
            <NButton @click="showCreate = false">取消</NButton>
            <NButton type="primary" @click="createServer">创建</NButton>
          </NSpace>
        </template>
      </NModal>
    </div>

    <div class="bottom-area">
      <div
        class="server-icon glow-effect settings-btn"
        title="设置"
        @click.stop="showSettings = true"
      >
        ⚙
      </div>
    </div>

    <Settings v-if="showSettings" @close="showSettings = false" />

    <!-- Server Permission Modal -->
    <ServerPermissionModal
      v-if="selectedServerForPermission !== null && chat.currentServer !== null"
      :isOpen="showServerPermissionModal"
      :serverId="selectedServerForPermission || 0"
      :serverName="chat.currentServer?.name || ''"
      :initialMinLevel="chat.currentServer?.min_level || 1"
      @close="showServerPermissionModal = false"
      @save="onServerPermissionSaved"
    />
  </div>
</template>

<style scoped>
.server-list {
  width: 80px;
  height: 100vh;
  border-right: 2px solid rgba(255, 166, 133, 0.50);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px 0;
  position: relative;
  z-index: 1000000;
}

.server-icon {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  background: var(--surface-glass);
  margin: 0 auto 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-weight: 600;
  font-size: 18px;
  color: var(--color-text-main);
  transition: all var(--transition-fast);
  border: 2px solid transparent;
}

.server-icon:hover {
  transform: scale(1.1);
  border-color: var(--color-accent);
  box-shadow: var(--shadow-glow);
}

.server-icon.active {
  background: var(--color-gradient-primary);
  color: white;
  box-shadow: var(--shadow-glow);
}

.add-server {
  background: var(--color-gradient-secondary);
  color: rgba(28, 28, 28, 0.804);
  font-size: 24px;
}

/* Bottom area */
.bottom-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 6px;
}

.settings-btn {
  margin-bottom: 0;
}
</style>
