<script setup lang="ts">
import { RouterView } from 'vue-router'
import { NConfigProvider, NMessageProvider, NDialogProvider } from 'naive-ui'
import { useGlowEffect } from '@rms-discord/shared'
import { themeOverrides } from '@rms-discord/shared/naive-ui'
import AppUpdater from '@rms-discord/shared/views/AppUpdater.vue'
import VersionBadge from '@rms-discord/shared/components/VersionBadge.vue'

useGlowEffect()
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides">
    <NMessageProvider>
      <NDialogProvider>
        <RouterView />
        <VersionBadge />
        <AppUpdater />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
