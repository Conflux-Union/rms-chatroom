/* ZhiMo UI Vue adapter layer.
   Wrappers keep the prop/event shape of the naive-ui components they
   replaced so call sites migrate by import rename. */

export { default as ZmButton } from './ZmButton.vue'
export { default as ZmInput } from './ZmInput.vue'
export { default as ZmInputNumber } from './ZmInputNumber.vue'
export { default as ZmModal } from './ZmModal.vue'
export { default as ZmDropdown } from './ZmDropdown.vue'
export type { ZmDropdownOption } from './ZmDropdown.vue'
export { default as ZmSelect } from './ZmSelect.vue'
export type { ZmSelectOption } from './ZmSelect.vue'
export { default as ZmSlider } from './ZmSlider.vue'
export { default as ZmProgress } from './ZmProgress.vue'
export { default as ZmSpin } from './ZmSpin.vue'
export { default as ZmSpace } from './ZmSpace.vue'
export { default as ZmCard } from './ZmCard.vue'
export { default as ZmEmpty } from './ZmEmpty.vue'
export { default as ZmEllipsis } from './ZmEllipsis.vue'
export { default as ZmForm } from './ZmForm.vue'
export { default as ZmFormItem } from './ZmFormItem.vue'
export { dialog } from './dialog'
export type { DialogOptions } from './dialog'
export { toast } from 'zhimo-ui'
