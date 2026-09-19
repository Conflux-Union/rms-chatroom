/**
 * Lightweight TTS for voice channel join announcements using Web Speech API.
 * No dependencies, runs entirely in the browser.
 */

import { t, currentLocaleTag } from '../i18n'

function isSpeechSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

/**
 * Speak "xxx进入了语音" / "xxx离开了语音" for a participant name. Cancels any
 * ongoing utterance so rapid join/leave events do not queue up. Respects
 * enabled flag.
 */
type AnnounceAction = 'joined' | 'left'

function speak(
  action: AnnounceAction,
  name: string,
  options?: { enabled?: boolean }
): void {
  if (options?.enabled === false) return
  if (!isSpeechSupported()) return

  const displayName = (name || t('common.someone')).trim() || t('common.someone')
  const text = action === 'joined'
    ? t('voice.announceJoined', { name: displayName })
    : t('voice.announceLeft', { name: displayName })

  const synth = window.speechSynthesis
  synth.cancel()

  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = currentLocaleTag()
  utterance.rate = 0.95
  utterance.volume = 1

  synth.speak(utterance)
}

export function announceParticipantJoined(
  name: string,
  options?: { enabled?: boolean }
): void {
  speak('joined', name, options)
}

export function announceParticipantLeft(
  name: string,
  options?: { enabled?: boolean }
): void {
  speak('left', name, options)
}

export { isSpeechSupported }
