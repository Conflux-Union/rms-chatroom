<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ZmSpin } from '../components/ui'

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
  <div class="callback-shell">
    <div class="loading">
      <ZmSpin size="large" />
      <p class="hint">正在登录，请稍候...</p>
    </div>
  </div>
</template>

<style scoped>
/* Same shape as Login.vue: transparent shell centered on the global
   <zhimo-ink-paper> background painted in App.vue. */
.callback-shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: var(--spacing-xl);
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-lg);
}

.hint {
  color: var(--zhimo-fg-muted);
}
</style>
