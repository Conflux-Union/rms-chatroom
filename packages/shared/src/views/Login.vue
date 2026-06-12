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
  <div class="page-shell">
    <div class="page-surface">
      <div class="page-surface__inner">
        <div class="page-content">
          <h1 class="title">RMS ChatRoom</h1>
          <p class="subtitle">{{ isTryingSilentLogin ? '正在尝试无感登录...' : '欢迎！请使用 RMS 账号登录' }}</p>
          <button class="btn " :disabled="isTryingSilentLogin" @click="handleLogin">
            {{ isTryingSilentLogin ? '请稍候...' : 'RMS 账号登录' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-shell {
  min-height: 100vh;
  min-height: 100dvh;
  width: 100%;
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
  padding-left: clamp(2rem, 10vw, 20rem);
}

.page-surface {
  min-height: 100vh;
  min-height: 100dvh;
  width: auto;
  min-width: 480px;
  max-width: 100%;
  flex: 0 0 auto;
  padding: var(--spacing-xxl) var(--spacing-xl);
  background: var(--surface-glass);
  border-left: 1px solid var(--zhimo-border-strong);
  box-shadow: none;
  display: flex;
  justify-content: center;
  align-items: center;
  transition: width 0.35s var(--transition-normal), padding 0.3s var(--transition-normal);
}

.page-surface__inner {
  width: 100%;
  max-width: 440px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.page-content {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xl);
}

.title {
  color: var(--color-text-main);
  text-align: center;
  font-size: 2.25rem;
  font-weight: 700;
  font-family: var(--zhimo-font-serif);
  letter-spacing: 0;
  margin: 0;
}

.subtitle {
  color: var(--color-text-muted);
  text-align: center;
  font-size: 1.1rem;
  margin: 0;
}

.btn {
  width: 100%;
  padding: 16px;
  border: 1px solid var(--zhimo-seal-hover);
  border-radius: var(--zhimo-radius);
  background: var(--zhimo-seal);
  color: var(--zhimo-accent-fg);
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), transform var(--transition-fast);
  box-shadow: none;
}

.btn:hover {
  transform: translateY(-1px);
  background: var(--zhimo-seal-hover);
}

.btn:active {
  transform: translateY(0) scale(0.98);
}

.btn:disabled {
  cursor: wait;
  opacity: 0.8;
  transform: none;
}

@media (max-width: 960px) {
  .page-shell {
    padding-left: 0;
    justify-content: center;
  }
  .page-surface {
    width: 100%;
    min-width: 100%;
    padding: var(--spacing-xl) var(--spacing-md);
    background: var(--surface-glass-strong);
  }
}

@media (max-width: 600px) {
  .page-surface {
    align-items: flex-start;
    padding-top: 40px;
  }
}
</style>
