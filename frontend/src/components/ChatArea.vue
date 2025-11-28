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
  // Backend stores UTC time but isoformat() omits timezone suffix
  const utcStr = dateStr.endsWith('Z') ? dateStr : dateStr + 'Z'
  const date = new Date(utcStr)
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
        :placeholder="`发送消息到 #${chat.currentChannel?.name || ''}`"
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
  min-height: 0;
  overflow: hidden;
}

.chat-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
}

.channel-hash {
  color: var(--color-text-muted);
  font-size: 24px;
  margin-right: 8px;
}

.channel-name {
  font-weight: 600;
  color: var(--color-text-main);
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
  background: var(--color-gradient-primary);
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
  overflow: hidden;
}

.message-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.message-author {
  font-weight: 500;
  color: var(--color-text-main);
}

.message-time {
  font-size: 12px;
  color: var(--color-text-muted);
}

.message-text {
  color: var(--color-text-main);
  line-height: 1.4;
  word-wrap: break-word;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.chat-input {
  padding: 0 16px 24px;
}

.chat-input input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--surface-glass-input);
  color: var(--color-text-main);
  font-size: 14px;
  box-sizing: border-box;
  transition: all var(--transition-fast);
}

.chat-input input::placeholder {
  color: var(--color-text-muted);
}

.chat-input input:focus {
  outline: none;
  background: var(--surface-glass-input-focus);
  border-color: rgba(255, 255, 255, 0.5);
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

/* Mobile Responsive */
@media (max-width: 768px) {
  .chat-header {
    height: 44px;
    padding: 0 12px;
  }

  .channel-hash {
    font-size: 20px;
  }

  .channel-name {
    font-size: 15px;
  }

  .messages {
    padding: 12px;
  }

  .message {
    margin-bottom: 12px;
  }

  .message-avatar {
    width: 32px;
    height: 32px;
    margin-right: 10px;
    font-size: 14px;
  }

  .message-author {
    font-size: 14px;
  }

  .message-text {
    font-size: 14px;
  }

  .chat-input {
    padding: 0 12px 16px;
  }

  .chat-input input {
    padding: 10px 14px;
    font-size: 15px;
  }
}
</style>
