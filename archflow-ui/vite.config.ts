import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
// Dois artefatos de build:
//   `vite build`                  → SPA completo (index.html + rotas) em dist-app/
//                                   — é isso que o Dockerfile serve como estático
//   `vite build --mode component` → lib do web-component <archflow-designer> em dist/
//                                   — é isso que o package.json publica no npm
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // Force a single instance of i18next/react-i18next across the module graph.
  // After a dep re-optimization the optimizer could otherwise bundle a second
  // i18next instance, leaving react-i18next with an uninitialized store (which
  // renders raw keys like "workflows.title").
  resolve: {
    dedupe: ['i18next', 'react-i18next'],
  },
  build: mode === 'component'
    ? {
        lib: {
          entry: resolve(fileURLToPath(new URL('.', import.meta.url)), 'src/web-component/index.ts'),
          name: 'ArchflowDesigner',
          fileName: 'archflow-designer',
          formats: ['es', 'umd'] as ('es' | 'umd')[]
        },
        rollupOptions: {
          external: [],
          output: {
            globals: {},
            exports: 'named' as const
          }
        },
        cssCodeSplit: false
      }
    : {
        outDir: 'dist-app'
      },
  define: {
    __ARCHFLOW_VERSION__: JSON.stringify('1.0.0-beta.1'),
    // No modo lib o vite NÃO substitui `process.env.*` (deixa para quem consome a lib decidir).
    // Mas este bundle também é servido como está, direto ao navegador, pelo trecho de
    // Publicar › Incorporar (RCHFLW-87) — e o React lê `process.env.NODE_ENV` nos entry points
    // CJS: sem a troca, o import lança "process is not defined" e o <archflow-designer> nunca é
    // registrado. No SPA o vite já faz essa troca sozinho; por isso só no modo component.
    ...(mode === 'component' ? { 'process.env.NODE_ENV': JSON.stringify('production') } : {}),
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
}))
