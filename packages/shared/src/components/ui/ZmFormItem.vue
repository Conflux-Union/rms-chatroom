<script setup lang="ts">
defineProps<{ label?: string }>()
</script>

<template>
  <div class="zm-form-item">
    <label v-if="label" class="zm-form-label">{{ label }}</label>
    <slot />
  </div>
</template>

<style scoped>
.zm-form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.zm-form-label {
  font-family: var(--zhimo-font-serif);
  font-size: 13px;
  font-weight: 600;
  color: var(--zhimo-fg);
}
</style>
