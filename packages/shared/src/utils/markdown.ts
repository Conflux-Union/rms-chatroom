// Chat message markdown rendering, safe for v-html.
// Pipeline: marked (GFM, single newlines become <br>) -> DOMPurify allowlist
// -> DOM post-pass that highlights @mentions on text nodes and hardens links.
import { marked } from 'marked'
import type { Tokens } from 'marked'
import DOMPurify from 'dompurify'

const renderer = {
  // Images render as links instead of <img>: remote images would leak viewer
  // IPs and enable tracking pixels, and media belongs to the attachment
  // system. Falling back to a bare <img> removal would blank the message.
  image({ href, text }: Tokens.Image): string {
    const label = text || href
    const escapedLabel = label.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return href
      ? `<a href="${href.replace(/"/g, '%22')}">${escapedLabel}</a>`
      : escapedLabel
  },
}

marked.use({ gfm: true, breaks: true, renderer })

const SANITIZE_OPTIONS = {
  ALLOWED_TAGS: [
    'p', 'br', 'strong', 'b', 'em', 'i', 'del', 's', 'code', 'pre',
    'blockquote', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'span', 'input',
  ],
  ALLOWED_ATTR: ['href', 'title', 'type', 'checked', 'disabled'],
  ALLOW_DATA_ATTR: false,
}

// Messages are re-rendered on every store update, so memoize by content.
const cache = new Map<string, string>()
const CACHE_LIMIT = 500

export function renderMessageHtml(content: string): string {
  const cached = cache.get(content)
  if (cached !== undefined) return cached

  const markdown = marked.parse(content, { async: false })
  const html = highlightMentions(hardenDom(DOMPurify.sanitize(markdown, SANITIZE_OPTIONS)))

  if (cache.size >= CACHE_LIMIT) cache.clear()
  cache.set(content, html)
  return html
}

function hardenDom(html: string): string {
  const container = document.createElement('div')
  container.innerHTML = html
  for (const anchor of container.querySelectorAll('a')) {
    anchor.setAttribute('target', '_blank')
    anchor.setAttribute('rel', 'noopener noreferrer')
  }
  // Raw user-typed <input> tags must stay inert; marked's task-list checkboxes
  // are already disabled, but the attribute is not guaranteed.
  for (const input of container.querySelectorAll('input')) {
    input.setAttribute('disabled', '')
  }
  return container.innerHTML
}

// Wrap @name occurrences in text nodes; mentions inside links or code blocks
// stay untouched. Mention styling comes from ChatArea's .mention-highlight.
function highlightMentions(html: string): string {
  const container = document.createElement('div')
  container.innerHTML = html

  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT)
  const textNodes: Text[] = []
  while (walker.nextNode()) {
    const node = walker.currentNode as Text
    const parent = node.parentElement
    if (parent?.closest('a, code, pre')) continue
    if (/@(\w+)/.test(node.nodeValue ?? '')) textNodes.push(node)
  }
  for (const node of textNodes) wrapMentions(node)

  return container.innerHTML
}

function wrapMentions(node: Text): void {
  const text = node.nodeValue ?? ''
  const regex = /@(\w+)/g
  const fragment = document.createDocumentFragment()
  let last = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(text)) !== null) {
    if (match.index > last) {
      fragment.appendChild(document.createTextNode(text.slice(last, match.index)))
    }
    const span = document.createElement('span')
    span.className = 'mention-highlight'
    span.textContent = match[0]
    fragment.appendChild(span)
    last = match.index + match[0].length
  }
  if (last < text.length) {
    fragment.appendChild(document.createTextNode(text.slice(last)))
  }
  node.parentNode?.replaceChild(fragment, node)
}
