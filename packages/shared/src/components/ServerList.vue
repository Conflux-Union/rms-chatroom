<script setup lang="ts">
import { ref, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { isTauri } from '../index'
import { ZmModal, ZmInput, ZmButton, ZmSpace, ZmDropdown } from './ui'
import type { ZmDropdownOption } from './ui'
import Settings from './Setting.vue'
import ServerPermissionModal from './ServerPermissionModal.vue'

const GITHUB_REPO_URL = 'https://github.com/Conflux-Union/rms-chatroom'

const chat = useChatStore()
const auth = useAuthStore()

const showCreate = ref(false)
const newServerName = ref('')
const showSettings = ref(false)

// Server Permission Modal state
const showServerPermissionModal = ref(false)
const selectedServerForPermission = ref<number | null>(null)

// Context menu state (ZmDropdown)
const serverDropdown = ref<{ show: boolean; x: number; y: number; serverId: number | null }>({
  show: false, x: 0, y: 0, serverId: null
})

// Dropdown options - 根据用户权限动态生成
const serverDropdownOptions = computed((): ZmDropdownOption[] => {
  const options: ZmDropdownOption[] = [
    { label: '权限设置', key: 'permissions' },
    { label: '删除服务器', key: 'delete', danger: true }
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

// The desktop webview blocks window.open, so route through the shell opener.
async function openGitHub() {
  if (!isTauri) {
    window.open(GITHUB_REPO_URL, '_blank', 'noopener')
    return
  }
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('open_external', { url: GITHUB_REPO_URL })
  } catch (error) {
    console.error('failed to open github repo', error)
  }
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

async function handleDropdownSelect(key: string | number) {
  if (key === 'permissions' && serverDropdown.value.serverId) {
    selectedServerForPermission.value = serverDropdown.value.serverId
    showServerPermissionModal.value = true
  } else if (key === 'delete' && serverDropdown.value.serverId) {
    await chat.deleteServer(serverDropdown.value.serverId)
  }
  serverDropdown.value.show = false
}

function onServerPermissionSaved(val: { minLevel: number; permMinLevel: number; logicOperator: 'AND' | 'OR' }) {
  // Update local server object so the modal shows fresh values next time
  const server = chat.servers.find(s => s.id === selectedServerForPermission.value)
  if (server) {
    server.min_level = val.minLevel
    server.perm_min_level = val.permMinLevel
    server.logic_operator = val.logicOperator
  }
  if (chat.currentServer && chat.currentServer.id === selectedServerForPermission.value) {
    chat.currentServer.min_level = val.minLevel
    chat.currentServer.perm_min_level = val.permMinLevel
    chat.currentServer.logic_operator = val.logicOperator
  }
  showServerPermissionModal.value = false
  selectedServerForPermission.value = null
}
</script>

<template>
  <div class="server-list" @click="hideDropdown">
    <div class="servers-scroll">
      <div
        v-for="server in chat.servers"
        :key="server.id"
        class="server-icon "
        :class="{ active: chat.currentServer?.id === server.id }"
        @click="selectServer(server.id)"
        @contextmenu="canShowContextMenu() ? showContextMenu($event, server.id) : undefined"
        :title="server.name"
      >
        {{ server.name.charAt(0).toUpperCase() }}
      </div>

      <div v-if="auth.isAdmin" class="server-icon add-server " @click="showCreate = true" title="创建服务器">
        +
      </div>
    </div>

    <div class="bottom-area">
      <div
        class="server-icon github-btn"
        title="GitHub 仓库"
        @click.stop="openGitHub"
      >
        <!-- Official GitHub mark (Simple Icons), same path as the Android drawable -->
        <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
          <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
        </svg>
      </div>
      <div
        class="server-icon  settings-btn"
        title="设置"
        @click.stop="showSettings = true"
      >
        ⚙
      </div>
    </div>

    <!-- Context Menu (ZmDropdown) -->
    <ZmDropdown
      placement="bottom-start"
      trigger="manual"
      :x="serverDropdown.x"
      :y="serverDropdown.y"
      :options="serverDropdownOptions"
      :show="serverDropdown.show && canShowContextMenu()"
      @select="handleDropdownSelect"
      @clickoutside="serverDropdown.show = false"
    />

    <!-- Create Server Modal (ZmModal) -->
    <ZmModal
      v-model:show="showCreate"
      preset="card"
      title="创建服务器"
      style="width: 360px"
      :segmented="{ content: true, footer: 'soft' }"
    >
      <ZmInput
        v-model:value="newServerName"
        placeholder="服务器名称"
        @keyup.enter="createServer"
      />
      <template #footer>
        <ZmSpace justify="end">
          <ZmButton @click="showCreate = false">取消</ZmButton>
          <ZmButton type="primary" @click="createServer">创建</ZmButton>
        </ZmSpace>
      </template>
    </ZmModal>

    <Settings v-if="showSettings" @close="showSettings = false" />

    <!-- Server Permission Modal -->
    <ServerPermissionModal
      v-if="selectedServerForPermission !== null && chat.currentServer !== null"
      :isOpen="showServerPermissionModal"
      :serverId="selectedServerForPermission || 0"
      :serverName="chat.currentServer?.name || ''"
      :initialMinLevel="chat.currentServer?.min_level || 0"
      :initialPermMinLevel="chat.currentServer?.perm_min_level || 0"
      :initialLogicOperator="chat.currentServer?.logic_operator || 'AND'"
      @close="showServerPermissionModal = false"
      @save="onServerPermissionSaved"
    />
  </div>
</template>

<style scoped>
.server-list {
  width: 80px;
  /* Fill the parent rail instead of hardcoding 100vh: the mobile drawer is
     offset by the 56px header, so 100vh pushed the bottom buttons off-screen. */
  height: 100%;
  border-right: 1px solid var(--zhimo-border-strong);
  display: flex;
  flex-direction: column;
  padding: 20px 0;
  position: relative;
  z-index: 1000000;
}

/* Server icons scroll when the rail is crowded; the bottom buttons stay pinned. */
.servers-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.server-icon {
  width: 48px;
  height: 42px;
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-bg-subtle);
  margin: 0 auto 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-weight: 600;
  font-size: 18px;
  color: var(--color-text-main);
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast), transform var(--transition-fast);
  border: 1px solid var(--zhimo-border);
  box-shadow: none;
}

.server-icon:hover {
  transform: translateY(-1px);
  background: var(--zhimo-bg-hover);
  border-color: var(--zhimo-border-strong);
  color: var(--zhimo-fg);
}

.server-icon.active {
  background: var(--zhimo-seal);
  border-color: var(--zhimo-seal-hover);
  color: var(--zhimo-accent-fg);
}

.add-server {
  background: var(--zhimo-bg);
  color: var(--zhimo-seal);
  border-style: dashed;
  font-size: 24px;
}

/* Bottom area */
.bottom-area {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 6px;
}

.settings-btn {
  margin-bottom: 0;
}
</style>
