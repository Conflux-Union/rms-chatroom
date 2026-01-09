<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { MessageSquare, Loader, ChevronUp, ChevronDown, User, Clock } from 'lucide-vue-next'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const auth = useAuthStore()
const chat = useChatStore()

// Props
interface Props {
  isExpanded: boolean
}
const props = defineProps<Props>()

// Emits
const emit = defineEmits<{
  'toggle-expand': []
}>()

// State
const transcriptionResults = ref<Array<{
  id: string
  speaker: string
  text: string
  timestamp: Date
  confidence?: number
}>>([])

const summaryProgress = ref(0)
const summaryResult = ref('')
const summaryLoading = ref(false)
const sessionId = ref<string | null>(null)
const isTranscribing = ref(false)
const error = ref('')
const scrollContainer = ref<HTMLElement | null>(null)

// WebSocket connection
let websocket: WebSocket | null = null
let eventSource: EventSource | null = null

// Computed
const hasTranscriptionTask = computed(() => {
  return transcriptionResults.value.length > 0 || isTranscribing.value
})

const hasPermission = computed(() => {
  return (auth.user?.permission_level ?? 0) >= 3
})

// Methods
function scrollToBottom() {
  if (scrollContainer.value) {
    scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
  }
}

function connectWebSocket() {
  if (!sessionId.value || !chat.currentChannel) {
    return
  }

  const wsUrl = `${API_BASE.replace('http', 'ws')}/ws/transcription/${chat.currentChannel.id}/${sessionId.value}?token=${auth.token}`

  websocket = new WebSocket(wsUrl)

  websocket.onopen = () => {
    if (import.meta.env.DEV) {
      console.log('[TranscriptionPanel] WebSocket connected')
    }
  }

  websocket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)

      if (data.type === 'transcription_result') {
        // Add new transcription result
        transcriptionResults.value.push({
          id: data.id || Date.now().toString(),
          speaker: data.speaker || '未知',
          text: data.text,
          timestamp: new Date(data.timestamp),
          confidence: data.confidence
        })

        // Auto scroll to bottom
        setTimeout(scrollToBottom, 50)
      } else if (data.type === 'session_ended') {
        isTranscribing.value = false
        sessionId.value = null
      } else if (data.type === 'error') {
        error.value = data.message
        isTranscribing.value = false
      }
    } catch (e) {
      if (import.meta.env.DEV) {
        console.error('[TranscriptionPanel] Failed to parse WebSocket message:', e)
      }
    }
  }

  websocket.onerror = (err) => {
    if (import.meta.env.DEV) {
      console.error('[TranscriptionPanel] WebSocket error:', err)
    }
  }

  websocket.onclose = (event) => {
    if (import.meta.env.DEV) {
      console.log('[TranscriptionPanel] WebSocket closed:', event.code, event.reason)
    }
    websocket = null
  }
}

function connectSummarySSE() {
  if (!sessionId.value || !chat.currentChannel) return

  const sseUrl = `${API_BASE}/api/voice-recognition/summary/stream/${sessionId.value}?token=${auth.token}`

  eventSource = new EventSource(sseUrl)

  eventSource.onopen = () => {
    summaryLoading.value = true
    summaryProgress.value = 0
  }

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)

      if (data.type === 'progress') {
        summaryProgress.value = data.progress
      } else if (data.type === 'summary') {
        summaryResult.value = data.content
        summaryLoading.value = false
        summaryProgress.value = 100
        eventSource?.close()
        eventSource = null
      } else if (data.type === 'error') {
        error.value = data.message
        summaryLoading.value = false
        eventSource?.close()
        eventSource = null
      }
    } catch (e) {
      if (import.meta.env.DEV) {
        console.error('[TranscriptionPanel] Failed to parse SSE message:', e)
      }
    }
  }

  eventSource.onerror = () => {
    if (import.meta.env.DEV) {
      console.error('[TranscriptionPanel] SSE connection error')
    }
    summaryLoading.value = false
    eventSource?.close()
    eventSource = null
  }
}

