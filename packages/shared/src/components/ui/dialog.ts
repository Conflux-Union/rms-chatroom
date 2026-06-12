/* Imperative confirm/alert dialogs on top of zhimo-modal.
   Drop-in for the naive-ui useDialog() subset this app uses. */

export interface DialogOptions {
  title?: string
  content?: string
  positiveText?: string
  negativeText?: string
  onPositiveClick?: () => void
  onNegativeClick?: () => void
}

type ZhimoModalElement = HTMLElement & { show: () => void; close: () => void }

function openDialog(opts: DialogOptions) {
  const modal = document.createElement('zhimo-modal') as ZhimoModalElement
  modal.setAttribute('heading', opts.title ?? '提示')
  modal.append(document.createTextNode(opts.content ?? ''))

  const addButton = (text: string, variant: string | null, onClick?: () => void) => {
    const btn = document.createElement('zhimo-button')
    btn.setAttribute('slot', 'footer')
    btn.setAttribute('size', 'sm')
    if (variant) btn.setAttribute('variant', variant)
    btn.textContent = text
    btn.addEventListener('click', () => {
      onClick?.()
      modal.close()
    })
    modal.append(btn)
  }

  if (opts.negativeText) addButton(opts.negativeText, 'secondary', opts.onNegativeClick)
  addButton(opts.positiveText ?? '确定', null, opts.onPositiveClick)

  modal.addEventListener('close', () => modal.remove())
  document.body.append(modal)
  modal.show()
}

export const dialog = {
  warning: openDialog,
  error: openDialog,
  success: openDialog,
  info: openDialog,
  create: openDialog,
}
