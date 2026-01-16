import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5873,
    proxy: {
      '/api': {
        target: 'http://localhost:9222',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:9222',
        ws: true
      }
    }
  },
  build: {
    outDir: 'dist'
  }
})
