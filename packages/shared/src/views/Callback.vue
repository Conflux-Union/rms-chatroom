<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// Parse URL fragment (#key=value&key2=value2)
function parseFragment(hash: string): Record<string, string> {
  const params: Record<string, string> = {}
  const raw = hash.startsWith('#') ? hash.slice(1) : hash
  if (!raw) return params
  for (const pair of raw.split('&')) {
    const [key, val] = pair.split('=')
    if (key) params[decodeURIComponent(key)] = decodeURIComponent(val || '')
  }
  return params
}

onMounted(async () => {
  // Try URL fragment first (web OAuth flow)
  const fragment = parseFragment(window.location.hash)
  let accessToken = fragment['access_token']
  let refreshToken = fragment['refresh_token']

  // Fall back to query string (native/legacy)
  if (!accessToken) {
    accessToken = route.query.access_token as string || route.query.token as string
    refreshToken = route.query.refresh_token as string
  }

  if (accessToken) {
    auth.setToken(accessToken, refreshToken || undefined)
    const valid = await auth.verifyToken()
    if (valid) {
      router.push('/')
      return
    }
  }

  router.push('/login')
})
</script>

<template>
  <div class="callback-container">
    <div class="loading">
      <div class="spinner"></div>
      <p>Processing login...</p>
    </div>
  </div>
</template>

<style scoped>
.callback-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  min-height: 100dvh;
  background-color: #36393f;
}

.loading {
  text-align: center;
  color: #fff;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid #5865f2;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 16px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
