import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
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
  base: "./",
})
