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
  define: {
    // Only set absolute URLs for production build
    // In dev mode, use empty string to enable vite proxy
    'import.meta.env.VITE_API_BASE': JSON.stringify(process.env.NODE_ENV === 'production' ? 'https://chatroom.rms.net.cn' : ''),
    'import.meta.env.VITE_WS_BASE': JSON.stringify(process.env.NODE_ENV === 'production' ? 'wss://chatroom.rms.net.cn' : ''),
  },
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
        secure: false,
      },
      '/ws': {
        target: 'wss://chatroom.rms.net.cn',
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
  base: './',
  publicDir: resolve(__dirname, '../shared/public'),
})
