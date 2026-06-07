import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Force a single instance of i18next/react-i18next across the module graph.
  // After a dep re-optimization the optimizer could otherwise bundle a second
  // i18next instance, leaving react-i18next with an uninitialized store (which
  // renders raw keys like "workflows.title").
  resolve: {
    dedupe: ['i18next', 'react-i18next'],
  },
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
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:16080',
        changeOrigin: true,
        ws: true
      },
      '/archflow': {
        target: 'http://localhost:16080',
        changeOrigin: true,
        ws: true
      },
      '/ag-ui': {
        target: 'http://localhost:16080',
        changeOrigin: true
      }
    }
  }
})
