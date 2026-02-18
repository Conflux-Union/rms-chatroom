<template>
  <div class="permission-settings">
    <div class="permission-section">
      <div class="section-header">
        <h3>{{ title }}</h3>
        <p class="section-subtitle">{{ description }}</p>
      </div>
      <div class="permission-controls">
        <div class="permission-group">
          <label class="permission-label">选择权限等级</label>
          <div class="level-selector">
            <button
              v-for="level in serverLevels"
              :key="level"
              class="level-btn"
              :class="{ active: modelValue === level }"
              @click.stop="updateLevel(level)"
              :title="`选择权限等级 ${level}: ${getLevelLabel(level)}`"
            >
              <span class="level-number">{{ level }}</span>
              <span class="level-name">{{ getLevelLabel(level) }}</span>
              <span class="level-desc">{{ getLevelDescription(level) }}</span>
            </button>
          </div>
          <div v-if="serverValue !== undefined" class="current-selection">
            <span class="check-icon">✓</span>
            <span class="selection-text">当前设置: <strong>{{ getLevelLabel(serverValue) }}</strong></span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
interface Props {
  modelValue: number
  title?: string
  description?: string
  maxLevel?: number  // 用户最高权限等级，限制可选项
  serverValue?: number  // 服务器上的实际值
}

const props = withDefaults(defineProps<Props>(), {
  title: '权限等级设置',
  description: '用户权限等级必须达到此等级才能访问',
  maxLevel: 4,
  serverValue: undefined
})

const emit = defineEmits<{
  'update:modelValue': [value: number]
}>()

const serverLevels = computed(() => {
  // 根据maxLevel返回可用的等级
  const levels: number[] = []
  for (let i = 1; i <= Math.min(props.maxLevel, 4); i++) {
    levels.push(i)
  }
  return levels
})

const getLevelLabel = (level: number): string => {
  const labels: { [key: number]: string } = {
    1: '所有人',
    2: '权限2+',
    3: '权限3+',
    4: '权限4(管理员)'
  }
  return labels[level] || '未知'
}

const getLevelDescription = (level: number): string => {
  const descriptions: { [key: number]: string } = {
    1: '无限制',
    2: '需要权限',
    3: '需要高权限',
    4: '仅管理员'
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
  grid-template-columns: repeat(auto-fit, minmax(115px, 1fr));
  gap: 12px;
}

.level-btn {
  padding: 14px 12px;
  border: 2px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-background);
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
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

.level-number {
  font-size: 20px;
  font-weight: 800;
  color: inherit;
  position: relative;
  z-index: 1;
}

.level-name {
  font-size: 11px;
  opacity: 0.75;
  font-weight: 700;
  position: relative;
  z-index: 1;
}

.level-desc {
  font-size: 10px;
  opacity: 0.6;
  position: relative;
  z-index: 1;
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
