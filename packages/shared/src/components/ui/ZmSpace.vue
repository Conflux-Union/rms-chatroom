<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  vertical?: boolean
  justify?: 'start' | 'end' | 'center' | 'space-between' | 'space-around'
  align?: string
  size?: 'small' | 'medium' | 'large' | number | string
  wrap?: boolean
}>()

const gap = computed(() => {
  if (typeof props.size === 'number') return `${props.size}px`
  if (props.size === 'small') return '8px'
  if (props.size === 'large') return '16px'
  if (typeof props.size === 'string' && /^\d+$/.test(props.size)) return `${props.size}px`
  return '12px'
})

const justifyContent = computed(() => {
  if (props.justify === 'start') return 'flex-start'
  if (props.justify === 'end') return 'flex-end'
  return props.justify
})
</script>

<template>
  <div
    class="zm-space"
    :style="{
      flexDirection: vertical ? 'column' : 'row',
      justifyContent,
      alignItems: align,
      gap,
      flexWrap: wrap === false ? 'nowrap' : 'wrap',
    }"
  >
    <slot />
  </div>
</template>

<style scoped>
.zm-space {
  display: flex;
}
</style>
