import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/boss', () => ({
  cancelBossLogin: vi.fn(async () => ({})),
  getBossLoginQr: vi.fn(async () => ({ status: 'qr_ready' })),
  getBossLoginStatus: vi.fn(async () => ({ ok: false, authenticated: false, status: 'auth_required' })),
}))

vi.mock('../src/api/jobs', () => ({
  listFavoriteJobs: vi.fn(async () => []),
  listBossFavoriteJobs: vi.fn(async () => ({ jobs: [], page: 1, hasMore: false })),
  importBossFavoriteJobs: vi.fn(async () => ({ favorites: [], items: [] })),
  saveFavoriteJob: vi.fn(async () => []),
  deleteFavoriteJob: vi.fn(async () => []),
  analyzeFavoriteJob: vi.fn(async () => ({})),
  startFavoriteAnalysisTask: vi.fn(async () => ({
    taskId: 'task-1',
    resourceKey: 'favorite-1',
    status: 'running',
    stage: 'analyzing',
    message: '正在生成报告',
    result: {},
  })),
  latestFavoriteAnalysisTask: vi.fn(async () => null),
  getAnalysisTask: vi.fn(async () => ({
    taskId: 'task-1',
    resourceKey: 'favorite-1',
    status: 'running',
    stage: 'analyzing',
    result: {},
  })),
  streamAnalysisTask: vi.fn(() => new Promise(() => {})),
  fetchJobDetail: vi.fn(async () => ({})),
}))

import JobCardList from '../src/components/JobCardList.vue'
import { deleteFavoriteJob } from '../src/api/jobs'
import { useJobStore } from '../src/stores/job'

function mountFavorites(overrides = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const job = useJobStore()
  job.favorites = [
    {
      favoriteKey: 'favorite-1',
      securityId: 'security-1',
      jobName: 'Java 大模型应用开发工程师',
      brandName: '示例科技',
      cityName: '上海',
      salaryDesc: '40-50K',
      jobDescription: '负责基于 Java 的大模型应用系统设计与开发。',
      ...overrides,
    },
  ]
  return mount(JobCardList, {
    props: { mode: 'favorites' },
    global: { plugins: [pinia] },
  })
}

