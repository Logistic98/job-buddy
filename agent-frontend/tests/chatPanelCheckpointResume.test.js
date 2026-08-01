import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('markstream-vue', () => ({
  default: {
    props: ['content'],
    template: '<div class="markdown-stub">{{ content }}</div>',
  },
  enableKatex: vi.fn(),
  enableMermaid: vi.fn(),
}))

vi.mock('../src/api/chat', () => ({
  deleteSession: vi.fn(),
  listSessionMessages: vi.fn().mockResolvedValue([]),
  listSessions: vi.fn().mockResolvedValue([]),
  streamChat: vi.fn(),
}))

vi.mock('../src/api/boss', () => ({ getBossLoginStatus: vi.fn() }))

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

import { streamChat } from '../src/api/chat'
import ChatPanel from '../src/components/ChatPanel.vue'
import { useChatStore } from '../src/stores/chat'

describe('ChatPanel checkpoint resume', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.HTMLElement.prototype.scrollTo = vi.fn()
  })

  it('continues the failed run without appending the user turn again', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.onEvent?.('tool_status', {
        id: 'runtime_managed',
        name: 'Runtime 断点续跑',
        status: 'success',
        detail: '恢复完成',
        payload: { runId: 'run-resumed', resumedFromRunId: 'run-failed' },
      })
      handlers.message?.({ content: '已从断点完成。' })
      handlers.done?.({ ok: true })
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.sessionId = 'session-1'
    chat.messages = [
      {
        id: 'turn-1',
        turnId: 'turn-1',
        role: 'user',
        content: '执行复杂任务',
        attachments: [],
      },
      {
        id: 'assistant-failed',
        role: 'assistant',
        content: 'Runtime 流式中断。',
        toolEvents: [
          {
            id: 'runtime_managed',
            name: 'Runtime 托管任务中断',
            status: 'error',
            detail: '连接中断',
            payload: { runId: 'run-failed', resumable: true },
          },
        ],
      },
    ]
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    await wrapper.get('.checkpoint-resume-action button').trigger('click')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledWith(
      expect.objectContaining({
        message: '执行复杂任务',
        sessionId: 'session-1',
        turnId: 'turn-1',
        resumeRunId: 'run-failed',
      }),
      expect.any(Object),
    )
    expect(chat.messages.filter((item) => item.role === 'user')).toHaveLength(1)
    expect(chat.messages.filter((item) => item.role === 'assistant')).toHaveLength(2)
    expect(wrapper.find('.checkpoint-resume-action').exists()).toBe(false)

    wrapper.unmount()
  })

  it('treats an exact continue message as checkpoint resume instead of a new task', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.message?.({ content: '已从规划断点完成。' })
      handlers.done?.({ ok: true })
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.sessionId = 'session-continue'
    chat.messages = [
      {
        id: 'turn-original',
        turnId: 'turn-original',
        role: 'user',
        content: '输出 Mermaid、LaTeX 和 Python 示例',
        attachments: [],
      },
      {
        id: 'assistant-invalid-plan',
        role: 'assistant',
        content: '计划依赖校验失败。',
        toolEvents: [
          {
            id: 'runtime_managed',
            name: 'Runtime 托管任务中断',
            status: 'error',
            detail: 'invalid_plan_dependency',
            payload: { runId: 'run-invalid-plan', resumable: true },
          },
        ],
      },
    ]
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    await wrapper.get('textarea').setValue('继续')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledWith(
      expect.objectContaining({
        message: '输出 Mermaid、LaTeX 和 Python 示例',
        sessionId: 'session-continue',
        turnId: 'turn-original',
        resumeRunId: 'run-invalid-plan',
      }),
      expect.any(Object),
    )
    expect(chat.messages.filter((item) => item.role === 'user')).toHaveLength(1)
    expect(chat.messages.some((item) => item.role === 'user' && item.content === '继续')).toBe(false)

    wrapper.unmount()
  })

  it('keeps a longer continue prompt as a normal new message', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.message?.({ content: '继续分析完成。' })
      handlers.done?.({ ok: true })
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.sessionId = 'session-normal-continue'
    chat.messages = [
      { id: 'turn-old', role: 'user', content: '旧任务', attachments: [] },
      {
        id: 'assistant-old',
        role: 'assistant',
        content: '旧任务失败。',
        toolEvents: [
          {
            id: 'runtime_managed',
            status: 'error',
            payload: { runId: 'run-old', resumable: true },
          },
        ],
      },
    ]
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    await wrapper.get('textarea').setValue('继续分析原因')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledWith(
      expect.objectContaining({
        message: '继续分析原因',
        resumeRunId: undefined,
      }),
      expect.any(Object),
    )
    expect(chat.messages.filter((item) => item.role === 'user')).toHaveLength(2)

    wrapper.unmount()
  })
})
