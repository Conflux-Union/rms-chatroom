import type { AudioCaptureOptions } from 'livekit-client'

export const STORAGE_KEY_AI_NOISE = 'rms-voice-ai-noise-suppression'

export function readAiNoiseSuppression(): boolean {
  if (typeof localStorage === 'undefined') return true
  return localStorage.getItem(STORAGE_KEY_AI_NOISE) !== 'false'
}

// With the RNNoise filter active the browser's built-in WebRTC noise
// suppression must be off, otherwise the two suppressors stack and the voice
// turns muddy. AEC and AGC stay on in both modes.
export function buildAudioCaptureDefaults(
  aiNoiseSuppression: boolean,
  deviceId?: string,
): AudioCaptureOptions {
  return {
    autoGainControl: true,
    noiseSuppression: !aiNoiseSuppression,
    echoCancellation: true,
    deviceId: deviceId || undefined,
  }
}
