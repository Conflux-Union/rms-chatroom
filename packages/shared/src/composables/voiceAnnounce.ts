/**
 * Lightweight TTS for voice channel join announcements using Web Speech API.
 * No dependencies, runs entirely in the browser.
 */

function isSpeechSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

/**
 * Speak "xxx进入了语音" for a participant name. Cancels any ongoing utterance
 * so rapid joins do not queue up. Respects enabled flag.
 */
export function announceParticipantJoined(
  name: string,
  options?: { enabled?: boolean }
): void {
  if (options?.enabled === false) return
  if (!isSpeechSupported()) return

  const displayName = (name || '有人').trim() || '有人'
  const text = `${displayName}进入了语音`

  const synth = window.speechSynthesis
  synth.cancel()

  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = 'zh-CN'
  utterance.rate = 0.95
  utterance.volume = 1

  synth.speak(utterance)
}

export { isSpeechSupported }
