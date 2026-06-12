<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  value?: string | null
  type?: string
  placeholder?: string
  rows?: number | string
  label?: string
  error?: string
  disabled?: boolean
  size?: string
}>()

const emit = defineEmits<{ (e: 'update:value', value: string): void }>()

const el = ref<HTMLElement & { value: string; focus: () => void }>()

function onInput(e: Event) {
  emit('update:value', (e.target as HTMLInputElement).value)
}

defineExpose({ focus: () => el.value?.focus() })
</script>

<template>
  <zhimo-input
    ref="el"
    :value="value ?? ''"
    :type="type"
    :rows="rows"
    :label="label"
    :error="error"
    :placeholder="placeholder"
    :disabled="disabled || undefined"
    @input="onInput"
  />
</template>
