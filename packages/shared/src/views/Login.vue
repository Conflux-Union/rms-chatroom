<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()
const isTryingSilentLogin = ref(false)

// Electron 环境：监听主进程回调（token/code），然后交给你们已有的 Callback 页面处理
const electronAPI = (window as any).electronAPI

if (electronAPI?.onAuthCallback) {
  electronAPI.onAuthCallback((data: any) => {
    const { token, code } = data || {}
    if (token) {
      router.replace({ path: '/callback', query: { token } })
    } else if (code) {
      router.replace({ path: '/callback', query: { code } })
    }
  })
}

async function handleLogin() {
  const loginUrl = auth.getLoginUrl()

  // ✅ Electron：走系统浏览器，不要 window.location 跳走主窗口
  if (electronAPI?.getCallbackUrl && electronAPI?.openExternal) {
    const cb = await electronAPI.getCallbackUrl() // 形如 http://127.0.0.1:53333/callback
    if (!cb) {
      console.error('callbackUrl 为空，无法走浏览器 SSO')
      return
    }

    // 把 loginUrl 里的 redirect_url 换成 cb
    const u = new URL(loginUrl)
    if (u.searchParams.has('redirect_url')) {
      u.searchParams.set('redirect_url', cb)
    }

    await electronAPI.openExternal(u.toString())
    return
  }

  // ✅ 网页端：保持原逻辑
  window.location.href = loginUrl
}

onMounted(async () => {
  if (electronAPI?.getCallbackUrl || !auth.canAttemptSilentLogin()) {
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
