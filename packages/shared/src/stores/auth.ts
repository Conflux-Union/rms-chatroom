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

  // Refresh lock to prevent concurrent refresh attempts
  let isRefreshing = false
  let refreshQueue: Array<{ resolve: (token: string) => void; reject: (err: unknown) => void }> = []

  function processQueue(error: unknown, newToken: string | null) {
    for (const p of refreshQueue) {
      if (error) {
        p.reject(error)
      } else {
        p.resolve(newToken!)
      }
    }
    refreshQueue = []
  }

  async function doRefreshToken(): Promise<string> {
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

  // Axios 401 interceptor with request queue
  axios.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config
      if (error.response?.status === 401 && !originalRequest._retry && refreshToken.value) {
        if (isRefreshing) {
          // Queue this request until refresh completes
          return new Promise((resolve, reject) => {
            refreshQueue.push({
              resolve: (newToken: string) => {
                originalRequest.headers['Authorization'] = `Bearer ${newToken}`
                resolve(axios(originalRequest))
              },
              reject
            })
          })
        }

        originalRequest._retry = true
        isRefreshing = true

        try {
          const newToken = await doRefreshToken()
          processQueue(null, newToken)
          originalRequest.headers['Authorization'] = `Bearer ${newToken}`
          return axios(originalRequest)
        } catch (refreshError) {
          processQueue(refreshError, null)
          logout()
          return Promise.reject(refreshError)
        } finally {
          isRefreshing = false
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
    } catch {
      // Token invalid
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
    // Revoke refresh token on server (fire-and-forget)
    if (refreshToken.value) {
      axios.post(`${API_BASE}/api/auth/logout`, {
        refresh_token: refreshToken.value
      }).catch(() => {})
    }
    user.value = null
    accessToken.value = null
    refreshToken.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem('token') // Clean up legacy key
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
    setToken,
    logout,
    getLoginUrl,
  }
})
