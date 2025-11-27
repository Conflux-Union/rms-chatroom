<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'

const chat = useChatStore()
const auth = useAuthStore()
const showCreate = ref(false)
const newServerName = ref('')
const contextMenu = ref<{ show: boolean; x: number; y: number; serverId: number | null }>({
  show: false, x: 0, y: 0, serverId: null
})

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
  contextMenu.value = { show: true, x: event.clientX, y: event.clientY, serverId }
}

function hideContextMenu() {
  contextMenu.value = { show: false, x: 0, y: 0, serverId: null }
}

async function deleteServer() {
  if (!contextMenu.value.serverId) return
  if (confirm('Are you sure you want to delete this server?')) {
    await chat.deleteServer(contextMenu.value.serverId)
  }
  hideContextMenu()
}
</script>

<template>
  <div class="server-list" @click="hideContextMenu">
    <div
      v-for="server in chat.servers"
      :key="server.id"
      class="server-icon glow-effect"
      :class="{ active: chat.currentServer?.id === server.id }"
      @click="selectServer(server.id)"
      @contextmenu="auth.isAdmin ? showContextMenu($event, server.id) : undefined"
      :title="server.name"
    >
      {{ server.name.charAt(0).toUpperCase() }}
    </div>

    <div v-if="auth.isAdmin" class="server-icon add-server glow-effect" @click="showCreate = true" title="Create Server">
      +
    </div>

    <!-- Context Menu -->
    <div
      v-if="contextMenu.show && auth.isAdmin"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item delete" @click="deleteServer">Delete Server</div>
    </div>

    <div v-if="showCreate" class="create-modal" @click.self="showCreate = false">
      <div class="modal-content">
        <h3>Create Server</h3>
        <input
          v-model="newServerName"
          placeholder="Server name"
          @keyup.enter="createServer"
        />
        <div class="modal-actions">
          <button class="glow-effect" @click="showCreate = false">Cancel</button>
          <button class="primary glow-effect" @click="createServer">Create</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.server-list {
  width: 72px;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  overflow-y: auto;
  border-right: 1px dashed rgba(128, 128, 128, 0.4);
}

.server-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--surface-glass);
  color: var(--color-text-main);
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  font-weight: 600;
  font-size: 18px;
  transition: border-radius var(--transition-fast), background var(--transition-fast), transform var(--transition-fast);
}

.server-icon:hover,
.server-icon.active {
  border-radius: 16px;
  background: var(--color-gradient-primary);
  color: white;
  transform: scale(1.05);
}

.add-server {
  background: transparent;
  border: 2px dashed var(--color-success);
  color: var(--color-success);
  font-size: 24px;
}

.add-server:hover {
  background: var(--color-success);
  color: #fff;
  border-color: var(--color-success);
  transform: scale(1.05);
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
</style>
