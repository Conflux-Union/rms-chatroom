<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppUpdater from '@rms-discord/shared/views/AppUpdater.vue'
import inkWashBg from '../../shared/public/bg.webp?url'
</script>

<template>
  <zhimo-ink-paper class="ink-bg" :image="inkWashBg" />
  <RouterView />
  <AppUpdater />
</template>

<style>
.ink-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
}

#app > *:not(.ink-bg) {
  position: relative;
  z-index: 1;
}
</style>
