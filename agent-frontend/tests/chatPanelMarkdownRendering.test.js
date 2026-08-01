import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeAll, describe, expect, it, vi } from 'vitest'

vi.mock('../src/api/chat', () => ({
  deleteChatAttachment: vi.fn(async () => ({})),
  deleteSession: vi.fn(async () => ({})),
  listSessionMessages: vi.fn(async () => []),
  listSessions: vi.fn(async () => []),
  streamChat: vi.fn(async () => {}),
  uploadChatAttachment: vi.fn(async () => ({})),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: vi.fn(async () => ({ status: 'logged_in' })),
}))

vi.mock('../src/api/jobs', () => ({
  cancelAnalysisTask: vi.fn(),
  deleteFavoriteJob: vi.fn(),
  fetchJobDetail: vi.fn(),
  getAnalysisTask: vi.fn(),
  latestFavoriteAnalysisTask: vi.fn(),
  listFavoriteJobs: vi.fn(),
  saveFavoriteJob: vi.fn(),
  startFavoriteAnalysisTask: vi.fn(),
  streamAnalysisTask: vi.fn(),
}))

import ChatPanel from '../src/components/ChatPanel.vue'
import { useChatStore } from '../src/stores/chat'
import { registerAssistantMarkdownFeatures } from '../src/utils/markdownFeatures'

Object.defineProperty(globalThis.HTMLElement.prototype, 'scrollTo', {
  configurable: true,
  value: vi.fn(),
})

beforeAll(() => registerAssistantMarkdownFeatures())

describe('ChatPanel assistant Markdown rendering', () => {
  it('renders common model Mermaid and LaTeX output through the real chat path', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.loading = false
    chat.messages = [
      {
        id: 'assistant-markdown',
        role: 'assistant',
        pending: false,
        content: [
          '## Mermaid 流程图示例',
          '',
          '```mermaid',
          'graph TD',
          '  A[开始] --> B{条件判断}',
          '  B -->|是| C[执行操作]',
          '  B -->|否| D[执行另操作]',
          '  C --> E[结束]',
          '  D --> E',
          '```',
          '',
          '当 \\( a \\neq 0 \\) 时，方程 \\( ax^2 + bx + c = 0 \\) 的解为：',
          '',
          '$$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}$$',
        ].join('\n'),
      },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] }, attachTo: document.body })
    await flushPromises()

    await expect
      .poll(
        () => ({
          mermaid: wrapper.find('[data-mermaid-wrapper] svg').exists(),
          blockMath: wrapper.findAll('.katex').length,
          displayMath: wrapper.findAll('.katex-display').length,
          rawMermaidText: wrapper.findAll('.assistant-plain-text').some((node) => node.text().includes('graph TD')),
        }),
        { timeout: 5000 },
      )
      .toEqual({ mermaid: true, blockMath: 1, displayMath: 1, rawMermaidText: false })

    wrapper.unmount()
  }, 10000)
})
