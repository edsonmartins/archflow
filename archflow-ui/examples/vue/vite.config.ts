import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@archflow/component': resolve(__dirname, '../../src/web-component')
    }
  },
  server: {
    port: 5175
  }
})
