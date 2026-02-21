<template>
  <div class="permission-settings">
    <div class="permission-section">
      <div class="section-header">
        <h3>{{ title }}</h3>
        <p class="section-subtitle">{{ description }}</p>
      </div>
      <div class="permission-controls">
        <!-- Logic operator toggle -->
        <div class="permission-group">
          <label class="permission-label">逻辑运算符</label>
          <div class="operator-selector">
            <button
              class="operator-btn"
              :class="{ active: logicOperator === 'AND' }"
              @click="$emit('update:logicOperator', 'AND')"
            >
              <span class="operator-text">AND</span>
              <span class="operator-desc">需同时满足两个条件</span>
            </button>
            <button
              class="operator-btn"
              :class="{ active: logicOperator === 'OR' }"
              @click="$emit('update:logicOperator', 'OR')"
            >
              <span class="operator-text">OR</span>
              <span class="operator-desc">满足任一条件即可</span>
            </button>
          </div>
        </div>

        <!-- Dual level selectors -->
        <div class="dual-selectors">
          <!-- Permission Level -->
          <div class="permission-group">
            <label class="permission-label">权限等级</label>
            <div class="level-selector">
              <button
                v-for="level in levels"
                :key="'perm-' + level"
                class="level-btn"
                :class="{ active: permLevel === level }"
                @click="$emit('update:permLevel', level)"
                :title="`Permission Level ${level}: ${getLevelLabel(level)}`"
              >
                <span class="level-number">{{ level }}</span>
                <span class="level-name">{{ getLevelLabel(level) }}</span>
              </button>
            </div>
          </div>

          <!-- Group Level -->
          <div class="permission-group">
            <label class="permission-label">组等级</label>
            <div class="level-selector">
              <button
                v-for="level in levels"
                :key="'group-' + level"
                class="level-btn"
                :class="{ active: groupLevel === level }"
                @click="$emit('update:groupLevel', level)"
                :title="`Group Level ${level}: ${getLevelLabel(level)}`"
              >
                <span class="level-number">{{ level }}</span>
                <span class="level-name">{{ getLevelLabel(level) }}</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Expression preview -->
        <div class="expression-preview">
          <span class="expression-label">规则：</span>
          <code class="expression-code">{{ expressionText }}</code>
        </div>

        <!-- Current server values -->
        <div v-if="hasServerValues" class="current-selection">
          <span class="check-icon">&#10003;</span>
          <span class="selection-text">当前值：<strong>{{ serverExpressionText }}</strong></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  permLevel: number
  groupLevel: number
  logicOperator: 'AND' | 'OR'
  title?: string
  description?: string
  maxLevel?: number
  serverPermLevel?: number
  serverGroupLevel?: number
  serverOperator?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '权限设置',
  description: '配置双维度权限要求',
  maxLevel: 4,
  serverPermLevel: undefined,
  serverGroupLevel: undefined,
  serverOperator: undefined
})

defineEmits<{
  'update:permLevel': [value: number]
  'update:groupLevel': [value: number]
  'update:logicOperator': [value: 'AND' | 'OR']
}>()

const levels = computed(() => {
  const result: number[] = []
  for (let i = 0; i <= Math.min(props.maxLevel, 4); i++) {
    result.push(i)
  }
  return result
})

const getLevelLabel = (level: number): string => {
  if (level === 0) return '无限制'
  if (level === 4) return 'Lv4 (管理员)'
  return `Lv${level}+`
}

const formatExpression = (perm: number, group: number, op: string): string => {
  const permPart = perm > 0 ? `perm >= ${perm}` : null
  const groupPart = group > 0 ? `group >= ${group}` : null
  if (permPart && groupPart) return `${permPart} ${op} ${groupPart}`
  if (permPart) return permPart
  if (groupPart) return groupPart
  return '无限制'
}

const expressionText = computed(() =>
  formatExpression(props.permLevel, props.groupLevel, props.logicOperator)
)

const hasServerValues = computed(() =>
  props.serverPermLevel !== undefined || props.serverGroupLevel !== undefined
)

const serverExpressionText = computed(() =>
  formatExpression(
    props.serverPermLevel ?? 0,
    props.serverGroupLevel ?? 0,
    props.serverOperator ?? 'AND'
  )
)
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

/* Operator toggle */
.operator-selector {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.operator-btn {
  padding: 12px;
  border: 2px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-background);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.operator-btn:hover {
  background: var(--color-background-soft);
  color: var(--color-text-main);
  border-color: var(--color-accent);
}

.operator-btn.active {
  background: linear-gradient(135deg, var(--color-accent) 0%, rgba(var(--color-accent-rgb, 100, 150, 200), 0.9) 100%);
  color: #000000;
  border-color: var(--color-accent);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.operator-text {
  font-size: 16px;
  font-weight: 800;
}

.operator-desc {
  font-size: 10px;
  opacity: 0.75;
  font-weight: 600;
}

.operator-btn.active .operator-desc {
  opacity: 1;
}

/* Dual selectors layout */
.dual-selectors {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.level-selector {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}

.level-btn {
  padding: 12px 6px;
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
  gap: 4px;
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
  font-size: 18px;
  font-weight: 800;
  color: inherit;
  position: relative;
  z-index: 1;
}

.level-name {
  font-size: 10px;
  opacity: 0.75;
  font-weight: 700;
  position: relative;
  z-index: 1;
  text-align: center;
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

.level-btn.active .level-name {
  opacity: 1;
  font-weight: 700;
}

/* Expression preview */
.expression-preview {
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.expression-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--color-text-muted);
  text-transform: uppercase;
}

.expression-code {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-accent);
  font-family: 'Fira Code', 'Cascadia Code', monospace;
}

/* Current selection indicator */
.current-selection {
  background: rgba(var(--color-accent-rgb, 100, 150, 200), 0.12);
  border-left: 4px solid var(--color-accent);
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-accent);
  margin-top: 4px;
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
