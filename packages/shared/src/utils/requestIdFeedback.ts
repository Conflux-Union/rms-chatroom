import axios from 'axios'

/**
 * Global API error feedback card.
 *
 * Rendered imperatively (same style as zhimo's toast/dialog helpers) so web
 * and desktop both get it without mounting anything in their App.vue. Shows
 * one sticky card for unexpected API failures carrying the backend request
 * id the user can copy into a bug report; repeated failures replace the
 * card's content instead of stacking copies.
 */

interface CardRefs {
  root: HTMLElement
  statusEl: HTMLElement
  idEl: HTMLElement
  copyBtn: HTMLButtonElement
}

let card: CardRefs | null = null
let copyResetTimer: ReturnType<typeof setTimeout> | undefined
let currentKey = ''

const STYLE_ID = 'cxu-request-id-feedback-style'

const CSS = `
.cxu-req-card {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3000;
  width: min(420px, calc(100vw - 32px));
  padding: 14px 16px 12px;
  background: var(--zhimo-surface-bg);
  border: 1px solid var(--zhimo-border-strong);
  border-radius: var(--zhimo-radius);
  box-shadow: var(--zhimo-shadow-md);
  color: var(--zhimo-fg);
  font-family: var(--zhimo-font);
  opacity: 0;
  translate: 0 -8px;
  transition: opacity var(--zhimo-transition), translate var(--zhimo-transition);
}
.cxu-req-card.cxu-req-card-shown {
  opacity: 1;
  translate: 0 0;
}
.cxu-req-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cxu-req-seal {
  width: 8px;
  height: 8px;
  border-radius: var(--zhimo-radius-sm);
  background: var(--zhimo-danger);
  flex: none;
}
.cxu-req-title {
  font-weight: 700;
  font-size: 0.95rem;
}
.cxu-req-status {
  font-size: 0.75rem;
  color: var(--zhimo-fg-muted);
  border: 1px solid var(--zhimo-border);
  border-radius: var(--zhimo-radius-sm);
  padding: 1px 6px;
}
.cxu-req-close {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--zhimo-fg-muted);
  font-size: 1rem;
  line-height: 1;
  cursor: pointer;
  padding: 2px 4px;
}
.cxu-req-close:hover {
  color: var(--zhimo-fg);
}
.cxu-req-msg {
  margin-top: 6px;
  font-size: 0.85rem;
  color: var(--zhimo-fg-muted);
}
.cxu-req-idrow {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.cxu-req-id {
  flex: 1;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  padding: 5px 8px;
  background: var(--zhimo-bg-subtle);
  border: 1px solid var(--zhimo-border);
  border-radius: var(--zhimo-radius-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cxu-req-copy {
  flex: none;
  border: 1px solid var(--zhimo-border-strong);
  border-radius: var(--zhimo-radius-sm);
  background: var(--zhimo-bg);
  color: var(--zhimo-fg);
  font-size: 0.8rem;
  padding: 5px 10px;
  cursor: pointer;
}
.cxu-req-copy:hover {
  border-color: var(--zhimo-fg);
}
.cxu-req-copy:disabled {
  opacity: 0.6;
  cursor: default;
}
`

