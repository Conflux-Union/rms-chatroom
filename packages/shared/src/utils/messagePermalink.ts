// Message permalinks: /<serverId>/<channelId>/<messageId> on the origin that
// serves the app. Web copies location.origin URLs, Android copies the API base
// URL, and desktop's hash router nests the path under '#'. Chat renders a
// message that is exactly one permalink as a "forwarded message" quote.
const API_BASE = import.meta.env.VITE_API_BASE || ''

const PERMALINK_PATH = /^\/(\d+)\/(\d+)\/(\d+)$/

export interface MessagePermalink {
  serverId: number
  channelId: number
  messageId: number
}

// Origins whose /a/b/c paths are our message permalinks: the serving origin
// plus the API base origin (they differ when the desktop build hard-defines
// VITE_API_BASE). Web and Android permalinks share the API origin in
// production, so this covers links copied on every platform.
function permalinkOrigins(): Set<string> {
  const origins = new Set<string>()
  if (typeof window !== 'undefined') origins.add(window.location.origin)
  if (API_BASE) {
    try {
      origins.add(new URL(API_BASE, window.location.origin).origin)
    } catch {
      // Malformed build-time base: origin matching degrades to window origin.
    }
  }
  return origins
}

function parsePermalinkPath(url: URL): MessagePermalink | null {
  const path = PERMALINK_PATH.test(url.pathname)
    ? url.pathname
    : url.hash.replace(/^#/, '').split('?')[0]
  const match = PERMALINK_PATH.exec(path)
  if (!match) return null
  return {
    serverId: Number(match[1]),
    channelId: Number(match[2]),
    messageId: Number(match[3]),
  }
}

// Parse message content that is exactly one permalink URL. Links embedded in
// longer text stay normal markdown links. Pass `base` to also resolve
// origin-relative hrefs (anchor clicks); message content requires an absolute
// URL so a bare "/1/8/29" is not mistaken for a link.
export function parseMessagePermalink(content: string, base?: string): MessagePermalink | null {
  const raw = content.trim()
  if (!raw) return null
  let url: URL
  try {
    url = new URL(raw, base)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  if (!permalinkOrigins().has(url.origin)) return null
  return parsePermalinkPath(url)
}

// Candidate scan for permalink URLs embedded in longer text. The lazy body
// plus the (?![/\d]) guard keep trailing punctuation (e.g. a full stop) out
// of the candidate; origin validation still happens in parseMessagePermalink.
const PERMALINK_CANDIDATE = /https?:\/\/[^\s]*?\/\d+\/\d+\/\d+(?![/\d])/g

export type MessageContentSegment =
  | { type: 'text'; text: string }
  | { type: 'quote'; link: MessagePermalink }

// Split message content around our message permalinks: "AAA <link> BBB"
// becomes text("AAA") + quote(link) + text("BBB"), so the text renders as
// markdown with a quote card where the link was. A permalink only becomes a
// card when whitespace-delimited (spaces on both sides, at a content edge, or
// alone on its line); a link glued to text or punctuation ("看这个<link>。")
// stays a normal inline link. Candidates that fail origin/path validation
// always stay inline. Returns [] when no permalink qualifies.
export function splitMessagePermalinks(content: string): MessageContentSegment[] {
  const segments: MessageContentSegment[] = []
  let cursor = 0
  for (const match of content.matchAll(PERMALINK_CANDIDATE)) {
    const link = parseMessagePermalink(match[0])
    if (!link) continue
    const start = match.index ?? 0
    const end = start + match[0].length
    const spaceBefore = start === 0 || /\s/.test(content[start - 1])
    const spaceAfter = end === content.length || /\s/.test(content[end])
    if (!spaceBefore || !spaceAfter) continue
    if (start > cursor) segments.push({ type: 'text', text: content.slice(cursor, start) })
    segments.push({ type: 'quote', link })
    cursor = end
  }
  if (segments.length === 0) return []
  if (cursor < content.length) segments.push({ type: 'text', text: content.slice(cursor) })
  // Whitespace-only segments (e.g. the space between two adjacent links)
  // would render as empty paragraphs between cards — drop them.
  return segments.filter((s) => s.type !== 'text' || s.text.trim() !== '')
}
