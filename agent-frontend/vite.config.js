import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

function packageNameFromId(id) {
  const marker = 'node_modules/'
  const index = id.lastIndexOf(marker)
  if (index < 0) return 'misc'
  const segments = id.slice(index + marker.length).split('/')
  if (segments[0]?.startsWith('@')) return `${segments[0].slice(1)}-${segments[1] || 'pkg'}`
  return segments[0] || 'misc'
}

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, repositoryRoot, '')

  return {
    envDir: repositoryRoot,
    plugins: [vue()],
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('/@vue/devtools-api/')) return 'vendor-vue'
            if (id.includes('/vue') || id.includes('/pinia') || id.includes('/vue-router')) return 'vendor-vue'
            if (id.includes('/markstream-vue')) return 'vendor-markdown'
            if (id.includes('/jspdf')) return 'vendor-jspdf'
            if (id.includes('/html2canvas')) return 'vendor-html2canvas'
            return `vendor-${packageNameFromId(id)}`
          },
        },
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