describe('JobCardList favorites interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the same page-level heading structure as other system modules', () => {
    const wrapper = mountFavorites()
    const header = wrapper.get('.favorite-jobs-header')

    expect(wrapper.get('.favorite-jobs-panel').exists()).toBe(true)
    expect(header.get('.eyebrow').text()).toBe('Favorite Jobs')
    expect(header.get('h1').text()).toBe('岗位收藏')
    expect(header.text()).toContain('这里只展示你手动标记收藏的岗位')
    expect(header.get('.favorite-jobs-count').text()).toContain('共 1 个收藏')
    expect(header.find('h2').exists()).toBe(false)
  })

  it('opens the Boss import dialog immediately from the header action', async () => {
    const wrapper = mountFavorites()

    await wrapper.get('.boss-favorite-import-entry').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.boss-favorite-import-modal').exists()).toBe(true)
  })

  it('never renders stale workspace matching as a favorite analysis', async () => {
    const wrapper = mountFavorites()
    const job = useJobStore()
    job.match = {
      matches: [
        {
          id: 'another-job',
          recommendation: '证据不足',
          reasoning: '这是岗位工作台中其他岗位的旧分析结果',
        },
      ],
    }
    await flushPromises()

    expect(wrapper.find('.match-box').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('证据不足')
    expect(wrapper.text()).not.toContain('其他岗位的旧分析结果')
    expect(wrapper.text()).toContain('分析岗位')
  })

  it('uses a compact single-line search field without a standalone label', () => {
    const wrapper = mountFavorites()
    const search = wrapper.find('.job-search input[type="search"]')

    expect(search.exists()).toBe(true)
    expect(search.attributes('aria-label')).toContain('搜索已收藏岗位')
    expect(wrapper.find('.job-search > span').exists()).toBe(false)
  })

  it('places the job description entry in the card action area', async () => {
    const wrapper = mountFavorites()
    const action = wrapper.find('.job-card-actions .job-jd-btn')

    expect(action.exists()).toBe(true)
    expect(action.text()).toBe('职位描述')
    expect(wrapper.find('.job-card-main .job-jd-btn').exists()).toBe(false)

    await action.trigger('click')

    expect(wrapper.find('.job-jd-full').text()).toContain('负责基于 Java 的大模型应用系统设计与开发')
    expect(action.text()).toBe('收起描述')
  })

  it('renders a decision-oriented report with dimensions, evidence and actions', async () => {
    const wrapper = mountFavorites({
      analyzedAt: '2026-07-21T04:49:00Z',
      analysis: {
        resumeName: '高级研发工程师.pdf',
        match: {
          score: 82,
          score_confidence: 'high',
          recommendation: '推荐',
          reasoning: '核心技术栈和项目交付经验与岗位高度匹配，建议优先投递。主要风险是缺少行业侧量化成果。',
          dimensions: {
            technical_skill: { score: 90, evidence: 'Java 与 Spring Boot 经验明确', gap: '补充性能调优证据' },
            seniority: { score: 85, evidence: '五年研发经验', gap: '' },
            project_relevance: { score: 80, evidence: '有平台建设项目', gap: '补充业务结果' },
            domain_fit: { score: 75, evidence: '具备企业软件经验', gap: '行业经验较少' },
            constraints: { score: 95, evidence: '地点与经验要求匹配', gap: '' },
          },
          hits: ['核心技术栈匹配', '具备平台交付经验'],
          gaps: ['缺少业务结果量化'],
          evidence: [
            {
              job_requirement: '负责高并发平台建设',
              resume_evidence: '主导服务治理项目',
              assessment: '项目方向匹配，但需要补充并发指标',
            },
          ],
          improvement_actions: ['在项目经历中补充吞吐量和延迟优化指标'],
          interview_focus: ['准备服务治理架构和故障恢复方案'],
          risks: ['业务成果证据不足可能影响高级岗位定级'],
          limitations: ['岗位未说明团队规模'],
        },
      },
    })

    const analysisButton = wrapper
      .findAll('.job-card-actions .favorite-job-btn')
      .find((button) => button.text() === '查看分析')
    await analysisButton.trigger('click')

    const report = wrapper.get('.favorite-analysis-report')
    expect(report.get('.favorite-analysis-score').text()).toContain('82')
    expect(report.get('.favorite-analysis-verdict').text()).toContain('建议优先投递')
    expect(report.findAll('.favorite-analysis-dimension-row')).toHaveLength(5)
    expect(report.text()).toContain('Java 与 Spring Boot 经验明确')
    expect(report.text()).toContain('缺少业务结果量化')
    const modalHead = wrapper.get('.favorite-analysis-modal-head')
    expect(modalHead.get('.favorite-analysis-title-block').text()).toContain('Java 大模型应用开发工程师')
    expect(modalHead.get('.favorite-analysis-job-meta').text()).toContain('示例科技')
    expect(modalHead.get('.favorite-analysis-context-card').text()).toContain('高级研发工程师.pdf')

    const evidenceTab = wrapper
      .findAll('.favorite-analysis-tabs button')
      .find((button) => button.text().includes('证据与风险'))
    await evidenceTab.trigger('click')
    expect(wrapper.get('.favorite-analysis-evidence').text()).toContain('负责高并发平台建设')
    expect(wrapper.get('.favorite-analysis-risk-card').text()).toContain('业务成果证据不足可能影响高级岗位定级')

    const actionsTab = wrapper
      .findAll('.favorite-analysis-tabs button')
      .find((button) => button.text().includes('行动方案'))
    await actionsTab.trigger('click')
    expect(wrapper.get('.favorite-analysis-actions-group').text()).toContain('在项目经历中补充吞吐量和延迟优化指标')
    expect(wrapper.get('.favorite-analysis-actions-group').text()).toContain('准备服务治理架构和故障恢复方案')
  })

  it('renders compact analysis when optional evidence fields are absent', async () => {
    const wrapper = mountFavorites({
      analysis: { match: { score: 68, recommendation: '谨慎', hits: ['Java 基础匹配'], gaps: ['缺少项目证据'] } },
    })

    const analysisButton = wrapper
      .findAll('.job-card-actions .favorite-job-btn')
      .find((button) => button.text() === '查看分析')
    await analysisButton.trigger('click')

    expect(wrapper.get('.favorite-analysis-overview').text()).toContain('68')
    expect(wrapper.get('.favorite-analysis-list-card.strengths').text()).toContain('Java 基础匹配')
    expect(wrapper.get('.favorite-analysis-list-card.gaps').text()).toContain('缺少项目证据')
    expect(wrapper.find('.favorite-analysis-dimensions').exists()).toBe(false)
    expect(wrapper.find('.favorite-analysis-evidence').exists()).toBe(false)
    const groupedTabs = wrapper.findAll('.favorite-analysis-tabs button')
    expect(groupedTabs[1].attributes('disabled')).toBeDefined()
    expect(groupedTabs[2].attributes('disabled')).toBeDefined()
  })

  it('allows closing the analysis modal while the background task is running', async () => {
    const wrapper = mountFavorites()
    const analysisButton = wrapper
      .findAll('.job-card-actions .favorite-job-btn')
      .find((button) => button.text() === '分析岗位')

    await analysisButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('.favorite-analysis-modal-card').exists()).toBe(true)
    expect(wrapper.get('.favorite-analysis-loading').text()).toContain('正在生成岗位决策报告')
    await wrapper.get('.favorite-analysis-close').trigger('click')
    expect(wrapper.find('.favorite-analysis-modal-card').exists()).toBe(false)

    const runningButton = wrapper
      .findAll('.job-card-actions .favorite-job-btn')
      .find((button) => button.text() === '分析中')
    expect(runningButton.attributes('disabled')).toBeUndefined()
    await runningButton.trigger('click')
    await flushPromises()
    expect(wrapper.find('.favorite-analysis-modal-card').exists()).toBe(true)
  })

  it('shows completed report sections while later sections are still running', async () => {
    const wrapper = mountFavorites()
    const job = useJobStore()
    job.applyFavoriteAnalysisTask({
      taskId: 'task-partial-ui',
      resourceKey: 'favorite-1',
      status: 'running',
      stage: 'partial_overview',
      result: {},
      partialResult: {
        ...job.favorites[0],
        analysis: {
          match: {
            score: 76,
            recommendation: '可尝试',
            reasoning: '首组投递结论已完成',
            hits: ['Java 匹配'],
            gaps: ['量化证据不足'],
          },
        },
      },
    })
    await flushPromises()

    const runningButton = wrapper
      .findAll('.job-card-actions .favorite-job-btn')
      .find((button) => button.text() === '分析中')
    await runningButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('.favorite-analysis-loading').exists()).toBe(true)
    expect(wrapper.get('.favorite-analysis-report').text()).toContain('首组投递结论已完成')
    expect(wrapper.get('.favorite-analysis-score').text()).toContain('76')
  })

  it('asks for confirmation before removing a favorite', async () => {
    const wrapper = mountFavorites()

    expect(wrapper.find('.job-card-actions .favorite-job-btn.active').exists()).toBe(false)
    await wrapper.get('.remove-job-btn').trigger('click')

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).toContain('确认移出“Java 大模型应用开发工程师”')
    expect(dialog.text()).toContain('示例科技')
    expect(deleteFavoriteJob).not.toHaveBeenCalled()

    await dialog.get('.secondary-btn').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('.job-card').exists()).toBe(true)
    expect(deleteFavoriteJob).not.toHaveBeenCalled()
  })

  it('keeps the card stable while removal is pending and removes it after success', async () => {
    let resolveDelete
    deleteFavoriteJob.mockReturnValue(
      new Promise((resolve) => {
        resolveDelete = resolve
      }),
    )
    const wrapper = mountFavorites()

    await wrapper.get('.remove-job-btn').trigger('click')
    await wrapper.get('.history-delete-actions .danger-btn').trigger('click')

    expect(wrapper.find('.job-card').exists()).toBe(true)
    expect(wrapper.get('.history-delete-actions .danger-btn').text()).toBe('移除中')
    expect(wrapper.get('.history-delete-actions .danger-btn').attributes('disabled')).toBeDefined()
    expect(deleteFavoriteJob).toHaveBeenCalledTimes(1)

    resolveDelete([])
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('.job-card').exists()).toBe(false)
  })

  it('keeps the confirmation open and shows the error when removal fails', async () => {
    deleteFavoriteJob.mockRejectedValue(new Error('网络异常'))
    const wrapper = mountFavorites()

    await wrapper.get('.remove-job-btn').trigger('click')
    await wrapper.get('.history-delete-actions .danger-btn').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain('网络异常')
    expect(wrapper.find('.job-card').exists()).toBe(true)
    expect(wrapper.get('.history-delete-actions .danger-btn').attributes('disabled')).toBeUndefined()
  })
})
