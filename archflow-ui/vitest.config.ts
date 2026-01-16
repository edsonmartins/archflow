import { defineConfig } from 'vitest/config'
import { resolve } from 'node:path'

export default defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/web-component/core/__tests__/setup.ts'],
    include: ['**/*.{test,spec}.{js,ts}'],
    exclude: ['node_modules', 'dist'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      exclude: [
        'node_modules/',
        'src/web-component/core/__tests__/',
        '*.test.ts',
        '*.spec.ts'
      ]
    }
  },
  resolve: {
    alias: {
      '@archflow/component': resolve(__dirname, 'src/web-component')
    }
  }
})
