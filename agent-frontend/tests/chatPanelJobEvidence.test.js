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
  listSessionMessages: vi.fn(),
  listSessions: vi.fn(),
  streamChat: vi.fn(),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: vi.fn(),
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
import { fetchJobDetail } from '../src/api/jobs'
import { useChatStore } from '../src/stores/chat'

describe('ChatPanel job recommendation evidence', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps evidence collapsed and opens it below the actions on demand', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-cards',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-1',
            jobName: '大模型应用开发组长',
            brandName: '示例科技',
            cityName: '杭州',
            jobExperience: '3-5年',
            salaryDesc: '35-50K',
            jobDescription: '负责大模型应用与 RAG 平台建设，包括 Java 微服务、效果评估和线上稳定性治理。',
            recommendationReasons: ['简历具备 RAG 与 Agent 项目经验'],
            recommendationWarnings: ['列表证据有限，需查看完整职位描述'],
            matchScore: 75,
            matchConfidence: 'medium',
            matchRecommendation: '可尝试',
            recommendationEvidenceLevel: 'list_metadata',
          },
        ],
      },
    ]

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    expect(wrapper.get('.msg.assistant').classes()).toContain('has-job-cards')
    const card = wrapper.get('.chat-job-card')
    const actions = card.get('.chat-job-actions')
    const actionLabels = actions.findAll('button').map((button) => button.text())

    expect(actionLabels).toEqual(['查看职位描述', '推荐依据', '分析此岗位', '收藏'])
    expect(card.find('.chat-job-recommendation-details').exists()).toBe(false)
    expect(card.text()).not.toContain('75 分')
    expect(card.text()).not.toContain('置信度中')
    expect(card.text()).not.toContain('可尝试')
    expect(actions.findAll('button')[1].attributes('aria-expanded')).toBe('false')

    await actions.findAll('button')[1].trigger('click')

    const details = card.get('.chat-job-recommendation-details')
    expect(actions.findAll('button')[1].text()).toBe('收起推荐依据')
    expect(actions.findAll('button')[1].attributes('aria-expanded')).toBe('true')
    expect(details.text()).toContain('匹配分75 分')
    expect(details.text()).toContain('置信度中')
    expect(details.text()).toContain('投递建议可尝试')
    expect(details.text()).toContain('证据范围岗位列表信息')
    expect(details.text()).toContain('简历具备 RAG 与 Agent 项目经验')
    expect(details.text()).toContain('列表证据有限，需查看完整职位描述')
    expect(actions.element.nextElementSibling).toBe(details.element)

    wrapper.unmount()
  })

  it('expands and collapses an existing job description without requesting job detail', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-with-jd',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-2',
            jobName: '大模型应用开发工程师',
            brandName: '示例科技',
            cityName: '上海',
            salaryDesc: '40-50K',
            jobDescription: '负责大模型应用、RAG 平台、Java 微服务和线上稳定性建设。',
          },
        ],
      },
    ]
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const buttons = wrapper.get('.chat-job-actions').findAll('button')
    const descriptionButton = buttons.find((button) => button.text() === '查看职位描述')
    const analyzeButton = buttons.find((button) => button.text() === '分析此岗位')

    expect(descriptionButton.attributes('disabled')).toBeUndefined()
    expect(analyzeButton.attributes('disabled')).toBeUndefined()

    await descriptionButton.trigger('click')

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(wrapper.get('.chat-job-jd-full').text()).toContain('RAG 平台')
    expect(descriptionButton.text()).toBe('收起职位描述')
    expect(analyzeButton.text()).toBe('分析此岗位')
    expect(analyzeButton.attributes('disabled')).toBeUndefined()

    await descriptionButton.trigger('click')

    expect(wrapper.find('.chat-job-jd-full').exists()).toBe(false)
    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('analyzes an existing job description without requesting detail and sends a compact snapshot', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-analysis',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-analysis',
            jobName: '大模型应用开发工程师',
            brandName: '示例科技',
            cityName: '上海',
            salaryDesc: '40-50K',
            brandIndustry: '人工智能',
            skills: ['RAG', 'Java'],
            jobDescription: '负责大模型应用、RAG 平台、Java 微服务和线上稳定性建设。',
            rawResponse: { oversizedUpstreamPayload: '不应进入分析请求' },
          },
        ],
      },
    ]
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    const analyzeButton = wrapper
      .get('.chat-job-actions')
      .findAll('button')
      .find((button) => button.text() === '分析此岗位')
    await analyzeButton.trigger('click')
    await flushPromises()

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0][2].selectedJob).toEqual({
      securityId: 'job-analysis',
      jobName: '大模型应用开发工程师',
      company: '示例科技',
      salary: '40-50K',
      city: '上海',
      industry: '人工智能',
      jobDescription: '负责大模型应用、RAG 平台、Java 微服务和线上稳定性建设。',
      skills: ['RAG', 'Java', '人工智能'],
    })
    wrapper.unmount()
  })

  it('does not show analysis state for a request running in another conversation', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.sessionId = 'visible-session'
    chat.messages = [
      {
        id: 'assistant-visible-job',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'visible-job',
            jobName: '大模型应用开发工程师',
            jobDescription: '负责大模型应用、RAG 平台、Java 微服务和线上稳定性建设。',
          },
        ],
      },
    ]
    chat.activeSessionRequests = {
      'background-session': {
        key: 'background-request',
        selectedJobKey: 'visible-job',
      },
    }
    // 模拟旧页面残留的会话级 loading 投影；岗位按钮必须按当前会话和岗位重新判定。
    chat.loading = true

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const analyzeButton = wrapper
      .get('.chat-job-actions')
      .findAll('button')
      .find((button) => button.text().includes('分析'))

    expect(analyzeButton.text()).toBe('分析此岗位')
    wrapper.unmount()
  })

  it('shows analysis state only on the selected job in the current conversation', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.sessionId = 'visible-session'
    chat.messages = [
      {
        id: 'assistant-visible-jobs',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'selected-job',
            jobName: '大模型应用开发工程师',
            jobDescription: '负责大模型应用、RAG 平台、Java 微服务和线上稳定性建设。',
          },
          {
            securityId: 'other-job',
            jobName: '大模型训练平台工程师',
            jobDescription: '负责大模型训练平台、分布式任务编排和训练稳定性建设。',
          },
        ],
      },
    ]
    chat.activeSessionRequests = {
      'visible-session': {
        key: 'selected-job-request',
        selectedJobKey: 'selected-job',
      },
    }
    chat.applyCurrentRequestProjection('visible-session')

    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const cards = wrapper.findAll('.chat-job-card')
    const selectedAnalyzeButton = cards[0]
      .get('.chat-job-actions')
      .findAll('button')
      .find((button) => button.text().includes('分析'))
    const otherAnalyzeButton = cards[1]
      .get('.chat-job-actions')
      .findAll('button')
      .find((button) => button.text().includes('分析'))

    expect(selectedAnalyzeButton.text()).toBe('分析中')
    expect(otherAnalyzeButton.text()).toBe('分析此岗位')
    wrapper.unmount()
  })

  it('reports a missing job description without requesting detail or sending analysis', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-without-jd',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-missing-jd',
            jobName: 'Agent 平台开发工程师',
            brandName: '示例科技',
          },
        ],
      },
    ]
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const buttons = wrapper.get('.chat-job-actions').findAll('button')
    const descriptionButton = buttons.find((button) => button.text() === '加载职位描述')
    const analyzeButton = buttons.find((button) => button.text() === '分析此岗位')

    expect(descriptionButton.attributes('disabled')).toBeUndefined()
    expect(analyzeButton.attributes('disabled')).toBeUndefined()

    await descriptionButton.trigger('click')
    await flushPromises()

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(wrapper.get('.chat-job-jd-error').text()).toContain('当前岗位未包含完整职位描述')
    expect(descriptionButton.text()).toBe('加载职位描述')
    expect(analyzeButton.text()).toBe('分析此岗位')
    expect(analyzeButton.attributes('disabled')).toBeUndefined()

    await analyzeButton.trigger('click')
    await flushPromises()

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
    expect(wrapper.get('.chat-job-jd-error').text()).toContain('当前岗位未包含完整职位描述')
    expect(descriptionButton.attributes('disabled')).toBeUndefined()
    expect(analyzeButton.text()).toBe('分析此岗位')
    wrapper.unmount()
  })

  it('does not reuse an expanded state for a later card with the same id but no job description', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-complete-job',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'repeated-job',
            jobName: 'Agent 平台开发工程师',
            jobDescription: '负责 Agent 平台研发与稳定性建设，包括工具编排、效果评估、Java 微服务与线上治理。',
          },
        ],
      },
      {
        id: 'assistant-incomplete-job',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'repeated-job',
            jobName: 'Agent 平台开发工程师',
          },
        ],
      },
    ]
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const cards = wrapper.findAll('.chat-job-card')
    const completeDescriptionButton = cards[0]
      .get('.chat-job-actions')
      .findAll('button')
      .find((button) => button.text() === '查看职位描述')

    await completeDescriptionButton.trigger('click')

    const incompleteDescriptionButton = cards[1].get('.chat-job-actions').findAll('button')[0]
    expect(incompleteDescriptionButton.text()).toBe('加载职位描述')

    await incompleteDescriptionButton.trigger('click')

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(cards[1].get('.chat-job-jd-error').text()).toContain('当前岗位未包含完整职位描述')
    wrapper.unmount()
  })

  it('does not analyze a short list summary as a complete job description', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-with-summary-only',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-summary-only',
            jobName: '大模型应用开发工程师',
            brandName: '示例科技',
            description: '负责大模型应用开发。',
          },
        ],
      },
    ]
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const buttons = wrapper.get('.chat-job-actions').findAll('button')
    const descriptionButton = buttons[0]
    const analyzeButton = buttons.find((button) => button.text() === '分析此岗位')

    expect(descriptionButton.text()).toBe('加载职位描述')
    await descriptionButton.trigger('click')

    expect(wrapper.find('.chat-job-jd-full').exists()).toBe(false)
    expect(wrapper.get('.chat-job-jd-error').text()).toContain('完整职位描述')

    await analyzeButton.trigger('click')
    await flushPromises()

    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
    expect(wrapper.get('.chat-job-jd-error').text()).toContain('完整职位描述')
    wrapper.unmount()
  })

  it('sends more jobs as a new flip request without replaying the previous assistant message', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'assistant-job-cards',
        role: 'assistant',
        content: '',
        pending: false,
        toolEvents: [],
        jobCards: [
          {
            securityId: 'job-1',
            jobName: '大模型应用开发工程师',
            brandName: '示例科技',
            cityName: '上海',
            salaryDesc: '40-50K',
            jobDescription: '负责大模型应用开发。',
          },
        ],
      },
    ]
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const wrapper = mount(ChatPanel, { props: { resumeId: 'resume-1' }, global: { plugins: [pinia] } })

    await wrapper.get('.chat-job-more button').trigger('click')

    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith('换一批', 'resume-1', { flipJobs: true })
    expect(send.mock.calls[0][2]).not.toHaveProperty('replay')
    expect(send.mock.calls[0][2]).not.toHaveProperty('assistantId')
    wrapper.unmount()
  })
})
