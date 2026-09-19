<script setup lang="ts">
// Forwarded-message quote card for a message permalink. Renders the source
// message's author and content; inner permalinks in the source content become
// nested cards recursively — including a source that is exactly one permalink
// ("pure forward"), so every forward level keeps its own sender shown.
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import { isTauri } from '../index'
import { t } from '../i18n'
import { renderMessageHtml } from '../utils/markdown'
import {
  parseMessagePermalink,
  splitMessagePermalinks,
  type MessageContentSegment,
  type MessagePermalink,
} from '../utils/messagePermalink'
import type { Message } from '../types'

const props = withDefaults(defineProps<{ link: MessagePermalink; depth?: number }>(), {
  depth: 0,
})

interface ForwardQuote {
  status: 'loading' | 'ready' | 'unavailable'
  author?: string
  content?: string
  hasAttachments?: boolean
  note?: string
}

// Module-level cache shared by every card instance and mount: quotes of the
// same source message (top-level or nested) share one fetch.
const quoteCache = reactive(new Map<string, ForwardQuote>())
const quoteInFlight = new Set<string>()

const API_BASE = import.meta.env.VITE_API_BASE || ''
// Rendering cap for nested cards so forward cycles cannot recurse forever;
// deeper permalinks degrade to plain links.
const MAX_NESTING_DEPTH = 3

const auth = useAuthStore()
const router = useRouter()

function quoteKey(link: MessagePermalink): string {
  return `${link.channelId}:${link.messageId}`
}

function quoteFor(link: MessagePermalink): ForwardQuote {
  const key = quoteKey(link)
  const cached = quoteCache.get(key)
  if (cached) return cached
  if (!quoteInFlight.has(key)) {
    quoteInFlight.add(key)
    const quote = reactive<ForwardQuote>({ status: 'loading', note: t('chat.loadingQuote') })
    quoteCache.set(key, quote)
    fetchQuote(link, key, quote)
  }
  return quoteCache.get(key)!
}

async function fetchQuote(link: MessagePermalink, key: string, quote: ForwardQuote) {
  try {
    const res = await axios.get(`${API_BASE}/api/channels/${link.channelId}/messages`, {
      params: { around: link.messageId, limit: 5 },
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    const source = (res.data as Message[]).find((m) => m.id === link.messageId) ?? null
    if (!source) {
      // The list endpoint filters deleted messages, so a 200 without the
      // target means the source message no longer exists.
      quote.status = 'unavailable'
      quote.note = t('chat.quoteDeleted')
      return
    }
    quote.status = 'ready'
    quote.note = undefined
    quote.author = source.username
    quote.content = source.content
    quote.hasAttachments = (source.attachments?.length ?? 0) > 0
  } catch (error: any) {
    quote.status = 'unavailable'
    quote.note = error.response?.status === 403 ? t('chat.quoteNoPermission') : t('chat.quoteUnavailable')
  } finally {
    quoteInFlight.delete(key)
  }
}

const quote = computed(() => quoteFor(props.link))

// Source content split around inner permalinks: text renders as markdown,
// permalinks become nested cards. Null when the content has no qualifying
// permalink (plain markdown render).
const bodySegments = computed<MessageContentSegment[] | null>(() => {
  const q = quote.value
  if (q.status !== 'ready' || !q.content) return null
  const segments = splitMessagePermalinks(q.content)
  return segments.length > 0 ? segments : null
})

function permalinkUrl(link: MessagePermalink): string {
  return `${window.location.origin}/${link.serverId}/${link.channelId}/${link.messageId}`
}

// Open the quoted source: push the message deep-link route; the route watcher
// in Main.vue switches the channel and ChatArea positions on the message.
function openForwardedMessage(link: MessagePermalink) {
  router.push({
    name: 'MessagePath',
    params: {
      serverId: String(link.serverId),
      channelId: String(link.channelId),
      messageId: String(link.messageId),
    },
  })
}

// Inside the quote body, links navigate themselves; only swallow the
// card-open click when an anchor was actually hit.
async function handleBodyClick(event: MouseEvent) {
  const anchor = (event.target as HTMLElement | null)?.closest?.('a')
  if (!anchor) return
  event.stopPropagation()
  const href = anchor.getAttribute('href')
  if (!href || href.startsWith('#')) return
  const parsed = parseMessagePermalink(href, window.location.origin)
  if (parsed) {
    event.preventDefault()
    openForwardedMessage(parsed)
    return
  }
  if (!isTauri) return
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('open_external', { url: href })
  } catch {
    // Opening in the desktop shell failed; nothing useful to show.
  }
}
</script>

<template>
  <div
    class="message-forward-quote"
    role="button"
    tabindex="0"
    @click.stop="openForwardedMessage(link)"
    @keydown.enter.stop="openForwardedMessage(link)"
  >
    <span class="forward-quote-label">{{ t('chat.forwardedMessage') }}</span>
    <span v-if="quote.status !== 'ready'" class="forward-quote-note">{{ quote.note }}</span>
    <template v-else>
      <span class="forward-quote-author">{{ quote.author }}</span>
      <template v-if="bodySegments">
        <template v-for="(seg, segIndex) in bodySegments" :key="segIndex">
          <div
            v-if="seg.type === 'text'"
            class="forward-quote-body"
            @click="handleBodyClick"
            v-html="renderMessageHtml(seg.text)"
          ></div>
          <ForwardQuoteCard
            v-else-if="depth < MAX_NESTING_DEPTH"
            :link="seg.link"
            :depth="depth + 1"
          />
          <div
            v-else
            class="forward-quote-body"
            @click="handleBodyClick"
            v-html="renderMessageHtml(permalinkUrl(seg.link))"
          ></div>
        </template>
      </template>
      <div
        v-else
        class="forward-quote-body"
        @click="handleBodyClick"
        v-html="renderMessageHtml(quote.content || (quote.hasAttachments ? `[${t('chat.attachmentTag')}]` : ''))"
      ></div>
    </template>
  </div>
</template>

<style>
/* Unscoped: nested card instances need the same rules without sharing a
   scope id, and the forward-quote-* classes are component-specific. */
.message-forward-quote {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 100%;
  margin: 2px 0 4px;
  padding: 6px 10px;
  text-align: left;
  background: var(--surface-glass);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

/* Only the innermost card under the cursor highlights: without :not(:has())
   every ancestor card would match :hover too and light up together. */
.message-forward-quote:hover:not(:has(.message-forward-quote:hover)) {
  background: var(--surface-glass-input);
}

.message-forward-quote:focus-visible {
  outline: 1px solid var(--color-accent);
}

/* Nested cards sit inside the body area — indent one level. */
.message-forward-quote .message-forward-quote {
  margin: 4px 0 2px 10px;
}

.forward-quote-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.forward-quote-author {
  color: var(--color-accent);
  font-weight: 500;
  font-size: 13px;
}

.forward-quote-body {
  color: var(--color-text-main);
  font-size: 13px;
  line-height: 1.4;
  word-wrap: break-word;
  word-break: break-word;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 6;
  overflow: hidden;
}

/* Markdown-rendered body: keep blocks compact for chat density. */
.forward-quote-body p {
  margin: 0 0 4px;
}

.forward-quote-body p:last-child {
  margin-bottom: 0;
}

.forward-quote-body a {
  color: var(--color-accent);
  text-decoration: underline;
}

.forward-quote-body code {
  padding: 1px 5px;
  background: var(--surface-glass-hover);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
}

.forward-quote-note {
  font-size: 12px;
  color: var(--color-text-muted);
  font-style: italic;
}
</style>
