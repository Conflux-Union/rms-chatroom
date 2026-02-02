import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  define: {
    // Only set absolute URLs for production build
    // In dev mode, use empty string to enable vite proxy
    'import.meta.env.VITE_API_BASE': JSON.stringify(process.env.NODE_ENV === 'production' ? 'https://preview-chatroom.rms.net.cn' : ''),
    'import.meta.env.VITE_WS_BASE': JSON.stringify(process.env.NODE_ENV === 'production' ? 'wss://preview-chatroom.rms.net.cn' : ''),
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
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyReq', (proxyReq, req, res) => {
            const target = options.target || '';
            const url = req.url || '';
            console.log('[Proxy]', req.method, url, '->', target + url);
          });
          proxy.on('proxyRes', (proxyRes, req, res) => {
            console.log('[Proxy Response]', proxyRes.statusCode, req.url || '');
          });
        },
      },
      '/ws': {
        target: 'wss://preview-chatroom.rms.net.cn',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
  base: './',
  publicDir: resolve(__dirname, '../shared/public'),
})
