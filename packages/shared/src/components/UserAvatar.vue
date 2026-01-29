<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  username: string
  size?: number
}>(), {
  size: 40
})

// Color palette for avatar backgrounds
const COLORS = [
  '#5865F2', // Discord blurple
  '#57F287', // Green
  '#FEE75C', // Yellow
  '#EB459E', // Pink
  '#ED4245', // Red
  '#3BA55C', // Dark green
  '#FAA61A', // Orange
  '#9B59B6', // Purple
  '#E91E63', // Material pink
  '#00BCD4', // Cyan
]

// Generate a stable hash from username
function hashString(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash = hash & hash // Convert to 32bit integer
  }
  return Math.abs(hash)
}

const backgroundColor = computed(() => {
  const hash = hashString(props.username)
  return COLORS[hash % COLORS.length]
})

const initial = computed(() => {
  return props.username.charAt(0).toUpperCase()
})

const fontSize = computed(() => {
  return Math.round(props.size * 0.45)
})
</script>

<template>
  <div
    class="user-avatar"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      backgroundColor: backgroundColor,
      fontSize: `${fontSize}px`
    }"
  >
    {{ initial }}
  </div>
</template>

<style scoped>
.user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: white;
  font-weight: 600;
  flex-shrink: 0;
  user-select: none;
}
</style>
