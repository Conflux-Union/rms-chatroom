import { readFileSync } from 'node:fs';

const files = [
  'packages/shared/src/views/Login.vue',
  'packages/shared/src/views/Main.vue',
  'packages/shared/src/components/ServerList.vue',
  'packages/shared/src/components/ChannelList.vue',
  'packages/shared/src/components/ChatArea.vue',
];

const forbidden = [
  { pattern: /backdrop-filter\s*:/, reason: 'glass blur does not belong in the paper-and-ink shell' },
  { pattern: /--blur-strength/, reason: 'legacy blur token should not drive the core shell' },
  { pattern: /--color-gradient-primary|--color-gradient-secondary|linear-gradient\(/, reason: 'core shell should use ink/seal fills, not gradients' },
  { pattern: /box-shadow:\s*var\(--shadow-glow\)/, reason: 'glow shadows break the printed-paper hierarchy' },
  { pattern: /border-radius:\s*50%/, reason: 'core navigation should avoid Discord-style circular pills' },
  { pattern: /rgba\(255,\s*255,\s*255/, reason: 'white glass overlays break the theme tokens' },
  { pattern: /rgba\(255,\s*166,\s*133/, reason: 'ad hoc peach accents bypass zhimo tokens' },
];

const failures = [];

for (const file of files) {
  const text = readFileSync(file, 'utf8');
  for (const rule of forbidden) {
    if (rule.pattern.test(text)) {
      failures.push(`${file}: ${rule.reason}`);
    }
  }
}

const webApp = readFileSync('packages/web/src/App.vue', 'utf8');
const electronApp = readFileSync('packages/electron-renderer/src/App.vue', 'utf8');
if (!/<zhimo-ink-paper\b[^>]*\bimage=/.test(webApp)) {
  failures.push('packages/web/src/App.vue: ink paper must reveal a background image');
}
if (!/<zhimo-ink-paper\b[^>]*\bimage=/.test(electronApp)) {
  failures.push('packages/electron-renderer/src/App.vue: ink paper must reveal a background image');
}

if (failures.length > 0) {
  console.error('ZhiMo theme contract failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}