async function requestVoiceHelp() {
  try {
    const helpUrl = `${API_BASE}/api/voice-recognition/help`

    const resp = await fetch(helpUrl, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${auth.token}`
      }
    })

    if (resp.ok) {
      const data = await resp.json()
      console.log('[TranscriptionPanel] Voice help received:', data)
    } else {
      const text = await resp.text()
      console.warn('[TranscriptionPanel] Failed to fetch voice help:', resp.status, text)
    }
  } catch (err) {
    console.error('[TranscriptionPanel] Error fetching voice help:', err)
  }
}

async function startTranscription() {
  console.log('[TranscriptionPanel] startTranscription called')
  console.log('[TranscriptionPanel] - Current channel:', chat.currentChannel?.id)
  console.log('[TranscriptionPanel] - Is transcribing:', isTranscribing.value)
  console.log('[TranscriptionPanel] - Has permission:', hasPermission.value)
  console.log('[TranscriptionPanel] - User permission level:', auth.user?.permission_level)
  
  if (!chat.currentChannel || isTranscribing.value || !hasPermission.value) {
    console.warn('[TranscriptionPanel] Conditions not met for starting transcription')
    console.warn('[TranscriptionPanel] - Channel exists:', !!chat.currentChannel)
    console.warn('[TranscriptionPanel] - Not already transcribing:', !isTranscribing.value)
    console.warn('[TranscriptionPanel] - Has permission:', hasPermission.value)
    return false
  }
  
  try {
    error.value = ''
    
    const requestBody = {
      room_config: {
        room_id: `server_${chat.currentChannel.server_id}_channel_${chat.currentChannel.id}`,
        type: 'livekit',
        name: chat.currentChannel.name || `channel_${chat.currentChannel.id}`,
        server_id: chat.currentChannel.server_id,
        channel_id: chat.currentChannel.id,
        livekit_room_name: `voice_${chat.currentChannel.id}`
      },
      voice_config: {
        language: 'zh-CN',
        sample_rate: 16000
      }
     }
    // Backend expects POST /api/voice-recognition/sessions
    const requestUrl = `${API_BASE}/api/voice-recognition/sessions`
    
    console.log('[TranscriptionPanel] Sending POST request')
    console.log('[TranscriptionPanel] - URL:', requestUrl)
    console.log('[TranscriptionPanel] - Body:', requestBody)
    console.log('[TranscriptionPanel] - Token (first 20 chars):', auth.token?.substring(0, 20) + '...')
    
    const response = await fetch(requestUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify(requestBody)
    })
    
    console.log('[TranscriptionPanel] Response received')
    console.log('[TranscriptionPanel] - Status:', response.status)
    console.log('[TranscriptionPanel] - OK:', response.ok)
    
    if (!response.ok) {
      let errMsg = 'Failed to start transcription'
      try {
        const err = await response.json()
        console.error('[TranscriptionPanel] ❌ Error response:', err)
        errMsg = err.detail || JSON.stringify(err)
      } catch (parseErr) {
        console.error('[TranscriptionPanel] ❌ Failed to parse error response:', parseErr)
      }

      // Map common backend errors to user-friendly messages
      if (/Invalid URL|No scheme supplied|trainsction/.test(String(errMsg))) {
        error.value = '服务器配置错误：语音转录服务 URL 无效，请联系管理员检查 voice_service_url 配置。'
      } else if (/Connection refused|Failed to establish a new connection|Max retries exceeded|无法连接到语音服务|连接语音服务.*超时/.test(String(errMsg))) {
        error.value = '语音转录服务不可用或无法连接，请稍后重试或联系管理员检查服务状态。'
      } else if (/语音服务请求失败|语音服务不可用/.test(String(errMsg))) {
        error.value = '语音服务请求失败：' + errMsg
      } else {
        error.value = errMsg
      }

      console.error('[TranscriptionPanel] ❌ Failed to start transcription:', errMsg)

      // 请求 help 并在控制台打印（在所有请求之后）
      await requestVoiceHelp()

      return false
    }
    
    const data = await response.json()
    console.log('[TranscriptionPanel] ✅ Success response:', data)
    
    sessionId.value = data.session_id
    isTranscribing.value = true
    
    console.log('[TranscriptionPanel] - Session ID:', sessionId.value)
    console.log('[TranscriptionPanel] - Is transcribing:', isTranscribing.value)
    
    // Connect WebSocket for real-time results
    console.log('[TranscriptionPanel] Connecting WebSocket...')
    connectWebSocket()

    // 在所有请求之后请求 help 并打印（用户点击后）
    await requestVoiceHelp()

    return true
    
  } catch (e) {
    const raw = e instanceof Error ? e.message : String(e)
    console.error('[TranscriptionPanel] ❌ Failed to start transcription:', e)
    console.error('[TranscriptionPanel] Error details:', {
      message: raw,
      stack: e instanceof Error ? e.stack : undefined,
      error: e
    })

    if (/Invalid URL|No scheme supplied|trainsction/.test(raw)) {
      error.value = '服务器配置错误：语音转录服务 URL 无效，请联系管理员检查 voice_service_url 配置。'
    } else if (/Connection refused|Failed to establish a new connection|Max retries exceeded|无法连接到语音服务|连接语音服务.*超时/.test(raw)) {
      error.value = '语音转录服务不可用或无法连接，请稍后重试或联系管理员检查服务状态。'
    } else if (/语音服务请求失败|语音服务不可用/.test(raw)) {
      error.value = '语音服务请求失败：' + raw
    } else {
      error.value = e instanceof Error ? e.message : '启动转录失败'
    }

    // 在异常路径也尝试请求 help 并打印，保证在所有请求之后执行
    await requestVoiceHelp()

    // Do not re-throw when backend is unreachable or misconfigured; return false so UI can react.
    return false
  }
}

async function stopTranscription() {
  console.log('[TranscriptionPanel] stopTranscription called')
  console.log('[TranscriptionPanel] - Session ID:', sessionId.value)
  console.log('[TranscriptionPanel] - Has permission:', hasPermission.value)
  
  if (!sessionId.value || !hasPermission.value) {
    console.warn('[TranscriptionPanel] Conditions not met for stopping transcription')
    console.warn('[TranscriptionPanel] - Has session ID:', !!sessionId.value)
    console.warn('[TranscriptionPanel] - Has permission:', hasPermission.value)
    return false
  }
  
  try {
    // Backend expects DELETE /api/voice-recognition/sessions/{session_id}
    const requestUrl = `${API_BASE}/api/voice-recognition/sessions/${sessionId.value}`
    console.log('[TranscriptionPanel] Sending DELETE request to stop')
    console.log('[TranscriptionPanel] - URL:', requestUrl)
    
    const response = await fetch(requestUrl, {
      method: 'DELETE',
      headers: {
        Authorization: `Bearer ${auth.token}`,
      }
    })
    
    console.log('[TranscriptionPanel] Stop response received')
    console.log('[TranscriptionPanel] - Status:', response.status)
    console.log('[TranscriptionPanel] - OK:', response.ok)
    
    if (response.ok) {
      isTranscribing.value = false
      console.log('[TranscriptionPanel] ✅ Transcription stopped successfully')
      console.log('[TranscriptionPanel] - Results count:', transcriptionResults.value.length)

      // Start summary generation
      if (transcriptionResults.value.length > 0) {
        console.log('[TranscriptionPanel] Starting summary generation...')
        connectSummarySSE()
      } else {
        console.log('[TranscriptionPanel] No results to summarize')
      }

      // Close WebSocket
      if (websocket) {
        console.log('[TranscriptionPanel] Closing WebSocket connection')
        websocket.close()
        websocket = null
      }

      // 在停止操作后请求 help 并打印
      await requestVoiceHelp()

      return true
    } else {
      const errorText = await response.text()
      console.error('[TranscriptionPanel] ❌ Failed to stop, response not OK')
      console.error('[TranscriptionPanel] Error response:', errorText)

      // 请求 help 并打印（出错时也执行）
      await requestVoiceHelp()

      return false
    }
  } catch (e) {
    console.error('[TranscriptionPanel] ❌ Failed to stop transcription:', e)
    console.error('[TranscriptionPanel] Error details:', {
      message: e instanceof Error ? e.message : String(e),
      stack: e instanceof Error ? e.stack : undefined
    })
    const raw = e instanceof Error ? e.message : String(e)
    if (/Invalid URL|No scheme supplied|trainsction/.test(raw)) {
      error.value = '服务器配置错误：语音转录服务 URL 无效，请联系管理员检查 voice_service_url 配置。'
    } else if (/Connection refused|Failed to establish a new connection|Max retries exceeded|无法连接到语音服务|连接语音服务.*超时/.test(raw)) {
      error.value = '语音转录服务不可用或无法连接，请稍后重试或联系管理员检查服务状态。'
    } else if (/语音服务请求失败|语音服务不可用/.test(raw)) {
      error.value = '语音服务请求失败：' + raw
    } else {
      error.value = '停止转录失败'
    }

    // 在异常路径也尝试请求 help 并打印
    await requestVoiceHelp()

    return false
  }
}

function clearResults() {
  transcriptionResults.value = []
  summaryResult.value = ''
  summaryProgress.value = 0
  summaryLoading.value = false
  error.value = ''
  sessionId.value = null
  isTranscribing.value = false
  
  // Close connections
  if (websocket) {
    websocket.close()
    websocket = null
  }
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

// Lifecycle
onMounted(() => {
  console.log('[TranscriptionPanel] Component mounted - Initial state report')
  console.log('[TranscriptionPanel] - API_BASE:', API_BASE)
  console.log('[TranscriptionPanel] - User:', auth.user)
  console.log('[TranscriptionPanel] - Has permission:', hasPermission.value)
  console.log('[TranscriptionPanel] - Permission level:', auth.user?.permission_level)
  console.log('[TranscriptionPanel] - Current channel:', chat.currentChannel)
  console.log('[TranscriptionPanel] - Is expanded:', props.isExpanded)
  console.log('[TranscriptionPanel] - Has transcription task:', hasTranscriptionTask.value)
  console.log('[TranscriptionPanel] - Is transcribing:', isTranscribing.value)
  console.log('[TranscriptionPanel] - Session ID:', sessionId.value)
  console.log('[TranscriptionPanel] - Token exists:', !!auth.token)
  console.log('[TranscriptionPanel] - Environment:', {
    VITE_API_BASE: import.meta.env.VITE_API_BASE,
    MODE: import.meta.env.MODE,
    DEV: import.meta.env.DEV,
    PROD: import.meta.env.PROD
  })
  
  // Auto scroll to bottom when new results arrive
  watch(() => transcriptionResults.value.length, () => {
    setTimeout(scrollToBottom, 50)
  })
  
  // Periodic status check every 30 seconds
  setInterval(() => {
    console.log('[TranscriptionPanel] Periodic status check')
    console.log('[TranscriptionPanel] - Is transcribing:', isTranscribing.value)
    console.log('[TranscriptionPanel] - Session ID:', sessionId.value)
    console.log('[TranscriptionPanel] - Results count:', transcriptionResults.value.length)
    console.log('[TranscriptionPanel] - WebSocket connected:', websocket?.readyState === WebSocket.OPEN)
    console.log('[TranscriptionPanel] - Error:', error.value)
  }, 30000)
})

onUnmounted(() => {
  console.log('[TranscriptionPanel] Component unmounting - cleanup')
  console.log('[TranscriptionPanel] - Active session:', sessionId.value)
  console.log('[TranscriptionPanel] - Was transcribing:', isTranscribing.value)
  
  // Clean up connections
  if (websocket) {
    console.log('[TranscriptionPanel] Closing WebSocket on unmount')
    websocket.close()
  }
  if (eventSource) {
    console.log('[TranscriptionPanel] Closing EventSource on unmount')
    eventSource.close()
  }
})

// Expose methods for parent component
defineExpose({
  startTranscription,
  stopTranscription,
  clearResults,
  hasTranscriptionTask
})
</script>

<template>
  <div class="transcription-panel" :class="{ expanded: props.isExpanded }">
    <!-- Panel Header -->
    <div class="panel-header" @click="emit('toggle-expand')">
      <MessageSquare :size="16" />
      <span class="header-title">语音转文字</span>
      <div class="header-status">
        <span v-if="isTranscribing" class="status recording">录制中</span>
        <span v-else-if="hasTranscriptionTask" class="status has-content">有内容</span>
        <span v-else class="status empty">暂无任务</span>
      </div>
      <button class="expand-toggle">
        <ChevronUp v-if="props.isExpanded" :size="16" />
        <ChevronDown v-else :size="16" />
      </button>
    </div>

    <!-- Panel Content -->
    <div v-if="props.isExpanded" class="panel-content">
      <!-- Error Display -->
      <div v-if="error" class="error-message">
        {{ error }}
        <button class="error-dismiss" @click="error = ''">×</button>
      </div>

      <!-- Empty State -->
      <div v-if="!hasTranscriptionTask" class="empty-state">
        <MessageSquare :size="48" class="empty-icon" />
        <p class="empty-text">当前没有语音转文字任务</p>
        <p class="empty-hint">点击下方按钮开始录制</p>
      </div>

      <!-- Transcription Results -->
      <div v-else class="transcription-content">
        <div class="results-section">
          <div class="section-header">
            <h4>实时转录结果</h4>
            <button v-if="!isTranscribing" class="clear-btn" @click="clearResults">
              清除
            </button>
          </div>
          
          <div ref="scrollContainer" class="results-container">
            <div
              v-for="result in transcriptionResults"
              :key="result.id"
              class="result-item"
            >
              <div class="result-header">
                <div class="speaker-info">
                  <User :size="14" />
                  <span class="speaker-name">{{ result.speaker }}</span>
                </div>
                <div class="result-time">
                  <Clock :size="12" />
                  <span class="time-text">{{ formatTime(result.timestamp) }}</span>
                </div>
              </div>
              <div class="result-text">{{ result.text }}</div>
              <div v-if="result.confidence" class="result-confidence">
                置信度: {{ Math.round(result.confidence * 100) }}%
              </div>
            </div>
            
            <!-- Loading indicator for active transcription -->
            <div v-if="isTranscribing" class="transcribing-indicator">
              <Loader :size="16" class="spinner" />
              <span>正在识别语音...</span>
            </div>
          </div>
        </div>

        <!-- Summary Section -->
        <div v-if="summaryLoading || summaryResult" class="summary-section">
          <div class="section-header">
            <h4>内容总结</h4>
          </div>
          
          <div v-if="summaryLoading" class="summary-loading">
            <div class="loading-header">
              <Loader :size="16" class="spinner" />
              <span>正在生成总结...</span>
            </div>
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: summaryProgress + '%' }"></div>
            </div>
            <div class="progress-text">{{ summaryProgress }}%</div>
          </div>
          
          <div v-else-if="summaryResult" class="summary-result">
            {{ summaryResult }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.transcription-panel {
  background: var(--surface-glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 255, 255, 0.15);
  margin-top: 12px;
  overflow: hidden;
  transition: all 0.3s ease;
}

.transcription-panel.expanded {
  max-height: 400px;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.2s ease;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.panel-header:hover {
  background: rgba(255, 255, 255, 0.05);
}

.header-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-main);
}

.header-status {
  margin-left: auto;
}

.status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.status.recording {
  background: var(--color-error, #ef4444);
  color: #fff;
}

.status.has-content {
  background: var(--color-success, #10b981);
  color: #fff;
}

.status.empty {
  background: rgba(255, 255, 255, 0.1);
  color: var(--color-text-muted);
}

.expand-toggle {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: all 0.2s ease;
}

.expand-toggle:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--color-text-main);
}

.panel-content {
  max-height: 350px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.error-message {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: var(--radius-md);
  margin: 12px 16px;
  font-size: 13px;
  color: var(--color-error, #ef4444);
}

.error-dismiss {
  background: none;
  border: none;
  color: var(--color-error, #ef4444);
  cursor: pointer;
  font-size: 16px;
  padding: 0 4px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  text-align: center;
}

.empty-icon {
  color: var(--color-text-muted);
  margin-bottom: 16px;
  opacity: 0.6;
}

.empty-text {
  color: var(--color-text-main);
  font-size: 16px;
  font-weight: 500;
  margin: 0 0 8px 0;
}

.empty-hint {
  color: var(--color-text-muted);
  font-size: 13px;
  margin: 0;
}

.transcription-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.results-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 8px;
}

.section-header h4 {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-main);
  margin: 0;
}

.clear-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  font-size: 12px;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
}

.clear-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  color: var(--color-text-main);
}

.results-container {
  flex: 1;
  overflow-y: auto;
  padding: 0 16px 12px;
  scroll-behavior: smooth;
}

.result-item {
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  padding: 12px;
  margin-bottom: 8px;
  border-left: 3px solid var(--color-primary, #6366f1);
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.speaker-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.speaker-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-primary, #6366f1);
}

.result-time {
  display: flex;
  align-items: center;
  gap: 4px;
}

.time-text {
  font-size: 11px;
  color: var(--color-text-muted);
}

.result-text {
  font-size: 14px;
  line-height: 1.4;
  color: var(--color-text-main);
  word-wrap: break-word;
}

.result-confidence {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-top: 4px;
}

.transcribing-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: rgba(16, 185, 129, 0.1);
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--color-success, #10b981);
}

.summary-section {
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  margin-top: 8px;
}

.summary-loading {
  padding: 0 16px 16px;
}

.loading-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--color-primary, #6366f1);
}

.progress-bar {
  width: 100%;
  height: 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-fill {
  height: 100%;
  background: var(--color-primary, #6366f1);
  transition: width 0.3s ease;
  border-radius: 2px;
}

.progress-text {
  font-size: 12px;
  color: var(--color-text-muted);
  text-align: center;
}

.summary-result {
  padding: 0 16px 16px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--color-text-main);
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  margin: 0 16px 16px;
  padding: 12px;
  word-wrap: break-word;
}

.spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Scrollbar styling */
.results-container::-webkit-scrollbar {
  width: 6px;
}

.results-container::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
}

.results-container::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 3px;
}

.results-container::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.5);
}
</style>
