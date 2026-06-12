const STORAGE_KEY = 'rms-theme'

type ThemeMode = 'light' | 'dark' | 'system'

function systemPrefersDark(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function apply(mode: ThemeMode) {
  const dark = mode === 'dark' || (mode === 'system' && systemPrefersDark())
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
}

export function getThemeMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'light' || saved === 'dark' ? saved : 'system'
}

export function setThemeMode(mode: ThemeMode) {
  if (mode === 'system') localStorage.removeItem(STORAGE_KEY)
  else localStorage.setItem(STORAGE_KEY, mode)
  apply(mode)
}

/* Sets data-theme on <html> from the saved preference (defaults to the OS
   setting) and keeps it in sync with OS changes while in system mode. */
export function initTheme() {
  apply(getThemeMode())
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (getThemeMode() === 'system') apply('system')
  })
}
