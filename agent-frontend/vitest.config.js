import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  test: {
    // DOMPurify 官方支持的 DOM 实现，避免 Happy DOM 与安全净化器的遍历语义不一致。
    environment: 'jsdom',
    include: ['tests/**/*.test.js'],
    setupFiles: ['tests/setup.js'],
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,vue}'],
      reporter: ['text', 'html', 'json', 'json-summary', 'lcov'],
      thresholds: { lines: 80, statements: 80, branches: 70, functions: 60 },
    },
  },
})
