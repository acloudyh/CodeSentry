import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    headers: {
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy':
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' wss:; frame-src 'self' about:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    },
    https:
      command === 'serve'
        ? {
            key: readFileSync(
              resolve(process.env.CODESENTRY_TLS_DIR ?? '../.codesentry/tls', 'server.key'),
            ),
            cert: readFileSync(
              resolve(process.env.CODESENTRY_TLS_DIR ?? '../.codesentry/tls', 'server.crt'),
            ),
          }
        : undefined,
    proxy: {
      '/api': {
        target: process.env.CODESENTRY_API_URL ?? 'http://127.0.0.1:18080',
        xfwd: true,
        headers: { 'X-Forwarded-Proto': 'https' },
      },
      '/actuator': 'http://127.0.0.1:18080',
    },
  },
}))
