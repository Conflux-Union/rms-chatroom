<template>
  <div class="permission-settings">
    <div class="permission-section">
      <div class="section-header">
        <h3>{{ title }}</h3>
        <p class="section-subtitle">{{ description }}</p>
      </div>
      <div class="permission-controls">
        <div class="permission-group">
          <label class="permission-label">选择用户类型</label>
          <div class="level-selector">
            <button
              v-for="level in internalLevels"
              :key="level"
              class="level-btn"
              :class="{ active: modelValue === level }"
              @click.stop="updateLevel(level)"
              :title="level === 1 ? '允许外服用户访问' : '仅允许内服用户访问'"
            >
              <span class="level-icon">{{ level === 1 ? '🌍' : '🏢' }}</span>
              <span class="level-name">{{ getInternalLabel(level) }}</span>
              <span class="level-desc">{{ getInternalDescription(level) }}</span>
            </button>
          </div>
          <div v-if="serverValue !== undefined" class="current-selection">
            <span class="check-icon">✓</span>
            <span class="selection-text">当前设置: <strong>{{ getInternalLabel(serverValue) }}</strong></span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  modelValue: number
  title?: string
  description?: string
  serverValue?: number  // 服务器上的实际值
}

const props = withDefaults(defineProps<Props>(), {
  title: '内部/外部权限设置',
  description: '选择此服务器对外部用户的可见性 (外服=任何人, 内服=内部成员)',
  serverValue: undefined
})

const emit = defineEmits<{
  'update:modelValue': [value: number]
}>()

const internalLevels = [1, 2]

const getInternalLabel = (level: number): string => {
  const labels: { [key: number]: string } = {
    1: '外服用户',
    2: '内服用户'
  }
  return labels[level] || '未知'
}

const getInternalDescription = (level: number): string => {
  const descriptions: { [key: number]: string } = {
    1: '公开',
    2: '私密'
  }
  return descriptions[level] || ''
}

const updateLevel = (level: number) => {
  emit('update:modelValue', level)
}
</script>

<style scoped>
.permission-settings {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.permission-section {
  background: linear-gradient(135deg, var(--color-background-soft) 0%, var(--color-background) 100%);
  border-radius: 12px;
  padding: 24px;
  border: 1px solid var(--color-border);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.section-header {
  margin-bottom: 24px;
}

.permission-section h3 {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text-main);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.section-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-muted);
  font-weight: 500;
  line-height: 1.5;
}

.permission-controls {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.permission-group {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.permission-label {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-main);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.level-selector {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(145px, 1fr));
  gap: 12px;
}

.level-btn {
  padding: 16px 12px;
  border: 2px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-background);
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  position: relative;
  overflow: hidden;
}

.level-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, transparent, rgba(255, 255, 255, 0.1));
  pointer-events: none;
}

.level-icon {
  font-size: 28px;
  display: block;
  position: relative;
  z-index: 1;
  margin-bottom: 2px;
  pointer-events: none;
}

.level-name {
  font-size: 12px;
  opacity: 0.8;
  font-weight: 700;
  position: relative;
  z-index: 1;
  pointer-events: none;
}

.level-desc {
  font-size: 10px;
  opacity: 0.6;
  position: relative;
  z-index: 1;
  pointer-events: none;
}

.level-btn:hover {
  background: var(--color-background-soft);
  color: var(--color-text-main);
  border-color: var(--color-accent);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.level-btn.active {
  background: linear-gradient(135deg, var(--color-accent) 0%, rgba(var(--color-accent-rgb, 100, 150, 200), 0.9) 100%);
  color: #000000;
  border-color: var(--color-accent);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.25);
  transform: translateY(-4px);
}

.level-btn.active .level-name,
.level-btn.active .level-desc {
  opacity: 1;
  font-weight: 700;
}

.current-selection {
  background: rgba(var(--color-accent-rgb, 100, 150, 200), 0.12);
  border-left: 4px solid var(--color-accent);
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-accent);
  margin-top: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.check-icon {
  font-size: 16px;
  font-weight: 800;
}

.selection-text {
  display: flex;
  align-items: center;
  gap: 4px;
}
</style>
