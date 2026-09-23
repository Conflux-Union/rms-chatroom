<template>
  <ZmModal
    :show="show"
    :title="t('app.whatsNew', { version: changelog.version })"
    style="width: 440px; max-width: 90vw"
    @update:show="emit('close')"
  >
    <div class="changelog-body">
      <template v-if="changelog.improvements.length">
        <div class="changelog-section">{{ t('app.changelogNew') }}</div>
        <ul class="changelog-list">
          <li v-for="(entry, i) in changelog.improvements" :key="`i-${i}`">{{ pick(entry) }}</li>
        </ul>
      </template>
      <template v-if="changelog.fixes.length">
        <div class="changelog-section">{{ t('app.changelogFixes') }}</div>
        <ul class="changelog-list">
          <li v-for="(entry, i) in changelog.fixes" :key="`f-${i}`">{{ pick(entry) }}</li>
        </ul>
      </template>
    </div>
    <template #action>
      <ZmButton @click="emit('close')">{{ t('common.ok') }}</ZmButton>
    </template>
  </ZmModal>
</template>

<script setup lang="ts">
import { t, locale } from '../i18n'
import type { ChangelogEntry, ReleaseChangelog } from '../changelog'
import { ZmModal, ZmButton } from './ui'

defineProps<{
  show: boolean
  changelog: ReleaseChangelog
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

// Entries ship both languages; show the active one with the other as-is
// when a translation is missing.
function pick(entry: ChangelogEntry): string {
  return locale.value === 'zh' ? (entry.zh || entry.en) : (entry.en || entry.zh)
}
</script>

<style scoped>
.changelog-body {
  max-height: 50vh;
  overflow-y: auto;
  font-size: 14px;
  line-height: 1.6;
}
.changelog-section {
  font-weight: 600;
  margin: 8px 0 4px;
}
.changelog-section:first-child {
  margin-top: 0;
}
.changelog-list {
  margin: 0;
  padding-left: 18px;
}
.changelog-list li + li {
  margin-top: 2px;
}
</style>
