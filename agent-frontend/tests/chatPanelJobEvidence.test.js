import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

vi.mock('markstream-vue', () => ({
  default: {
    props: ['content'],
    template: '<div class="markdown-stub">{{ content }}</div>',
  },
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
import { useChatStore } from '../src/stores/chat'

describe('ChatPanel job recommendation evidence', () => {
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
            cityName: '上海',
            jobExperience: '3-5年',
            salaryDesc: '35-50K',
            jobDescription: '负责大模型应用与 RAG 平台建设。',
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
})
