<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useGlowEffect } from '@rms-discord/shared'
import VersionBadge from '@rms-discord/shared/components/VersionBadge.vue'

useGlowEffect()
</script>

<template>
  <RouterView />
  <VersionBadge />
</template>
