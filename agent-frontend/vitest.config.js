import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  test: {
    // DOMPurify 官方支持的 DOM 实现，避免 Happy DOM 与安全净化器的遍历语义不一致。
    environment: 'jsdom',
    include: ['tests/**/*.test.js'],
  },
})
