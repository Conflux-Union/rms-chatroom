<script setup lang="ts">
import { computed, useSlots } from 'vue'

const props = defineProps<{
  show?: boolean
  title?: string
  width?: string | number
  // accepted for naive-ui call-site compatibility, no visual meaning here
  preset?: string
  segmented?: unknown
}>()

const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>()

const slots = useSlots()
const hasFooter = computed(() => Boolean(slots.footer || slots.action))

const hostStyle = computed(() =>
  props.width != null
    ? { '--zhimo-modal-width': typeof props.width === 'number' ? `${props.width}px` : props.width }
    : undefined
)
</script>

<template>
  <zhimo-modal
    :open="show ? true : undefined"
    :heading="title"
    :style="hostStyle"
    @close="emit('update:show', false)"
  >
    <slot />
    <div v-if="hasFooter" slot="footer" class="zm-modal-footer">
      <slot name="footer" /><slot name="action" />
    </div>
  </zhimo-modal>
</template>

<style scoped>
.zm-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
