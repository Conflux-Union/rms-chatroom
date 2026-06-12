<script setup lang="ts">
defineProps<{
  percentage?: number
  // accepted for naive-ui call-site compatibility
  type?: string
  showIndicator?: boolean
  status?: string
}>()
</script>

<template>
  <zhimo-progress :value="percentage ?? 0" />
</template>
