<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import Settings from './Setting.vue' // ✅ 新增：同目录引入

const chat = useChatStore()
const auth = useAuthStore()

const showCreate = ref(false)
const newServerName = ref('')
const showSettings = ref(false) // ✅ 新增：控制设置弹窗

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
  contextMenu.value.show = false
}

async function deleteServer() {
  if (!contextMenu.value.serverId) return
  await chat.deleteServer(contextMenu.value.serverId)
  contextMenu.value.show = false
}
</script>

<template>
  <div class="server-list" @click="hideContextMenu">
    <div>
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

      <div v-if="auth.isAdmin" class="server-icon add-server glow-effect" @click="showCreate = true" title="创建服务器">
        +
      </div>

      <!-- Context Menu -->
      <div
        v-if="contextMenu.show && auth.isAdmin"
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <div class="context-menu-item delete" @click="deleteServer">删除服务器</div>
      </div>

      <div v-if="showCreate" class="create-modal" @click.self="showCreate = false">
        <div class="modal-content">
          <h3 class="showCreateH3">创建服务器</h3>
          <input
            v-model="newServerName"
            placeholder="服务器名称"
            @keyup.enter="createServer"
          />
          <div class="modal-actions">
            <button class="glow-effect" @click="showCreate = false">取消</button>
            <button class="primary glow-effect" @click="createServer">创建</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ✅ 底部区域：设置入口（你可以把图标换成自己的） -->
    <div class="bottom-area">
      <div
        class="server-icon glow-effect settings-btn"
        title="设置"
        @click.stop="showSettings = true"
      >
        ⚙
      </div>
    </div>

    <!-- ✅ 设置弹窗 -->
    <Settings v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped>
.server-list {
  width: 80px;
  height: 100vh;
  background: var(--surface-glass-dark);
  border-right: 1px solid rgba(255, 255, 255, 0.011);
  background-color: lab(62.08% 0 -0.01 / 0.292);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px 0;
  backdrop-filter: blur(20px);
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

.create-modal {
  position: fixed;
  top: 50vh;
  left: 50vw;
  transform: translate(-50%, -50%);
  width: 500px;
  height: auto;
  background: rgba(0, 0, 0, 0);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: var(--surface-glass-dark);
  padding: 30px;
  border-radius: var(--radius-lg);
  background: rgba(20,20,22,.95);
  border: 1px solid rgba(255,255,255,.12);
  backdrop-filter: blur(20px);
  width: 300px;
}

.modal-content h3 {
  margin: 0 0 20px 0;
  color: white;
  text-align: left;
  font-size: 18px; 
  font-weight: 700;
}

.modal-content input {
  width: 100%;
  padding: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-md);
  background: var(--surface-glass);
  color: var(--color-text-main);
  margin-bottom: 20px;
}

.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
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

/* Context Menu */
.context-menu {
  position: fixed;
  background: var(--surface-glass-dark);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-md);
  padding: 8px 0;
  min-width: 120px;
  z-index: 2000;
  backdrop-filter: blur(20px);
  box-shadow: var(--shadow-lg);
}

.context-menu-item {
  padding: 10px 16px;
  cursor: pointer;
  color: var(--color-text-main);
  transition: all var(--transition-fast);
  font-size: 14px;
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

/* ✅ 底部区域 */
.bottom-area{
  display:flex;
  flex-direction:column;
  align-items:center;
  padding-bottom: 6px;
}

.settings-btn{
  margin-bottom: 0;
}
</style>
