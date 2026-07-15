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
export { initTheme, getThemeMode, setThemeMode } from './utils/theme'
export {
  installTelemetry,
  reportTelemetryEvent,
  isTelemetryEnabled,
  setTelemetryEnabled,
} from './utils/telemetry'

// Platform detection
export const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
// Backward compat alias
export const isElectron = isTauri
