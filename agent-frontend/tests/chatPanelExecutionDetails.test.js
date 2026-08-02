import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

vi.mock('markstream-vue', () => ({
  default: {
    props: ['content'],
    template: '<div class="markdown-stub">{{ content }}</div>',
  },
  MarkdownCodeBlockNode: {
    props: ['node', 'showHeader', 'isDark'],
    template:
      '<div class="markdown-code-block-stub" :data-language="node.language" :data-show-header="String(showHeader)" :data-is-dark="String(isDark)"><pre><code><span class="syntax-token">{{ node.code }}</span></code></pre></div>',
  },
  enableKatex: vi.fn(),
  enableMermaid: vi.fn(),
}))

vi.mock('../src/utils/clipboard', () => ({ copyText: vi.fn().mockResolvedValue(true) }))

vi.mock('../src/api/chat', () => ({
  deleteSession: vi.fn(),
  listSessionMessages: vi.fn(),
  listSessions: vi.fn(),
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

import ChatPanel from '../src/components/ChatPanel.vue'
import { useChatStore } from '../src/stores/chat'
import { copyText } from '../src/utils/clipboard'

Object.defineProperty(window.HTMLElement.prototype, 'scrollTo', {
  configurable: true,
  value: vi.fn(),
})

describe('ChatPanel web search progress', () => {
  it('keeps completed process panels immutable while a new flip request is running', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    const completed = [
      { id: 'runtime_understanding', name: 'Runtime 任务理解', status: 'success', detail: '已完成任务理解。' },
      { id: 'recommendation_context', name: '画像与简历已就绪', status: 'success', detail: '上下文准备完成。' },
      { id: 'job_search', name: '岗位搜索完成', status: 'success', detail: '检索到候选岗位。' },
      { id: 'recommendation_quality_gate', name: '画像与简历预筛完成', status: 'success', detail: '5 个岗位达标。' },
    ]
    const current = [
      { id: 'job_flip', name: '换一批', status: 'success', detail: '直接翻到第 2 批岗位。' },
      { id: 'recommendation_quality_gate', name: '画像与简历预筛', status: 'running', detail: '正在验证候选岗位。' },
    ]
    chat.loading = true
    // 模拟上一轮历史同步迟到后污染会话级投影；面板必须只读取所属助手消息。
    chat.toolEvents = [...completed, ...current]
    chat.messages = [
      { id: 'user-search', role: 'user', content: '筛选岗位' },
      { id: 'assistant-search', role: 'assistant', content: '', pending: false, toolEvents: completed, jobCards: [] },
      { id: 'user-flip', role: 'user', content: '换一批' },
      { id: 'assistant-flip', role: 'assistant', content: '', pending: false, toolEvents: current, jobCards: [] },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const panels = wrapper.findAll('.tool-process')

    expect(panels).toHaveLength(2)
    expect(panels[0].get('summary').text()).toContain('已完成 4/4 步')
    expect(panels[0].find('.tool-thinking-step').exists()).toBe(false)
    expect(panels[1].get('summary').text()).toContain('进行中 · 1/2 步')
    expect(panels[1].get('.tool-thinking-step').text()).toContain('画像与简历预筛')
    expect(panels[1].get('.tool-thinking-step').text()).toContain('1/2')
    expect(panels[1].text()).not.toContain('Runtime 任务理解')

    wrapper.unmount()
  })

  it('shows web search while it is running and keeps the completed evidence in the process panel', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    const running = {
      id: 'runtime_web_search',
      name: '联网搜索',
      status: 'running',
      detail: '已进入联网搜索流程，正在准备查询与来源核验。',
      payload: { query: 'OpenAI 最新模型 官方发布', stage: 'query_preparation' },
    }
    chat.loading = true
    chat.toolEvents = [running]
    chat.messages = [
      {
        id: 'assistant-search',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [running],
      },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    expect(wrapper.get('.tool-thinking-step').text()).toContain('联网搜索')
    expect(wrapper.get('.tool-thinking-step').text()).toContain('正在准备查询与来源核验')

    chat.upsertToolEvent(
      {
        id: 'runtime_web_search',
        title: '联网搜索',
        status: 'success',
        summary: '联网搜索已完成，取得 3 个可引用来源。',
        detail: {
          query: 'OpenAI 最新模型 官方发布',
          provider: 'bocha_web',
          rawCount: 5,
          deduplicatedCount: 3,
          sourceCount: 3,
        },
      },
      'assistant-search',
    )
    chat.loading = false
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.tool-thinking-step').exists()).toBe(false)
    expect(wrapper.get('.tool-step-card').text()).toContain('联网搜索已完成，取得 3 个可引用来源')
    await wrapper.get('.tool-detail-toggle').trigger('click')
    expect(wrapper.get('.tool-key-details').text()).toContain('博查 Web Search')
    expect(wrapper.get('.tool-key-details').text()).toContain('5 → 3 个')

    wrapper.unmount()
  })
})

describe('ChatPanel sandbox execution details', () => {
  it('reveals executed source and stdout/stderr only after the user expands the tool detail', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-code',
        role: 'assistant',
        content: '执行完成。',
        pending: false,
        toolEvents: [
          {
            id: 'runtime_sandbox_code_execute',
            name: '沙箱代码执行',
            status: 'success',
            detail: '候选代码已由 agent-sandbox 隔离执行并通过验证。',
            payload: {
              language: 'python',
              sandboxed: true,
              exitCode: 0,
              outputChars: 2,
              code: "value = '<safe>'\nprint(value)",
              codeChars: 3446,
              codeTruncated: false,
              stdout: '<safe>\n',
              stdoutChars: 7,
              stdoutTruncated: false,
              stderr: '',
              stderrChars: 0,
              stderrTruncated: false,
            },
          },
        ],
      },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    expect(wrapper.find('.tool-execution-detail').exists()).toBe(false)
    const toggle = wrapper.get('.tool-detail-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.tool-execution-output-title').text()).toContain('运行输出')
    expect(wrapper.find('.tool-execution-code-icon').exists()).toBe(false)
    expect(wrapper.find('.tool-execution-toolbar svg').exists()).toBe(false)
    expect(wrapper.get('.tool-execution-heading > strong').text()).toBe('执行代码')
    expect(wrapper.get('.tool-execution-meta').text()).toContain('Python')
    expect(wrapper.get('.tool-execution-meta').text()).toContain('3446 字符')
    expect(wrapper.get('.markdown-code-block-stub').attributes('data-language')).toBe('python')
    expect(wrapper.get('.markdown-code-block-stub').attributes('data-show-header')).toBe('false')
    expect(wrapper.get('.markdown-code-block-stub').attributes('data-is-dark')).toBe('false')
    expect(wrapper.get('.syntax-token').text()).toContain("value = '<safe>'")
    expect(wrapper.get('.tool-execution-source code').text()).toContain("value = '<safe>'")
    expect(wrapper.get('.tool-execution-output-body[data-product="stdout"] pre').text()).toBe('<safe>')
    expect(wrapper.find('.tool-execution-source script').exists()).toBe(false)

    const codeFrame = wrapper.get('.tool-execution-code-frame')
    const expandButton = wrapper.get('.tool-execution-expand')
    expect(codeFrame.classes()).toContain('collapsed')
    expect(expandButton.attributes('aria-expanded')).toBe('false')
    expect(expandButton.text()).toBe('展开代码')
    await expandButton.trigger('click')
    expect(codeFrame.classes()).toContain('expanded')
    expect(expandButton.attributes('aria-expanded')).toBe('true')
    expect(expandButton.text()).toBe('收起代码')

    const stdoutTab = wrapper.get('[data-output-tab="stdout"]')
    const stderrTab = wrapper.get('[data-output-tab="stderr"]')
    expect(stdoutTab.attributes('aria-selected')).toBe('true')
    expect(stderrTab.attributes('aria-selected')).toBe('false')
    await stdoutTab.trigger('keydown', { key: 'ArrowRight' })
    expect(stderrTab.attributes('aria-selected')).toBe('true')
    expect(wrapper.get('.tool-execution-output-body[data-product="stderr"]').text()).toBe('无标准错误')
    await stderrTab.trigger('keydown', { key: 'Home' })
    expect(stdoutTab.attributes('aria-selected')).toBe('true')

    const copyButton = wrapper.get('.tool-execution-copy')
    expect(copyButton.text()).toBe('复制代码')
    await copyButton.trigger('click')
    expect(copyText).toHaveBeenCalledWith("value = '<safe>'\nprint(value)")
    expect(copyButton.text()).toBe('已复制')

    chat.messages.push({
      ...chat.messages[0],
      id: 'assistant-code-2',
      toolEvents: chat.messages[0].toolEvents.map((event) => ({ ...event, payload: { ...event.payload } })),
    })
    await wrapper.vm.$nextTick()
    const detailToggles = wrapper.findAll('.tool-detail-toggle')
    expect(detailToggles).toHaveLength(2)
    await detailToggles[1].trigger('click')
    const copyButtons = wrapper.findAll('.tool-execution-copy')
    expect(copyButtons).toHaveLength(2)
    expect(copyButtons[1].text()).toBe('复制代码')

    copyText.mockResolvedValueOnce(false)
    await copyButton.trigger('click')
    expect(copyButton.text()).toBe('复制失败')
    expect(copyButton.classes()).toContain('failed')

    wrapper.unmount()
  })

  it('selects standard error when an in-flight execution later returns an error stream', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-code-error',
        role: 'assistant',
        content: '执行失败。',
        pending: false,
        toolEvents: [
          {
            id: 'runtime_sandbox_code_execute',
            name: '沙箱代码执行',
            status: 'error',
            detail: '沙箱返回了错误输出。',
            payload: {
              language: 'python',
              code: 'import numpy as np',
              codeChars: 18,
              stdout: '准备导入依赖\n',
              stdoutChars: 7,
              stderr: '',
              stderrChars: 0,
            },
          },
        ],
      },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    await wrapper.get('.tool-detail-toggle').trigger('click')

    expect(wrapper.get('[data-output-tab="stdout"]').attributes('aria-selected')).toBe('true')

    chat.messages[0].toolEvents[0].payload.stderr = "ModuleNotFoundError: No module named 'numpy'"
    chat.messages[0].toolEvents[0].payload.stderrChars = 45
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-output-tab="stderr"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('.tool-execution-output-body').attributes('data-product')).toBe('stderr')
    expect(wrapper.get('.tool-execution-output-body').text()).toContain('ModuleNotFoundError')
    expect(wrapper.find('.tool-execution-expand').exists()).toBe(false)

    wrapper.unmount()
  })
})
