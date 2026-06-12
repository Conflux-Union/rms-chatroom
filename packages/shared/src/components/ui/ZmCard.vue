<script setup lang="ts">
import { useSlots } from 'vue'

defineProps<{
  title?: string
  hoverable?: boolean
}>()

const slots = useSlots()
</script>

<template>
  <zhimo-card :hoverable="hoverable || undefined">
    <span v-if="title || slots.header" slot="header"><slot name="header">{{ title }}</slot></span>
    <slot />
    <span v-if="slots.footer" slot="footer"><slot name="footer" /></span>
  </zhimo-card>
</template>
