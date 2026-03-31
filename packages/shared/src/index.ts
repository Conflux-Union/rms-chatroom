// Types
export * from './types'

// Stores
export { useAuthStore } from './stores/auth'
export { useChatStore } from './stores/chat'
export { useVoiceStore } from './stores/voice'
export { useMusicStore } from './stores/music'
export type { VoiceParticipant, AudioDevice, ScreenShareInfo } from './stores/voice'

// Composables
export { useWebSocket } from './composables/useWebSocket'
export { useChatWebSocket } from './composables/useChatWebSocket'
export { useGlowEffect } from './composables/useGlowEffect'
export { useSwipe } from './composables/useSwipe'

// Utils
export {
  parseUTCDateTime,
  formatDateTime,
  formatTime,
  formatTimeFromDate,
  getTimestamp,
  diffMinutes,
  isWithinMinutes,
  formatDuration,
} from './utils/datetime'

// Platform detection
export const isElectron = typeof window !== 'undefined' && !!(window as any).electronAPI
