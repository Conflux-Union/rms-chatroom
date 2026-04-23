import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''
const SILENT_SSO_LOGOUT_KEY = 'rms_silent_sso_logged_out'
const ACCESS_TOKEN_KEY = 'access_token'
const REFRESH_TOKEN_KEY = 'refresh_token'
const LEGACY_TOKEN_KEY = 'token'
const ACCESS_TOKEN_COOKIE = 'rms_access_token'
const REFRESH_TOKEN_COOKIE = 'rms_refresh_token'
const REFRESH_TOKEN_MAX_AGE = 30 * 24 * 60 * 60

function canUseCookieStorage(): boolean {
  if (typeof window === 'undefined') return false
  return window.location.protocol === 'http:' || window.location.protocol === 'https:'
}

function usesSingleJwtSession(): boolean {
  return canUseCookieStorage()
}

function isSilentLoginSuppressed(): boolean {
  if (typeof localStorage === 'undefined') return false
  return localStorage.getItem(SILENT_SSO_LOGOUT_KEY) === '1'
}

function getCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const prefix = `${encodeURIComponent(name)}=`
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length))
    }
  }
  return null
}

function deleteCookie(name: string) {
  if (typeof document === 'undefined') return
  document.cookie = `${encodeURIComponent(name)}=; Max-Age=0; Path=/; SameSite=Lax`
}

function parseJwtExpirySeconds(token: string): number | null {
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const payload = JSON.parse(atob(padded))
    const exp = Number(payload?.exp)
    if (!Number.isFinite(exp)) return null
    return Math.max(0, Math.floor(exp - Date.now() / 1000))
  } catch {
    return null
  }
}

function setCookie(name: string, value: string, maxAge?: number | null) {
  if (typeof document === 'undefined') return
  const attrs = [
    `${encodeURIComponent(name)}=${encodeURIComponent(value)}`,
    'Path=/',
    'SameSite=Lax'
  ]
  if (typeof maxAge === 'number' && maxAge >= 0) {
    attrs.push(`Max-Age=${maxAge}`)
  }
  if (typeof window !== 'undefined' && window.location.protocol === 'https:') {
    attrs.push('Secure')
  }
  document.cookie = attrs.join('; ')
}

function getStoredAccessToken(): string | null {
  if (typeof localStorage === 'undefined' && !canUseCookieStorage()) return null
  if (canUseCookieStorage()) {
    return getCookie(ACCESS_TOKEN_COOKIE)
  }
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

function getStoredRefreshToken(): string | null {
  if (usesSingleJwtSession()) return null
  if (typeof localStorage === 'undefined' && !canUseCookieStorage()) return null
  if (canUseCookieStorage()) {
    return getCookie(REFRESH_TOKEN_COOKIE)
  }
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

function setStoredTokens(newAccessToken: string, newRefreshToken?: string) {
  if (usesSingleJwtSession()) {
    setCookie(ACCESS_TOKEN_COOKIE, newAccessToken, parseJwtExpirySeconds(newAccessToken))
    deleteCookie(REFRESH_TOKEN_COOKIE)
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    return
  }

  if (canUseCookieStorage()) {
    setCookie(ACCESS_TOKEN_COOKIE, newAccessToken, parseJwtExpirySeconds(newAccessToken))
    if (newRefreshToken) {
      setCookie(REFRESH_TOKEN_COOKIE, newRefreshToken, REFRESH_TOKEN_MAX_AGE)
    }
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    return
  }

  if (typeof localStorage === 'undefined') return
  localStorage.setItem(ACCESS_TOKEN_KEY, newAccessToken)
  if (newRefreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, newRefreshToken)
  }
}

function clearStoredTokens() {
  if (canUseCookieStorage()) {
    deleteCookie(ACCESS_TOKEN_COOKIE)
    deleteCookie(REFRESH_TOKEN_COOKIE)
  }
  if (typeof localStorage === 'undefined') return
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(LEGACY_TOKEN_KEY)
}

function migrateLegacyTokenStorage() {
  if (typeof window === 'undefined') return

  const legacyAccessToken = localStorage.getItem(ACCESS_TOKEN_KEY) || localStorage.getItem(LEGACY_TOKEN_KEY)
  const legacyRefreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)

  if (usesSingleJwtSession()) {
    if (!getCookie(ACCESS_TOKEN_COOKIE) && legacyAccessToken) {
      setCookie(ACCESS_TOKEN_COOKIE, legacyAccessToken, parseJwtExpirySeconds(legacyAccessToken))
    }
    deleteCookie(REFRESH_TOKEN_COOKIE)
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    return
  }

  if (canUseCookieStorage()) {
    if (!getCookie(ACCESS_TOKEN_COOKIE) && legacyAccessToken) {
      setCookie(ACCESS_TOKEN_COOKIE, legacyAccessToken, parseJwtExpirySeconds(legacyAccessToken))
    }
    if (!getCookie(REFRESH_TOKEN_COOKIE) && legacyRefreshToken) {
      setCookie(REFRESH_TOKEN_COOKIE, legacyRefreshToken, REFRESH_TOKEN_MAX_AGE)
    }
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    return
  }

  if (legacyAccessToken && !localStorage.getItem(ACCESS_TOKEN_KEY)) {
    localStorage.setItem(ACCESS_TOKEN_KEY, legacyAccessToken)
  }
  localStorage.removeItem(LEGACY_TOKEN_KEY)
}

