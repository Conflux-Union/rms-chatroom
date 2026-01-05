<script setup lang="ts">
import { VERSION_NAME, VERSION_CODE, COMMIT_HASH } from '../version'

const versionText = `v${VERSION_NAME}(${VERSION_CODE})(commit:${COMMIT_HASH})`
</script>

<template>
  <div class="version-badge">
    {{ versionText }}
  </div>
</template>

<style scoped>
.version-badge {
  position: fixed;
  bottom: 12px;
  right: 12px;
  padding: 4px 12px;
  font-size: 11px;
  font-weight: 500;
  color: var(--color-text-muted);
  background: var(--surface-glass);
  backdrop-filter: blur(var(--blur-strength));
  -webkit-backdrop-filter: blur(var(--blur-strength));
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--radius-pill);
  z-index: 50;
  opacity: 0.7;
  transition: opacity var(--transition-fast);
  user-select: none;
  pointer-events: none;
}

.version-badge:hover {
  opacity: 1;
}

@media (max-width: 768px) {
  .version-badge {
    bottom: 8px;
    right: 8px;
    font-size: 10px;
    padding: 3px 10px;
  }
}
</style>
