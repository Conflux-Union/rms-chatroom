<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useGlowEffect } from './composables/useGlowEffect'

useGlowEffect()
</script>

<template>
  <RouterView />
</template>
