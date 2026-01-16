import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    lib: {
      entry: resolve(fileURLToPath(new URL('.', import.meta.url)), 'src/web-component/index.ts'),
      name: 'ArchflowDesigner',
      fileName: 'archflow-designer',
      formats: ['es', 'umd']
    },
    rollupOptions: {
      external: [],
      output: {
        globals: {},
        exports: 'named'
      }
    },
    cssCodeSplit: false
  },
  define: {
    __ARCHFLOW_VERSION__: JSON.stringify('1.0.0-beta.1')
  }
})
