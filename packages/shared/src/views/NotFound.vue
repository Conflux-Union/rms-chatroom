<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Compass } from 'lucide-vue-next'
import { t } from '../i18n'

const router = useRouter()
</script>

<template>
  <div class="notfound-shell">
    <div class="notfound-card">
      <Compass class="notfound-icon" :size="52" />
      <h1 class="code">404</h1>
      <p class="title">{{ t('app.pageNotFound') }}</p>
      <p class="hint">{{ t('app.notFoundHint') }}</p>
      <button class="home-btn" @click="router.push('/')">{{ t('app.backToHome') }}</button>
    </div>
  </div>
</template>

<style scoped>
/* Standalone page: transparent shell centered on the global <zhimo-ink-paper>
   background painted in App.vue. */
.notfound-shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: var(--spacing-md);
}

.notfound-card {
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  text-align: center;
  padding: var(--spacing-xl) var(--spacing-lg);
  background: var(--zhimo-surface-bg);
  border: 1px solid var(--zhimo-border-strong);
  border-radius: var(--zhimo-radius);
  box-shadow: var(--zhimo-shadow-sm);
}

.notfound-icon {
  color: var(--color-text-muted);
  margin-bottom: var(--spacing-xs);
}

.code {
  margin: 0;
  font-size: 3rem;
  font-weight: 700;
  line-height: 1;
  color: var(--color-text-main);
  letter-spacing: 0.05em;
}

.title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--color-text-main);
}

.hint {
  margin: 0;
  font-size: 0.85rem;
  color: var(--zhimo-fg-muted);
}

.home-btn {
  margin-top: var(--spacing-md);
  padding: 10px 28px;
  border: 1px solid var(--zhimo-accent);
  border-radius: var(--radius-md);
  background: var(--zhimo-accent);
  color: var(--zhimo-accent-fg);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.home-btn:hover {
  background: var(--zhimo-accent-hover, var(--zhimo-accent));
  border-color: var(--zhimo-accent-hover, var(--zhimo-accent));
}
</style>
