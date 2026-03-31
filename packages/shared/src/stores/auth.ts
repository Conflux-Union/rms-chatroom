import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''

// Migrate legacy 'token' key to 'access_token'
if (localStorage.getItem('token') && !localStorage.getItem('access_token')) {
  localStorage.setItem('access_token', localStorage.getItem('token')!)
  localStorage.removeItem('token')
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('access_token'))
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'))

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

  // Axios 401 interceptor — doRefreshToken is internally deduped so
  // concurrent 401s all share one in-flight refresh request.
  axios.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config
      if (error.response?.status === 401 && !originalRequest._retry && refreshToken.value) {
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
    localStorage.setItem('access_token', newAccessToken)
    if (newRefreshToken) {
      refreshToken.value = newRefreshToken
      localStorage.setItem('refresh_token', newRefreshToken)
    }
  }

  function logout() {
    // Revoke refresh token on server (fire-and-forget).
    // Must send Bearer header since /api/auth/logout requires auth middleware.
    if (refreshToken.value && accessToken.value) {
      axios.post(`${API_BASE}/api/auth/logout`, {
        refresh_token: refreshToken.value
      }, {
        headers: { Authorization: `Bearer ${accessToken.value}` }
      }).catch(() => {})
    }
    user.value = null
    accessToken.value = null
    refreshToken.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem('token')
  }

  function getLoginUrl(redirectUrl?: string): string {
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
    doRefreshToken,
    setToken,
    logout,
    getLoginUrl,
  }
})
