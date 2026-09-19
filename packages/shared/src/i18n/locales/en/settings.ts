import type { Message } from '../../types'
import type { Keys } from '../zh/settings'

export const messages: Record<Keys, Message> = {
  'settings.title': 'Settings',
  'settings.inputDevice': 'Input device',
  'settings.outputDevice': 'Output device',
  'settings.play': 'Play',
  'settings.voiceAnnounce': 'Voice join announcement',
  'settings.voiceAnnounceDesc':
    'Speak the nickname when someone joins or leaves the current voice channel',
  'settings.hotkeyWindow': 'Show/hide window (global hotkey)',
  'settings.hotkeyMic': 'Toggle microphone (global hotkey)',
  'settings.hotkeyPlaceholder': 'Click, then press the shortcut',
  'settings.hotkeyTip': 'Press the key combination you want (e.g. Ctrl + Alt + M)',
  'settings.hotkeyDetected': 'Detected: {acc} (click Save to apply)',
  'settings.telemetry': 'Anonymous error reporting',
  'settings.telemetryDesc':
    'Report crashes and connection quality to help improve the app; never includes message content',
  'settings.language': 'Language',
  'settings.languageSystem': 'Follow system',
}
