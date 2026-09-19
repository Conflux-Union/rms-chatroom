export type Locale = 'zh' | 'en'

export type LocalePreference = 'system' | Locale

/** Parameters for `{name}` placeholders inside messages. */
export type MessageParams = Record<string, string | number>

/**
 * A single translated message. Either a static string containing optional
 * `{name}` placeholders, or a function for cases plain placeholders cannot
 * express (e.g. English plurals or locale-specific date shapes).
 */
export type Message = string | ((params: MessageParams) => string)