function ensureMounted(): CardRefs {
  if (card) return card
  if (!document.getElementById(STYLE_ID)) {
    const style = document.createElement('style')
    style.id = STYLE_ID
    style.textContent = CSS
    document.head.appendChild(style)
  }

  const root = document.createElement('div')
  root.className = 'cxu-req-card'
  root.setAttribute('role', 'alert')

  const head = document.createElement('div')
  head.className = 'cxu-req-head'
  const seal = document.createElement('span')
  seal.className = 'cxu-req-seal'
  const title = document.createElement('span')
  title.className = 'cxu-req-title'
  title.textContent = '操作出现问题'
  const statusEl = document.createElement('span')
  statusEl.className = 'cxu-req-status'
  const closeBtn = document.createElement('button')
  closeBtn.className = 'cxu-req-close'
  closeBtn.type = 'button'
  closeBtn.textContent = '✕'
  closeBtn.addEventListener('click', hideRequestErrorCard)
  head.append(seal, title, statusEl, closeBtn)

  const msg = document.createElement('div')
  msg.className = 'cxu-req-msg'
  msg.textContent = '请稍后重试；若持续出现，可复制下方 ID 发给管理员排查。'

  const idrow = document.createElement('div')
  idrow.className = 'cxu-req-idrow'
  const idEl = document.createElement('code')
  idEl.className = 'cxu-req-id'
  const copyBtn = document.createElement('button')
  copyBtn.className = 'cxu-req-copy'
  copyBtn.type = 'button'
  copyBtn.textContent = '复制 ID'
  copyBtn.addEventListener('click', () => copyRequestId(copyBtn, idEl))
  idrow.append(idEl, copyBtn)

  root.append(head, msg, idrow)
  document.body.appendChild(root)

  card = { root, statusEl, idEl, copyBtn }
  return card
}

function copyRequestId(btn: HTMLButtonElement, idEl: HTMLElement): void {
  const text = idEl.textContent ?? ''
  const done = () => {
    btn.textContent = '已复制'
    clearTimeout(copyResetTimer)
    copyResetTimer = setTimeout(() => {
      btn.textContent = '复制 ID'
    }, 1200)
  }
  navigator.clipboard
    .writeText(text)
    .then(done)
    .catch(() => {
      // Clipboard API can be unavailable (permissions policy, older webview)
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      try {
        document.execCommand('copy')
        done()
      } finally {
        ta.remove()
      }
    })
}

/** Hide the card; safe to call repeatedly. */
export function hideRequestErrorCard(): void {
  currentKey = ''
  if (!card) return
  card.root.classList.remove('cxu-req-card-shown')
}

/**
 * Show (or replace the content of) the global error card. `requestId` is
 * the backend-generated cxu_chat_req_ id; `status` is the HTTP status.
 */
export function reportApiError(requestId: string | null | undefined, status?: number): void {
  const id = typeof requestId === 'string' && requestId ? requestId : null
  // Polling/retries repeat the same failure — keep the existing card.
  const key = `${status ?? ''}|${id ?? ''}`
  if (card && key === currentKey) return
  currentKey = key

  const refs = ensureMounted()
  refs.statusEl.textContent = status !== undefined ? `HTTP ${status}` : ''
  if (id) {
    refs.idEl.textContent = id
    refs.copyBtn.disabled = false
  } else {
    refs.idEl.textContent = '(本次响应未返回 ID)'
    refs.copyBtn.disabled = true
  }
  // Restart the entry transition when the card is already visible.
  refs.root.classList.remove('cxu-req-card-shown')
  void refs.root.offsetWidth
  refs.root.classList.add('cxu-req-card-shown')
}

/** 401/403 are expected business outcomes (session recovery, permission rules), not anomalies. */
function isExpectedStatus(status: number): boolean {
  return status === 401 || status === 403
}

/**
 * Classify an axios failure and surface it: unexpected responses show the
 * feedback card, expected rejections (401/403) and network errors (no
 * response at all) only log to the console with the request id.
 */
export function reportAxiosError(error: unknown): void {
  if (!axios.isAxiosError(error) || !error.response) return
  const { status, headers } = error.response
  const raw = headers?.['x-request-id']
  const requestId = Array.isArray(raw) ? raw[0] : (raw ?? null)
  if (isExpectedStatus(status)) {
    console.warn(`[api] request rejected (HTTP ${status}), request id: ${requestId ?? '(none)'}`)
    return
  }
  reportApiError(requestId, status)
}

/**
 * Same classification for fetch-based callers (authFetch): pass the final
 * Response and the card fires only for unexpected non-2xx statuses.
 */
export function reportFetchError(response: Response): void {
  if (response.ok || isExpectedStatus(response.status)) return
  reportApiError(response.headers.get('x-request-id'), response.status)
}
