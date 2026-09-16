<script setup lang="ts">
import { computed } from 'vue'
import type { Message } from '../types'
import grassBlockUrl from '../assets/grass_block_icon.png'

const props = defineProps<{ msg: Message }>()

// Forwarded-message source: 'qq' is the QQ sync bot, anything else is a game
// server arriving over ChatBridge (Minecraft). QQ shows the official penguin
// mark only; game messages pair the grass block icon with the origin server
// name.
const isQQ = computed(() => props.msg.source_platform === 'qq')
const serverLabel = computed(() => props.msg.forward_meta?.server || '服务器')
</script>

<template>
  <span class="source-badge" :title="isQQ ? 'QQ' : serverLabel">
    <svg v-if="isQQ" class="source-icon" viewBox="0 0 24 24" role="img" aria-label="QQ">
      <!-- Official Tencent QQ mark (simple-icons/qq path data) -->
      <path
        d="M21.395 15.035a40 40 0 0 0-.803-2.264l-1.079-2.695c.001-.032.014-.562.014-.836C19.526 4.632 17.351 0 12 0S4.474 4.632 4.474 9.241c0 .274.013.804.014.836l-1.08 2.695a39 39 0 0 0-.802 2.264c-1.021 3.283-.69 4.643-.438 4.673.54.065 2.103-2.472 2.103-2.472 0 1.469.756 3.387 2.394 4.771-.612.188-1.363.479-1.845.835-.434.32-.379.646-.301.778.343.578 5.883.369 7.482.189 1.6.18 7.14.389 7.483-.189.078-.132.132-.458-.301-.778-.483-.356-1.233-.646-1.846-.836 1.637-1.384 2.393-3.302 2.393-4.771 0 0 1.563 2.537 2.103 2.472.251-.03.581-1.39-.438-4.673"
      />
    </svg>
    <!-- Official grass block render (Minecraft Wiki, game textures) -->
    <img v-else class="source-icon" :src="grassBlockUrl" alt="" />
    <span v-if="!isQQ" class="source-label">{{ serverLabel }}</span>
  </span>
</template>

<style scoped>
/* Source badge next to forwarded message authors (FORWARD channels) */
.source-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 10px;
  line-height: 14px;
  color: var(--color-text-muted);
}

.source-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}

.source-icon path {
  fill: currentColor;
}
</style>
