import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/api/http.ts', 'src/api/codesentry.ts', 'src/utils/files.ts'],
      thresholds: { 'src/api/http.ts': { lines: 80, functions: 80, statements: 80, branches: 80 } },
    },
  },
})
