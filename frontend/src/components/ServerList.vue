<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'

const chat = useChatStore()
const auth = useAuthStore()
const showCreate = ref(false)
const newServerName = ref('')

async function selectServer(serverId: number) {
  await chat.fetchServer(serverId)
}

async function createServer() {
  if (!newServerName.value.trim()) return
  await chat.createServer(newServerName.value.trim())
  newServerName.value = ''
  showCreate.value = false
}
</script>

<template>
  <div class="server-list">
    <div
      v-for="server in chat.servers"
      :key="server.id"
      class="server-icon"
      :class="{ active: chat.currentServer?.id === server.id }"
      @click="selectServer(server.id)"
      :title="server.name"
    >
      {{ server.name.charAt(0).toUpperCase() }}
    </div>

    <div v-if="auth.isAdmin" class="server-icon add-server" @click="showCreate = true" title="Create Server">
      +
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
          <button @click="showCreate = false">Cancel</button>
          <button class="primary" @click="createServer">Create</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.server-list {
  width: 72px;
  background: #202225;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  overflow-y: auto;
}

.server-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #36393f;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  font-weight: 600;
  font-size: 18px;
  transition: border-radius 0.2s, background 0.2s;
}

.server-icon:hover,
.server-icon.active {
  border-radius: 16px;
  background: #5865f2;
}

.add-server {
  background: transparent;
  border: 2px dashed #3ba55c;
  color: #3ba55c;
  font-size: 24px;
}

.add-server:hover {
  background: #3ba55c;
  color: #fff;
  border-color: #3ba55c;
}

.create-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-content {
  background: #36393f;
  padding: 24px;
  border-radius: 8px;
  width: 300px;
}

.modal-content h3 {
  margin: 0 0 16px;
  color: #fff;
}

.modal-content input {
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 4px;
  background: #202225;
  color: #fff;
  margin-bottom: 16px;
  box-sizing: border-box;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.modal-actions button {
  padding: 8px 16px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  background: #4f545c;
  color: #fff;
}

.modal-actions button.primary {
  background: #5865f2;
}

.modal-actions button:hover {
  opacity: 0.9;
}
</style>
