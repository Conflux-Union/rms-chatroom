import { VERSION_NAME, COMMIT_HASH } from '../version'

// 5x5 block-letter bitmaps, one entry per letter: '1' = painted cell, '0' = gap.
// Cells are rendered as background-colored spaces, NOT '█' glyphs: U+2588 is
// East-Asian-Width Ambiguous, and on Windows the console font often lacks it,
// so per-character fallback resolves it through a CJK font that renders it
// full-width (or as tofu), scrambling the art.
const LETTERS: Record<string, string[]> = {
  R: ['11110', '10001', '11110', '10010', '10001'],
  M: ['10001', '11011', '10101', '10001', '10001'],
  S: ['01111', '10000', '01110', '00001', '11110'],
  C: ['01111', '10000', '10000', '10000', '01111'],
  H: ['10001', '10001', '11111', '10001', '10001'],
  A: ['01110', '10001', '11111', '10001', '10001'],
  T: ['11111', '00100', '00100', '00100', '00100'],
  ' ': ['00', '00', '00', '00', '00'],
}

// One color per art row: pink -> orange -> yellow -> green -> blue
const ROW_COLORS = ['#ff6b9d', '#ffa94d', '#ffd43b', '#69db7c', '#4dabf7']

let printed = false

interface Segment {
  text: string
  style: string
}

function buildRows(text: string): string[] {
  const rows = ['', '', '', '', '']
  for (const ch of text) {
    const glyph = LETTERS[ch]
    if (!glyph) continue
    for (let i = 0; i < 5; i++) {
      rows[i] += glyph[i] + '0'
    }
  }
  return rows.map((row) => row.replace(/0+$/, ''))
}

/**
 * Split one bitmap row into runs of painted/gap cells. Each run becomes one
 * %c span: painted runs get the row color as background. Runs never contain
 * a newline, so a background never bleeds across line boundaries.
 */
function buildRowSegments(cells: string, color: string): Segment[] {
  const segments: Segment[] = []
  let i = 0
  while (i < cells.length) {
    const painted = cells[i] === '1'
    let j = i
    while (j < cells.length && (cells[j] === '1') === painted) j++
    segments.push({
      text: ' '.repeat(j - i),
      style: painted ? `background:${color};` : '',
    })
    i = j
  }
  return segments
}

/**
 * Console easter egg: colorful "RMS CHAT" block art, printed once after the
 * app finishes initializing. The whole art is one log entry; each block run
 * is a %c span whose background paints the cells, so the shape depends only
 * on the space advance — identical in every monospace font. Works in
 * Chrome/WebView2 and Firefox devtools.
 */
export function printConsoleEasterEgg(): void {
  if (printed) return
  printed = true

  const segments: Segment[] = []
  buildRows('RMS CHAT').forEach((cells, i) => {
    segments.push(...buildRowSegments(cells, ROW_COLORS[i]))
    segments.push({ text: '\n', style: '' })
  })
  segments.push(
    { text: '\n', style: '' },
    {
      text: `RMS Chat v${VERSION_NAME} (${COMMIT_HASH})`,
      style: 'color:#8b949e;font-weight:bold;',
    },
    { text: '\n', style: '' },
    {
      text: 'GitHub: https://github.com/Conflux-Union/rms-chatroom',
      style: 'color:#4dabf7;',
    },
    { text: '\n', style: '' },
    {
      text: 'XR好想谈一段甜甜的恋爱呀👩‍❤️‍👨',
      style: 'color:#ff6b9d;font-style:italic;',
    },
  )

  console.log(
    segments.map((segment) => `%c${segment.text}`).join(''),
    ...segments.map((segment) => segment.style),
  )
}