migrateLegacyTokenStorage()

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(getStoredAccessToken())
  const refreshToken = ref<string | null>(getStoredRefreshToken())

  // Backward-compatible alias: WS composables read auth.token
  const token = computed(() => accessToken.value)

  const isLoggedIn = computed(() => !!user.value && !!accessToken.value)
  const isAdmin = computed(() => (user.value?.permission_level ?? 0) >= 3)

  // Single in-flight refresh promise shared by both axios interceptor and authFetch
  let _refreshPromise: Promise<string> | null = null

  async function doRefreshToken(): Promise<string> {
    if (_refreshPromise) return _refreshPromise
    _refreshPromise = _executeRefresh().finally(() => { _refreshPromise = null })
    return _refreshPromise
  }

  async function _executeRefresh(): Promise<string> {
    if (usesSingleJwtSession()) {
      const resp = await axios.post(`${API_BASE}/api/auth/silent-login`, null, {
        withCredentials: true
      })
      const data = resp.data
      if (data?.success && data?.authenticated && data?.access_token) {
        setToken(data.access_token)
        return data.access_token
      }
      throw new Error('Silent re-auth failed')
    }

    if (!refreshToken.value) {
      throw new Error('No refresh token')
    }
    const resp = await axios.post(`${API_BASE}/api/auth/refresh`, {
      refresh_token: refreshToken.value
    })
    const data = resp.data
    if (data.access_token) {
      setToken(data.access_token, data.refresh_token || refreshToken.value)
      return data.access_token
    }
    throw new Error('Refresh failed')
  }

  function canRecoverSession(): boolean {
    if (usesSingleJwtSession()) {
      return !isSilentLoginSuppressed()
    }
    return !!refreshToken.value
  }

  // Session recovery is internally deduped so concurrent 401s all
  // share one in-flight recovery request.
  axios.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config
      if (error.response?.status === 401 && !originalRequest._retry && canRecoverSession()) {
        originalRequest._retry = true
        try {
          const newToken = await doRefreshToken()
          originalRequest.headers['Authorization'] = `Bearer ${newToken}`
          return axios(originalRequest)
        } catch (refreshError) {
          logout()
          return Promise.reject(refreshError)
        }
      }
      return Promise.reject(error)
    }
  )

  async function verifyToken(): Promise<boolean> {
    if (!accessToken.value) return false

    try {
      const resp = await axios.get(`${API_BASE}/api/auth/me`, {
        headers: { Authorization: `Bearer ${accessToken.value}` },
      })
      if (resp.data.success && resp.data.user) {
        user.value = resp.data.user
        return true
      }
    } catch (err: unknown) {
      if (axios.isAxiosError(err) && err.response) {
        // Server explicitly rejected the token (401/403) — session is dead
        logout()
        return false
      }
      // Network error / timeout — don't nuke the session
      console.warn('[auth] verifyToken network error, keeping session:', err)
      return false
    }
    logout()
    return false
  }

  function setToken(newAccessToken: string, newRefreshToken?: string) {
    accessToken.value = newAccessToken
    setStoredTokens(newAccessToken, newRefreshToken)
    localStorage.removeItem(SILENT_SSO_LOGOUT_KEY)
    if (usesSingleJwtSession()) {
      refreshToken.value = null
    } else if (newRefreshToken) {
      refreshToken.value = newRefreshToken
    }
  }

  function canAttemptSilentLogin(): boolean {
    if (typeof window === 'undefined') return false
    if (isSilentLoginSuppressed()) return false
    const hostname = window.location.hostname
    return hostname === 'rms.net.cn' || hostname.endsWith('.rms.net.cn')
  }

  async function doSilentLogin(): Promise<boolean> {
    const resp = await axios.post(`${API_BASE}/api/auth/silent-login`, null, {
      withCredentials: true
    })
    const data = resp.data
    if (!data?.success || !data?.authenticated || !data?.access_token) {
      return false
    }
    setToken(data.access_token)
    return verifyToken()
  }

  function logout(options?: { manual?: boolean }) {
    // Revoke refresh token on server (fire-and-forget).
    // Must send Bearer header since /api/auth/logout requires auth middleware.
    if (refreshToken.value && accessToken.value) {
      axios.post(`${API_BASE}/api/auth/logout`, {
        refresh_token: refreshToken.value
      }, {
        headers: { Authorization: `Bearer ${accessToken.value}` }
      }).catch(() => {})
    }
    if (options?.manual) {
      localStorage.setItem(SILENT_SSO_LOGOUT_KEY, '1')
    }
    user.value = null
    accessToken.value = null
    refreshToken.value = null
    clearStoredTokens()
  }

  function getLoginUrl(redirectUrl?: string): string {
    localStorage.removeItem(SILENT_SSO_LOGOUT_KEY)
    const callback = encodeURIComponent(redirectUrl ?? `${window.location.origin}/callback`)
    return `${API_BASE}/api/auth/login?redirect_url=${callback}`
  }

  return {
    user,
    token,
    accessToken,
    refreshToken,
    isLoggedIn,
    isAdmin,
    verifyToken,
    canAttemptSilentLogin,
    canRecoverSession,
    doSilentLogin,
    doRefreshToken,
    setToken,
    logout,
    getLoginUrl,
  }
})
