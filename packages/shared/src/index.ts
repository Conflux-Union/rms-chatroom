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
export { startNoiseCancel } from './composables/noiseCancle'
export type { NoiseCancelMode, NoiseCancelSession } from './composables/noiseCancle'

// Platform detection
export const isElectron = typeof window !== 'undefined' && !!(window as any).electronAPI
