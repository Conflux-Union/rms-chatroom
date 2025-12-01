<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useGlowEffect } from './composables/useGlowEffect'
import VersionBadge from './components/VersionBadge.vue'

useGlowEffect()
</script>

<template>
  <RouterView />
  <VersionBadge />
</template>
