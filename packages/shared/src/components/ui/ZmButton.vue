<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    type?: 'primary' | 'default' | 'error' | 'warning' | 'success' | 'info'
    size?: 'tiny' | 'small' | 'medium' | 'large'
    secondary?: boolean
    quaternary?: boolean
    ghost?: boolean
    text?: boolean
    block?: boolean
    loading?: boolean
    disabled?: boolean
  }>(),
  { type: 'default', size: 'medium' }
)

// naive-ui button types collapse onto zhimo's three variants:
// solid ink (primary), outlined (default), danger; text-like become ghost
const variant = computed(() => {
  if (props.text || props.quaternary || props.ghost) return 'ghost'
  if (props.type === 'error' || props.type === 'warning') return 'danger'
  if (props.type === 'primary') return undefined
  return 'secondary'
})

const zmSize = computed(() => {
  if (props.size === 'tiny' || props.size === 'small') return 'sm'
  if (props.size === 'large') return 'lg'
  return undefined
})
</script>

<template>
  <zhimo-button
    :variant="variant"
    :size="zmSize"
    :block="block || undefined"
    :loading="loading || undefined"
    :disabled="disabled || undefined"
  >
    <slot />
  </zhimo-button>
</template>
