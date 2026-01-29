import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const legacyToken = localStorage.getItem('token')
  const initialAccessToken = localStorage.getItem('access_token') || legacyToken
  const accessToken = ref<string | null>(initialAccessToken)
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'))

  // Migrate legacy token -> access_token to avoid forcing logout on upgrade.
  if (!localStorage.getItem('access_token') && legacyToken) {
    localStorage.setItem('access_token', legacyToken)
    localStorage.removeItem('token')
  }

  // Backward compatibility: expose token as computed property
  const token = computed(() => accessToken.value)

  const isLoggedIn = computed(() => !!user.value && !!accessToken.value)
  const isAdmin = computed(() => (user.value?.permission_level ?? 0) >= 3)

  // Set axios default Authorization header when access token changes
  function updateAxiosHeader() {
    if (accessToken.value) {
      axios.defaults.headers.common['Authorization'] = `Bearer ${accessToken.value}`
    } else {
      delete axios.defaults.headers.common['Authorization']
    }
  }

  // Initialize axios header on store creation
  updateAxiosHeader()

  /**
   * Attempt to refresh the access token using the refresh token.
   * Returns true if successful, false otherwise.
   */
  async function doRefreshToken(): Promise<boolean> {
    if (!refreshToken.value) return false

    try {
      const resp = await axios.post(`${API_BASE}/api/auth/refresh`, {
        refresh_token: refreshToken.value,
      })

      if (resp.data.access_token) {
        accessToken.value = resp.data.access_token
        localStorage.setItem('access_token', resp.data.access_token)

        // Update refresh token if a new one is provided
        if (resp.data.refresh_token) {
          refreshToken.value = resp.data.refresh_token
          localStorage.setItem('refresh_token', resp.data.refresh_token)
        }

        updateAxiosHeader()
        return true
      }
    } catch {
      // Refresh failed, clear tokens
    }
    return false
  }

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
    } catch (e: any) {
      // On 401, attempt to refresh token
      if (e.response?.status === 401 && refreshToken.value) {
        const refreshed = await doRefreshToken()
        if (refreshed) {
          // Retry verification with new token
          try {
            const retryResp = await axios.get(`${API_BASE}/api/auth/me`, {
              headers: { Authorization: `Bearer ${accessToken.value}` },
            })
            if (retryResp.data.success && retryResp.data.user) {
              user.value = retryResp.data.user
              return true
            }
          } catch {
            // Retry failed
          }
        }
      }
    }
    logout()
    return false
  }

  function setTokens(newAccessToken: string, newRefreshToken: string) {
    accessToken.value = newAccessToken
    refreshToken.value = newRefreshToken
    localStorage.setItem('access_token', newAccessToken)
    localStorage.setItem('refresh_token', newRefreshToken)
    updateAxiosHeader()
  }

  // Legacy method for backward compatibility
  function setToken(newToken: string) {
    setTokens(newToken, refreshToken.value || '')
  }

  async function logout() {
    // Attempt to revoke refresh token on server
    if (refreshToken.value) {
      try {
        await axios.post(`${API_BASE}/api/auth/revoke`, {
          refresh_token: refreshToken.value,
        })
      } catch {
        // Ignore revoke errors
      }
    }

    user.value = null
    accessToken.value = null
    refreshToken.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    // Clean up legacy token if exists
    localStorage.removeItem('token')
    updateAxiosHeader()
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
    setTokens,
    logout,
    getLoginUrl,
  }
})
