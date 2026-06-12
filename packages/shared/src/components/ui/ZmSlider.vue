<script setup lang="ts">
defineProps<{
  value?: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
  // accepted for naive-ui call-site compatibility; zhimo-slider has no tooltip
  tooltip?: unknown
}>()

const emit = defineEmits<{ (e: 'update:value', value: number): void }>()

function onChange(e: Event) {
  emit('update:value', (e as CustomEvent<{ value: number }>).detail.value)
}
</script>

<template>
  <zhimo-slider
    :value="value"
    :min="min"
    :max="max"
    :step="step"
    :disabled="disabled || undefined"
    @change.stop="onChange"
  />
</template>
