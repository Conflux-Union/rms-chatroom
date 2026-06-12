<script setup lang="ts">
import { computed, useSlots } from 'vue'

const props = defineProps<{
  show?: boolean
  size?: 'small' | 'medium' | 'large'
}>()

const slots = useSlots()
const standalone = computed(() => !slots.default)
const zmSize = computed(() => {
  if (props.size === 'small') return 'sm'
  if (props.size === 'large') return 'lg'
  return undefined
})
</script>

<template>
  <zhimo-spinner v-if="standalone" :size="zmSize" />
  <div v-else class="zm-spin">
    <slot />
    <div v-if="show" class="zm-spin-overlay">
      <zhimo-spinner :size="zmSize" />
    </div>
  </div>
</template>

<style scoped>
.zm-spin {
  position: relative;
}
.zm-spin-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--zhimo-bg) 60%, transparent);
}
</style>
