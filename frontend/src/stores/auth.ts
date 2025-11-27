import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8000'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem('token'))

  const isLoggedIn = computed(() => !!user.value && !!token.value)
  const isAdmin = computed(() => (user.value?.permission_level ?? 0) >= 5)

  async function verifyToken(): Promise<boolean> {
    if (!token.value) return false

    try {
      const resp = await axios.get(`${API_BASE}/api/auth/me`, {
        headers: { Authorization: `Bearer ${token.value}` },
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

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function logout() {
    user.value = null
    token.value = null
    localStorage.removeItem('token')
  }

  function getLoginUrl(): string {
    const callback = encodeURIComponent(`${window.location.origin}/callback`)
    return `${API_BASE}/api/auth/login?redirect_url=${callback}`
  }

  return {
    user,
    token,
    isLoggedIn,
    isAdmin,
    verifyToken,
    setToken,
    logout,
    getLoginUrl,
  }
})
