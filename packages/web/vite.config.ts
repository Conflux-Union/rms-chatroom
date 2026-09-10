import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

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
      '@rms-discord/shared': resolve(__dirname, '../shared/src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://chatroom.rms.net.cn',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8000',
        ws: true,
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
  publicDir: resolve(__dirname, '../shared/public'),
})
