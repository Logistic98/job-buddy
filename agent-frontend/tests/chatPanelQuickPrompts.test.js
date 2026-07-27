import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

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

describe('ChatPanel quick prompts', () => {
  it('shows complete guidance and fills the composer without sending', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })

    expect(wrapper.get('.quick-prompts-guide').text()).toBe(
      '您好，我会结合您的求职画像、简历、收藏岗位及求职进展，动态检索 Boss 直聘数据，为您提供岗位推荐、简历分析、面试辅导、技术答疑等服务。例如，您可以这样问：',
    )
    expect(wrapper.find('.quick-prompts-label').exists()).toBe(false)
    expect(wrapper.find('.panel-head').exists()).toBe(false)

    const questions = wrapper.findAll('.quick-prompts-list button')
    expect(questions).toHaveLength(3)
    expect(wrapper.find('.quick-prompts-list svg').exists()).toBe(false)
    expect(questions.map((button) => button.text())).toEqual([
      '筛选上海 40-50K 大模型应用开发岗位',
      '分析当前简历与目标岗位的匹配度',
      '生成大模型应用开发面试准备清单',
    ])

    await questions[1].trigger('click')

    expect(wrapper.get('textarea').element.value).toBe('分析当前简历与目标岗位的匹配度')
    expect(wrapper.emitted('ask')).toBeUndefined()

    wrapper.unmount()
  })
})
