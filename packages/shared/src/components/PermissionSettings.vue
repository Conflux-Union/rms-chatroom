<template>
  <div class="permission-settings">
    <div class="permission-section">
      <h3>{{ title }}</h3>
      <div class="permission-controls">
        <div class="permission-group">
          <label>Permission Level</label>
          <div class="level-selector">
            <button
              v-for="level in levels"
              :key="level"
              class="level-btn"
              :class="{ active: modelValue.minLevel === level }"
              @click="updateLevel(level)"
              :title="`Level ${level}`"
            >
              {{ getLevelLabel(level) }}
            </button>
          </div>
          <p class="description">Minimum permission level required for access</p>
        </div>

        <div v-if="showSpeakPermissions" class="permission-group">
          <label>Speak Permission Level</label>
          <div class="level-selector">
            <button
              v-for="level in levels"
              :key="'speak-' + level"
              class="level-btn"
              :class="{ active: modelValue.speakMinLevel === level }"
              @click="updateSpeakLevel(level)"
              :title="`Speak level ${level}`"
            >
              {{ getLevelLabel(level) }}
            </button>
          </div>
          <p class="description">Minimum permission level required to speak</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface PermissionModel {
  minLevel: number
  speakMinLevel?: number
}

interface Props {
  modelValue: PermissionModel
  title?: string
  showSpeakPermissions?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: 'Permission Settings',
  showSpeakPermissions: false
})

const emit = defineEmits<{
  'update:modelValue': [value: PermissionModel]
}>()

const levels = [1, 2, 3, 4]

const getLevelLabel = (level: number): string => {
  const labels: { [key: number]: string } = {
    1: 'Level 1',
    2: 'Level 2',
    3: 'Level 3',
    4: 'Level 4'
  }
  return labels[level] || 'Unknown'
}

const updateLevel = (level: number) => {
  emit('update:modelValue', { ...props.modelValue, minLevel: level })
}

const updateSpeakLevel = (level: number) => {
  emit('update:modelValue', { ...props.modelValue, speakMinLevel: level })
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
