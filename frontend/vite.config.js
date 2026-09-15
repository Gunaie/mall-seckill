import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发通过 /api 代理到后端（Nginx 或直连 app1:8080）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true
      },
      '/doc.html': {
        target: 'http://localhost:8090',
        changeOrigin: true
      },
      '/v3': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
