import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeLibrary from '../src/components/ResumeLibrary.vue'
import { useResumeStore } from '../src/stores/resume'

vi.mock('../src/api/resume', () => ({
  analyzeResume: vi.fn(),
  deleteResume: vi.fn(),
  getJobProfile: vi.fn(),
  getResume: vi.fn(),
  listResumes: vi.fn(),
  resumeDownloadUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/download`),
  resumePreviewUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/preview`),
  resumeThumbnailUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/thumbnail`),
  saveJobProfile: vi.fn(),
  syncBossOnlineResume: vi.fn(),
  updateResumeParsed: vi.fn(),
  uploadResume: vi.fn(),
}))

vi.mock('../src/api/workspace', () => ({
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
}))

describe('ResumeLibrary analysis report', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows partial report content with a compact running status before completion', () => {
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-partial-ui',
      originalName: '应聘Java开发.pdf',
      suffix: 'pdf',
      parseStatus: 'success',
      parsed: {
        name: '候选人',
        analysis: {
          overall_score: 74,
          summary: '首组总体判断已展示',
          advantages: ['Java 基础'],
          disadvantages: ['缺少量化结果', '职责边界模糊'],
        },
      },
    }
    resume.current = current
    resume.items = [current]
    resume.analysisTasks = {
      'resume-partial-ui': {
        taskId: 'task-partial-ui',
        resourceKey: 'resume-partial-ui',
        status: 'running',
        stage: 'partial_overview',
        message: '总体判断、优势与风险已生成',
        partialResult: current,
      },
    }

    const wrapper = mount(ResumeLibrary)

    expect(wrapper.get('.resume-analysis-task-status').text()).toContain('总体判断、优势与风险已生成')
    expect(wrapper.get('.resume-analysis-task-status').text()).toContain('可离开页面')
    const summaryMetrics = wrapper.findAll('.analysis-summary-metric')
    expect(summaryMetrics).toHaveLength(4)
    expect(wrapper.get('.resume-analysis-summary').text()).toContain('74')
    expect(summaryMetrics.map((item) => item.text())).toEqual(['综合评分74', '优势点1', '劣势点2', '深挖点0'])
    expect(summaryMetrics.every((item) => item.find('svg').exists())).toBe(true)
    expect(summaryMetrics[0].classes()).toContain('is-score')
    expect(summaryMetrics[1].classes()).toContain('is-advantage')
    expect(summaryMetrics[2].classes()).toContain('is-disadvantage')
    expect(summaryMetrics[3].classes()).toContain('is-deep-dive')
    expect(wrapper.find('.resume-analysis-pane .detail-top h2').exists()).toBe(false)
    expect(wrapper.find('.resume-analysis-pane .state-badge').exists()).toBe(false)
    expect(
      wrapper
        .findAll('.analysis-card')
        .some((section) => section.find('h3').exists() && section.get('h3').text() === '劣势'),
    ).toBe(true)
    expect(wrapper.get('.primary-analysis').text()).toContain('首组总体判断已展示')
    expect(wrapper.get('.analysis-start-btn').text()).toBe('分析中')
    expect(wrapper.get('.analysis-start-btn').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.favorite-analysis-loading').exists()).toBe(false)
  })

  it('renders action items in the dedicated full-width report list', () => {
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-1',
      originalName: 'Java工程师简历.pdf',
      suffix: 'pdf',
      parseStatus: 'SUCCESS',
      parsed: {
        name: '候选人',
        analysis: {
          action_items: [
            '立即修正工作经历的起止时间。',
            '重构个人优势与专业技能板块，提炼三点以内的一句话核心竞争力。',
          ],
        },
      },
    }
    resume.current = current
    resume.items = [current]
    resume.loaded = true

    const wrapper = mount(ResumeLibrary)
    const actionSection = wrapper.findAll('.analysis-card').find((section) => section.get('h3').text() === '行动建议')
    const reportList = actionSection.get('.analysis-report-list')

    expect(reportList.classes()).toContain('analysis-list')
    expect(reportList.findAll('p').map((item) => item.text())).toEqual(current.parsed.analysis.action_items)
    expect(wrapper.find('.score-breakdown-card').exists()).toBe(false)
  })

  it('keeps weighted score evidence collapsed until the user expands it', async () => {
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-scored',
      originalName: '后端工程师.pdf',
      suffix: 'pdf',
      parseStatus: 'success',
      parsed: {
        name: '候选人',
        analysis: {
          overall_score: 78,
          score_breakdown: {
            content_completeness: { label: '内容完整性', score: 80, weight: 15, evidence: '教育、工作和项目章节完整' },
            achievement_evidence: { label: '成果证据', score: 90, weight: 25, evidence: '包含三项量化成果' },
          },
        },
      },
    }
    resume.current = current
    resume.items = [current]
    resume.loaded = true

    const wrapper = mount(ResumeLibrary)
    const breakdown = wrapper.get('.score-breakdown-card')
    const toggle = breakdown.get('.score-breakdown-toggle')

    expect(breakdown.text()).toContain('六个维度按固定权重汇总')
    expect(toggle.text()).toBe('展开详情')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(breakdown.find('.score-breakdown-grid').exists()).toBe(false)

    const reportCards = wrapper.findAll('.resume-analysis-pane > .analysis-card')
    expect(reportCards.findIndex((card) => card.classes().includes('primary-analysis'))).toBeLessThan(
      reportCards.findIndex((card) => card.classes().includes('score-breakdown-card')),
    )

    await toggle.trigger('click')
    const rows = breakdown.findAll('.score-breakdown-item')
    expect(toggle.text()).toBe('收起详情')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('内容完整性')
    expect(rows[0].text()).toContain('权重 15%')
    expect(rows[0].text()).toContain('良好')
    expect(rows[0].classes()).toContain('is-good')
    expect(rows[0].attributes('style')).toContain('--score-progress: 80%')
    expect(rows[1].text()).toContain('卓越')
    expect(rows[1].text()).toContain('评分证据')
    expect(rows[1].text()).toContain('包含三项量化成果')

    await toggle.trigger('click')
    expect(breakdown.find('.score-breakdown-grid').exists()).toBe(false)
  })
})
