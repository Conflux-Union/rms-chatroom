<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

function handleLogin() {
  window.location.href = auth.getLoginUrl()
}
</script>

<template>
  <div class="page-shell">
    <div class="page-surface">
      <div class="page-surface__inner">
        <div class="page-content">
          <h1 class="title">RMS Discord</h1>
          <p class="subtitle">Welcome! Please login with your RMS account.</p>
          <button class="btn glow-effect" @click="handleLogin">Login with RMS SSO</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-shell {
  min-height: 100vh;
  width: 100%;
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
  padding-left: clamp(2rem, 10vw, 20rem);
}

.page-surface {
  min-height: 100vh;
  width: auto;
  min-width: 480px;
  max-width: 100%;
  flex: 0 0 auto;
  padding: var(--spacing-xxl) var(--spacing-xl);
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  box-shadow: -5px 0 30px rgba(0, 0, 0, 0.05);
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
  letter-spacing: -0.5px;
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
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-gradient-primary);
  color: white;
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-normal);
  box-shadow: var(--shadow-glow);
}

.btn:hover {
  transform: translateY(-2px);
  filter: brightness(1.1);
  box-shadow: 0 12px 28px rgba(220, 38, 127, 0.35);
}

.btn:active {
  transform: translateY(0) scale(0.98);
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
