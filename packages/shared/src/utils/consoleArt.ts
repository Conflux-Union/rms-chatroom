import { VERSION_NAME, COMMIT_HASH } from '../version'

// 5x5 block-letter bitmaps, one entry per letter
const LETTERS: Record<string, string[]> = {
  R: ['████ ', '█   █', '████ ', '█  █ ', '█   █'],
  M: ['█   █', '██ ██', '█ █ █', '█   █', '█   █'],
  S: [' ████', '█    ', ' ███ ', '    █', '████ '],
  C: [' ████', '█    ', '█    ', '█    ', ' ████'],
  H: ['█   █', '█   █', '█████', '█   █', '█   █'],
  A: [' ███ ', '█   █', '█████', '█   █', '█   █'],
  T: ['█████', '  █  ', '  █  ', '  █  ', '  █  '],
  ' ': ['  ', '  ', '  ', '  ', '  '],
}

// One color per art row: pink -> orange -> yellow -> green -> blue
const ROW_COLORS = ['#ff6b9d', '#ffa94d', '#ffd43b', '#69db7c', '#4dabf7']

let printed = false

function buildRows(text: string): string[] {
  const rows = ['', '', '', '', '']
  for (const ch of text) {
    const glyph = LETTERS[ch]
    if (!glyph) continue
    for (let i = 0; i < 5; i++) {
      rows[i] += glyph[i] + ' '
    }
  }
  return rows.map((row) => row.trimEnd())
}

/**
 * Console easter egg: colorful "RMS CHAT" block art, printed once
 * after the app finishes initializing. The whole art is one log entry;
 * each line gets its own color via a per-line %c span. Works in
 * Chrome/WebView2 and Firefox devtools.
 */
export function printConsoleEasterEgg(): void {
  if (printed) return
  printed = true

  const lines: Array<{ text: string; style: string }> = [
    ...buildRows('RMS CHAT').map((row, i) => ({
      text: row,
      style: `color:${ROW_COLORS[i]};font-weight:bold;`,
    })),
    { text: '', style: '' },
    {
      text: `RMS Chat v${VERSION_NAME} (${COMMIT_HASH})`,
      style: 'color:#8b949e;font-weight:bold;',
    },
    {
      text: 'XR好想谈一段甜甜的恋爱呀👩‍❤️‍👨',
      style: 'color:#ff6b9d;font-style:italic;',
    },
  ]

  console.log(
    lines.map((line) => `%c${line.text}`).join('\n'),
    ...lines.map((line) => line.style),
  )
}
