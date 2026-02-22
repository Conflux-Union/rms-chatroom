import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
export default defineConfig({
    plugins: [vue()],
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
    },
    publicDir: resolve(__dirname, '../shared/public'),
});
