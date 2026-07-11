<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'
import { isTauri } from '../index'

const auth = useAuthStore()
const router = useRouter()
const isTryingSilentLogin = ref(false)

// Tauri desktop: listen for OAuth callback from Rust side
let unlisten: (() => void) | null = null

if (isTauri) {
  import('@tauri-apps/api/event').then(({ listen }) => {
    listen('auth-callback', (event) => {
      const { access_token, refresh_token, token, code } = event.payload as any || {}
      if (access_token || token) {
        router.replace({ path: '/callback', query: { access_token: access_token || token, refresh_token: refresh_token || undefined } })
      } else if (code) {
        router.replace({ path: '/callback', query: { code } })
      }
    }).then((fn) => { unlisten = fn })
  })
}

onBeforeUnmount(() => {
  if (unlisten) unlisten()
})

async function handleLogin() {
  const loginUrl = auth.getLoginUrl()

  // Tauri desktop: open SSO in system browser, use local callback server
  if (isTauri) {
    const { invoke } = await import('@tauri-apps/api/core')
    const { open } = await import('@tauri-apps/plugin-shell')
    const cb = await invoke('get_callback_url') as string
    if (!cb) {
      console.error('callbackUrl is empty, cannot launch browser SSO')
      return
    }

    const u = new URL(loginUrl, window.location.origin)
    if (u.searchParams.has('redirect_url')) {
      u.searchParams.set('redirect_url', cb)
    }

    await open(u.toString())
    return
  }

  // Web: navigate directly
  window.location.href = loginUrl
}

onMounted(async () => {
  if (isTauri || !auth.canAttemptSilentLogin()) {
    return
  }

  isTryingSilentLogin.value = true
  try {
    const ok = await auth.doSilentLogin()
    if (ok) {
      router.replace('/')
    }
  } catch (err) {
    console.warn('[auth] silent login failed:', err)
  } finally {
    isTryingSilentLogin.value = false
  }
})
</script>

<template>
  <div class="login-shell">
    <zhimo-card class="login-card">
      <div class="login-content">
        <h1 class="title">RMS ChatRoom</h1>
        <p class="subtitle">{{ isTryingSilentLogin ? '正在尝试无感登录...' : '欢迎！请使用 RMS 账号登录' }}</p>
        <zhimo-button
          size="lg"
          block
          :loading="isTryingSilentLogin || undefined"
          @click="handleLogin"
        >
          {{ isTryingSilentLogin ? '请稍候...' : 'RMS 账号登录' }}
        </zhimo-button>
      </div>
    </zhimo-card>
  </div>
</template>

<style scoped>
/* A full page, not a modal: just center a paper card on the global
   <zhimo-ink-paper> background painted in App.vue. No dimming backdrop. */
.login-shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: var(--spacing-xl);
}

.login-card {
  width: 100%;
  max-width: 420px;
  box-shadow: var(--zhimo-shadow-md);
}

.login-content {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  padding: var(--spacing-md) var(--spacing-sm);
  text-align: center;
}

.title {
  color: var(--zhimo-fg);
  font-family: var(--zhimo-font-serif);
  font-size: 2rem;
  font-weight: 700;
  margin: 0;
}

.subtitle {
  color: var(--zhimo-fg-muted);
  font-size: 1rem;
  margin: 0;
}

@media (max-width: 600px) {
  .login-shell {
    padding: var(--spacing-md);
  }
}
</style>
