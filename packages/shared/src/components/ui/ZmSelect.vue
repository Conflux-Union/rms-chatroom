<script setup lang="ts">
export interface ZmSelectOption {
  label: string
  value: string | number
  disabled?: boolean
}

const props = defineProps<{
  value?: string | number | null
  options: ZmSelectOption[]
  placeholder?: string
  label?: string
  disabled?: boolean
  size?: string
}>()

const emit = defineEmits<{ (e: 'update:value', value: string | number): void }>()

function onChange(e: Event) {
  const raw = (e as CustomEvent<{ value: string }>).detail.value
  // option values can be numbers; attributes stringify them
  const match = props.options.find((o) => String(o.value) === raw)
  emit('update:value', match ? match.value : raw)
}
</script>

<template>
  <zhimo-select
    :value="value != null ? String(value) : undefined"
    :placeholder="placeholder"
    :label="label"
    :disabled="disabled || undefined"
    @change.stop="onChange"
  >
    <zhimo-option
      v-for="opt in options"
      :key="opt.value"
      :value="String(opt.value)"
      :disabled="opt.disabled || undefined"
    >
      {{ opt.label }}
    </zhimo-option>
  </zhimo-select>
</template>
