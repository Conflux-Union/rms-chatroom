<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'

export interface ZmDropdownOption {
  label: string
  key: string | number
  disabled?: boolean
  danger?: boolean
  // tolerated leftover from naive-ui options; ignored
  props?: Record<string, unknown>
}

const props = defineProps<{
  options: ZmDropdownOption[]
  show?: boolean
  x?: number
  y?: number
  // accepted for naive-ui call-site compatibility (all usages are manual)
  trigger?: string
  placement?: string
}>()

const emit = defineEmits<{
  (e: 'select', key: string | number): void
  (e: 'clickoutside'): void
}>()

const el = ref<HTMLElement & { openAt: (x: number, y: number) => void; close: () => void; open: boolean }>()

watch(
  () => [props.show, props.x, props.y] as const,
  async ([show, x, y]) => {
    await nextTick()
    if (!el.value) return
    if (show) el.value.openAt(x ?? 0, y ?? 0)
    else el.value.close()
  },
  { immediate: true }
)

function onSelect(e: Event) {
  const raw = (e as CustomEvent<{ value: string }>).detail.value
  // keys can be numbers at the call site; attributes stringify them
  const match = props.options.find((o) => String(o.key) === raw)
  emit('select', match ? match.key : raw)
}

// fires on outside click / Esc / item select; only the first two need
// the parent to reset its show flag
function onClose() {
  if (props.show) emit('clickoutside')
}
</script>

<template>
  <zhimo-dropdown ref="el" class="zm-dropdown" @select.stop="onSelect" @close="onClose">
    <zhimo-menu-item
      v-for="opt in options"
      :key="opt.key"
      :value="String(opt.key)"
      :disabled="opt.disabled || undefined"
      :danger="opt.danger || undefined"
    >
      {{ opt.label }}
    </zhimo-menu-item>
  </zhimo-dropdown>
</template>

<style scoped>
/* context-menu host must not take layout space */
.zm-dropdown {
  position: fixed;
  width: 0;
  height: 0;
}
</style>
