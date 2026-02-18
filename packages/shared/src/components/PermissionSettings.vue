<template>
  <div class="permission-settings">
    <div class="permission-section">
      <h3>{{ title }}</h3>
      <div class="permission-controls">
        <div class="permission-group">
          <label>服务器权限等级</label>
          <div class="level-selector">
            <button
              v-for="level in serverLevels"
              :key="level"
              class="level-btn"
              :class="{ active: modelValue.minServerLevel === level }"
              @click.stop="updateServerLevel(level)"
              :title="`等级 ${level}`"
            >
              {{ getLevelLabel(level) }}
            </button>
          </div>
          <p class="description">用户服务器权限必须达到此等级才能访问</p>
        </div>

        <div class="permission-group">
          <label>内部/外部权限</label>
          <div class="level-selector">
            <button
              v-for="level in internalLevels"
              :key="level"
              class="level-btn"
              :class="{ active: modelValue.minInternalLevel === level }"
              @click.stop="updateInternalLevel(level)"
              :title="`等级 ${level}`"
            >
              {{ getInternalLabel(level) }}
            </button>
          </div>
          <p class="description">用户内部/外部权限必须达到此等级才能访问 (1=外服, 2=内服)</p>
        </div>

        <div v-if="showSpeakPermissions" class="permission-group">
          <label>发言权限 (仅限频道)</label>
          <div class="level-selector">
            <button
              v-for="level in serverLevels"
              :key="'speak-' + level"
              class="level-btn"
              :class="{ active: modelValue.speakMinServerLevel === level }"
              @click.stop="updateSpeakServerLevel(level)"
              :title="`发言权限等级 ${level}`"
            >
              {{ getLevelLabel(level) }}
            </button>
          </div>
          <p class="description">用户发言所需的最低权限等级</p>
        </div>

        <div v-if="showSpeakPermissions" class="permission-group">
          <label>发言内部/外部权限 (仅限频道)</label>
          <div class="level-selector">
            <button
              v-for="level in internalLevels"
              :key="'speak-internal-' + level"
              class="level-btn"
              :class="{ active: modelValue.speakMinInternalLevel === level }"
              @click.stop="updateSpeakInternalLevel(level)"
              :title="`发言权限等级 ${level}`"
            >
              {{ getInternalLabel(level) }}
            </button>
          </div>
          <p class="description">用户发言所需的最低内部/外部权限</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface PermissionModel {
  minServerLevel: number
  minInternalLevel: number
  speakMinServerLevel?: number
  speakMinInternalLevel?: number
}

interface Props {
  modelValue: PermissionModel
  title?: string
  showSpeakPermissions?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '权限设置',
  showSpeakPermissions: false
})

const emit = defineEmits<{
  'update:modelValue': [value: PermissionModel]
}>()

const serverLevels = [1, 2, 3, 4]
const internalLevels = [1, 2]

const getLevelLabel = (level: number): string => {
  const labels: { [key: number]: string } = {
    1: '等级1',
    2: '等级2',
    3: '等级3',
    4: '等级4'
  }
  return labels[level] || '未知'
}

const getInternalLabel = (level: number): string => {
  const labels: { [key: number]: string } = {
    1: '外服',
    2: '内服'
  }
  return labels[level] || '未知'
}

const updateServerLevel = (level: number) => {
  emit('update:modelValue', {
    ...props.modelValue,
    minServerLevel: level
  })
}

const updateInternalLevel = (level: number) => {
  emit('update:modelValue', {
    ...props.modelValue,
    minInternalLevel: level
  })
}

const updateSpeakServerLevel = (level: number) => {
  emit('update:modelValue', {
    ...props.modelValue,
    speakMinServerLevel: level
  })
}

const updateSpeakInternalLevel = (level: number) => {
  emit('update:modelValue', {
    ...props.modelValue,
    speakMinInternalLevel: level
  })
}
</script>

<style scoped>
.permission-settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.permission-section {
  background: rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  padding: 16px;
  border: 1px solid var(--color-border);
}

.permission-section h3 {
  margin: 0 0 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-main);
}

.permission-controls {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.permission-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.permission-group label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-main);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.level-selector {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.level-btn {
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.08);
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  backdrop-filter: blur(5px);
}

.level-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--color-text-main);
  border-color: var(--color-accent);
}

.level-btn.active {
  background: var(--color-accent);
  color: white;
  border-color: var(--color-accent);
}

.description {
  font-size: 11px;
  color: var(--color-text-muted);
  margin: 0;
  line-height: 1.4;
}
</style>
