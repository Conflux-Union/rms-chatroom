<script setup lang="ts">
defineProps<{ description?: string }>()
</script>

<template>
  <div class="zm-empty">
    <span class="zm-empty-mark">◆</span>
    <p>{{ description ?? '暂无数据' }}</p>
    <slot />
  </div>
</template>

<style scoped>
.zm-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: var(--zhimo-fg-muted);
  font-size: 13px;
}
.zm-empty-mark {
  color: var(--zhimo-border-strong);
  font-size: 18px;
}
</style>
