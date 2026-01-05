import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  define: {
    // Electron production needs absolute API URL
    'import.meta.env.VITE_API_BASE': JSON.stringify('https://preview-chatroom.rms.net.cn'),
    'import.meta.env.VITE_WS_BASE': JSON.stringify('wss://preview-chatroom.rms.net.cn'),
  },
  resolve: {
    alias: {
      '@rms-discord/shared': resolve(__dirname, '../shared/src'),
      // Override shared version.ts with local version.ts
      '../version': resolve(__dirname, 'src/version.ts'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://preview-chatroom.rms.net.cn',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://preview-chatroom.rms.net.cn',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
  base: './',
  publicDir: resolve(__dirname, '../shared/public'),
})
