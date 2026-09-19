import { computed, ref, watch } from 'vue'
import type { Locale, LocalePreference, Message, MessageParams } from './types'

import { messages as zhCommon } from './locales/zh/common'
import { messages as zhApp } from './locales/zh/app'
import { messages as zhInvite } from './locales/zh/invite'
import { messages as zhChat } from './locales/zh/chat'
import { messages as zhChannels } from './locales/zh/channels'
import { messages as zhVoice } from './locales/zh/voice'
import { messages as zhMusic } from './locales/zh/music'
import { messages as zhSettings } from './locales/zh/settings'
import { messages as zhTime } from './locales/zh/time'
import { messages as zhFeedback } from './locales/zh/feedback'

import { messages as enCommon } from './locales/en/common'
import { messages as enApp } from './locales/en/app'
import { messages as enInvite } from './locales/en/invite'
import { messages as enChat } from './locales/en/chat'
import { messages as enChannels } from './locales/en/channels'
import { messages as enVoice } from './locales/en/voice'
import { messages as enMusic } from './locales/en/music'
import { messages as enSettings } from './locales/en/settings'
import { messages as enTime } from './locales/en/time'
import { messages as enFeedback } from './locales/en/feedback'

// zh is the source of truth for keys; every en namespace is typed as
// Record<Keys, Message> so a missing translation fails the vue-tsc build.
const zhMessages = {
  ...zhCommon,
  ...zhApp,
  ...zhInvite,
  ...zhChat,
  ...zhChannels,
  ...zhVoice,
  ...zhMusic,
  ...zhSettings,
  ...zhTime,
  ...zhFeedback,
}

export type MessageKey = keyof typeof zhMessages

const zhDict = zhMessages as unknown as Record<string, Message>
const enDict: Record<MessageKey, Message> = {
  ...enCommon,
  ...enApp,
  ...enInvite,
  ...enChat,
  ...enChannels,
  ...enVoice,
  ...enMusic,
  ...enSettings,
  ...enTime,
  ...enFeedback,
}

const STORAGE_KEY = 'rms-lang'

function detectSystemLocale(): Locale {
  if (typeof navigator === 'undefined') return 'zh'
  const candidates = [...(navigator.languages ?? []), navigator.language ?? '']
  for (const tag of candidates) {
    if (tag && tag.toLowerCase().startsWith('zh')) return 'zh'
  }
  return 'en'
}

function readStoredPreference(): LocalePreference {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'zh' || v === 'en') return v
  } catch {
    // Storage unavailable (private mode etc.) — follow the system locale.
  }
  return 'system'
}

const preference = ref<LocalePreference>(readStoredPreference())
const systemLocale = ref<Locale>(detectSystemLocale())

/** The active locale: the manual preference when set, otherwise the system locale. */
export const locale = computed<Locale>(() =>
  preference.value === 'system' ? systemLocale.value : preference.value,
)

export function getLocalePreference(): LocalePreference {
  return preference.value
}

export function setLocalePreference(pref: LocalePreference): void {
  preference.value = pref
  try {
    if (pref === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, pref)
  } catch {
    // Same as readStoredPreference: nothing we can do, keep the in-memory value.
  }
}

/** BCP 47 tag for Intl/locale-sensitive APIs (e.g. toLocaleTimeString). */
export function currentLocaleTag(): string {
  return locale.value === 'zh' ? 'zh-CN' : 'en-US'
}

function interpolate(template: string, params?: MessageParams): string {
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  )
}

/**
 * Translate a message key in the active locale. Reactive: templates and
 * computeds that call t() re-render when the locale changes. Note that strings
 * captured once into non-reactive state (e.g. an error message stored in a
 * Pinia ref) stay in the locale active at capture time until re-set.
 */
export function t(key: MessageKey, params?: MessageParams): string {
  const dict: Record<string, Message> = locale.value === 'en' ? enDict : zhDict
  const raw = dict[key] ?? zhDict[key] ?? key
  if (typeof raw === 'function') return raw(params ?? {})
  return interpolate(raw, params)
}

/** Apply the active locale to the document; call once during app bootstrap. */
export function initI18n(): void {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = currentLocaleTag()
  }
}

watch(locale, () => {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = currentLocaleTag()
  }
})

export function useI18n() {
  return { t, locale, getLocalePreference, setLocalePreference }
}

export type { Locale, LocalePreference, Message, MessageParams } from './types'
