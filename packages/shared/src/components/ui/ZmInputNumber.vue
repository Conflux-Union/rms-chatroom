<script setup lang="ts">
defineProps<{
  value?: number | null
  min?: number
  max?: number
  step?: number
  placeholder?: string
  disabled?: boolean
  size?: string
}>()

const emit = defineEmits<{ (e: 'update:value', value: number | null): void }>()

function onInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  emit('update:value', raw === '' ? null : Number(raw))
}
</script>

<template>
  <zhimo-input
    type="number"
    :value="value ?? ''"
    :min="min"
    :max="max"
    :step="step"
    :placeholder="placeholder"
    :disabled="disabled || undefined"
    @input="onInput"
  />
</template>
