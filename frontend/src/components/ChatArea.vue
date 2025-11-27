<script setup lang="ts">
import { ref, watch, nextTick, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useWebSocket } from '../composables/useWebSocket'

const chat = useChatStore()
const messageInput = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

let ws: ReturnType<typeof useWebSocket> | null = null

function connectWebSocket(channelId: number) {
  if (ws) {
    ws.disconnect()
  }
  
  ws = useWebSocket(`/ws/chat/${channelId}`)
  
  ws.onMessage((data) => {
    if (data.type === 'message') {
      chat.addMessage({
        id: data.id,
        channel_id: channelId,
        user_id: data.user_id,
        username: data.username,
        content: data.content,
        created_at: data.created_at,
      })
      scrollToBottom()
    }
  })
  
  ws.connect()
}

watch(
  () => chat.currentChannel,
  async (channel) => {
    if (channel && channel.type === 'text') {
      await chat.fetchMessages(channel.id)
      connectWebSocket(channel.id)
      await nextTick()
      scrollToBottom()
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (ws) {
    ws.disconnect()
  }
})

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

function sendMessage() {
  if (!messageInput.value.trim() || !ws) return
  
  ws.send({
    type: 'message',
    content: messageInput.value.trim(),
  })
  
  messageInput.value = ''
}

function formatTime(dateStr: string) {
  const date = new Date(dateStr)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="chat-area">
    <div class="chat-header">
      <span class="channel-hash">#</span>
      <span class="channel-name">{{ chat.currentChannel?.name }}</span>
    </div>

    <div class="messages" ref="messagesContainer">
      <div v-for="msg in chat.messages" :key="msg.id" class="message">
        <div class="message-avatar">{{ msg.username.charAt(0).toUpperCase() }}</div>
        <div class="message-content">
          <div class="message-header">
            <span class="message-author">{{ msg.username }}</span>
            <span class="message-time">{{ formatTime(msg.created_at) }}</span>
          </div>
          <div class="message-text">{{ msg.content }}</div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <input
        v-model="messageInput"
        :placeholder="`Message #${chat.currentChannel?.name || ''}`"
        @keyup.enter="sendMessage"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chat-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #202225;
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.2);
}

.channel-hash {
  color: #72767d;
  font-size: 24px;
  margin-right: 8px;
}

.channel-name {
  font-weight: 600;
  color: #fff;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.message {
  display: flex;
  padding: 4px 0;
  margin-bottom: 16px;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #5865f2;
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: 600;
  color: #fff;
  margin-right: 16px;
  flex-shrink: 0;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.message-author {
  font-weight: 500;
  color: #fff;
}

.message-time {
  font-size: 12px;
  color: #72767d;
}

.message-text {
  color: #dcddde;
  line-height: 1.4;
  word-wrap: break-word;
}

.chat-input {
  padding: 0 16px 24px;
}

.chat-input input {
  width: 100%;
  padding: 12px 16px;
  border: none;
  border-radius: 8px;
  background: #40444b;
  color: #dcddde;
  font-size: 14px;
  box-sizing: border-box;
}

.chat-input input::placeholder {
  color: #72767d;
}

.chat-input input:focus {
  outline: none;
}
</style>
