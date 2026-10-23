import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// Dev proxy target. Point DEV_PROXY_TARGET at a local backend to test server
// changes (DEV_PROXY_TARGET=http://localhost:8000 pnpm web:dev); the ws
// target derives from it unless DEV_PROXY_WS_TARGET overrides it.
const proxyTarget = process.env.DEV_PROXY_TARGET || 'https://chatroom.cxu.org.cn'
const proxyWsTarget = process.env.DEV_PROXY_WS_TARGET || proxyTarget.replace(/^http/, 'ws')

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) => tag.startsWith('zhimo-'),
        },
      },
    }),
  ],
  resolve: {
    alias: {
      '@rms-discord/shared': resolve(__dirname, '../../packages/shared/src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
      '/ws': {
        target: proxyWsTarget,
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // lightningcss dedupes paired backdrop-filter/-webkit-backdrop-filter
    // declarations down to one (issue #537), and Chrome 150 dropped the
    // -webkit- alias, so whichever survives breaks one browser. Declare
    // Safari in cssTarget so lightningcss emits both prefixes from the
    // standard property alone.
    cssTarget: ['chrome100', 'safari15.6'],
  },
  publicDir: resolve(__dirname, '../../packages/shared/public'),
})
