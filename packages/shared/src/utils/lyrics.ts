/**
 * LRC lyric parsing. Providers return raw LRC text (plus an optional
 * translation whose timestamps mirror the primary lyric); these helpers turn
 * that into a flat, time-sorted line list for playback sync.
 */

export interface LyricLine {
  timeMs: number
  text: string
  translation?: string
}

interface TimedText {
  timeMs: number
  text: string
}

// [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] — providers emit all three.
const TIME_TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
const OFFSET_TAG = /^\s*\[offset:\s*([+-]?\d+)\s*\]/

// Fractional part is centiseconds when written with two digits and
// milliseconds with three; scale by digit count.
function fractionToMs(frac: string | undefined): number {
  if (!frac) return 0
  const v = parseInt(frac, 10)
  if (frac.length === 1) return v * 100
  if (frac.length === 2) return v * 10
  return v
}

export function parseLrc(text: string): TimedText[] {
  if (!text) return []
  let offsetMs = 0
  const lines: TimedText[] = []
  for (const raw of text.split(/\r?\n/)) {
    const offset = raw.match(OFFSET_TAG)
    if (offset) {
      // Positive offset shifts lyric timestamps up (lines show earlier).
      offsetMs = parseInt(offset[1], 10)
      continue
    }
    TIME_TAG.lastIndex = 0
    const tags = [...raw.matchAll(TIME_TAG)]
    if (tags.length === 0) continue
    const content = raw.replace(TIME_TAG, '').trim()
    if (!content) continue
    for (const tag of tags) {
      const timeMs =
        parseInt(tag[1], 10) * 60000 +
        parseInt(tag[2], 10) * 1000 +
        fractionToMs(tag[3]) -
        offsetMs
      if (timeMs < 0) continue
      lines.push({ timeMs, text: content })
    }
  }
  lines.sort((a, b) => a.timeMs - b.timeMs)
  return lines
}

// Timestamps are matched rounded to 10ms to absorb float jitter between the
// two LRC payloads without ever colliding two real lines.
function timeKey(timeMs: number): number {
  return Math.round(timeMs / 10)
}

export function mergeLyricTranslation(lines: TimedText[], translation: string): LyricLine[] {
  const result: LyricLine[] = lines.map((l) => ({ timeMs: l.timeMs, text: l.text }))
  if (!translation || result.length === 0) return result

  const byTime = new Map<number, string>()
  for (const t of parseLrc(translation)) {
    byTime.set(timeKey(t.timeMs), t.text)
  }
  for (const line of result) {
    line.translation = byTime.get(timeKey(line.timeMs))
  }
  return result
}

// Index of the line active at timeMs (the last line whose timestamp has
// passed), or -1 before the first line.
export function activeLyricIndex(lines: LyricLine[], timeMs: number): number {
  let idx = -1
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].timeMs > timeMs) break
    idx = i
  }
  return idx
}
