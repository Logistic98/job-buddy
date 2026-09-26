import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeLibrary from '../../src/components/ResumeLibrary.vue'
import { useResumeStore } from '../../src/stores/resume'
import { getResume } from '../../src/api/resume'

vi.mock('../../src/api/resume', () => ({
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

vi.mock('../../src/api/workspace', () => ({
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
}))

describe('ResumeLibrary analysis report', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('shows zero for every summary metric when no resume is selected', () => {
    const wrapper = mount(ResumeLibrary)

    expect(wrapper.findAll('.analysis-summary-metric').map((item) => item.text())).toEqual([
      '综合评分0',
      '优势点0',
      '劣势点0',
      '深挖点0',
    ])
  })

  it('keeps a white loading layer visible until the PDF viewer has painted', async () => {
    vi.useFakeTimers()
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-preview',
      originalName: '候选人简历.pdf',
      suffix: 'pdf',
      parsed: {},
    }
    resume.current = current
    resume.items = [current]
    resume.loaded = true

    const wrapper = mount(ResumeLibrary)
    await wrapper.vm.$nextTick()

    const frame = wrapper.get('.resume-doc-frame')
    expect(wrapper.get('.resume-pdf-loading').text()).toContain('正在加载原始 PDF')
    expect(frame.classes()).not.toContain('ready')

    await frame.trigger('load')
    expect(wrapper.find('.resume-pdf-loading').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(4999)
    expect(wrapper.find('.resume-pdf-loading').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.find('.resume-pdf-loading').exists()).toBe(false)
    expect(frame.classes()).toContain('ready')
    wrapper.unmount()
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

  it('shows the selected resume analysis error without promoting it to a manager error', () => {
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-failed-ui',
      originalName: '失败简历.pdf',
      suffix: 'pdf',
      parsed: {},
    }
    resume.current = current
    resume.items = [current]
    resume.analysisTasks = {
      'resume-failed-ui': {
        taskId: 'task-failed-ui',
        resourceKey: 'resume-failed-ui',
        status: 'failed',
        stage: 'failed',
        errorMessage: '对象存储暂时不可用',
      },
    }

    const wrapper = mount(ResumeLibrary)

    expect(wrapper.get('.analysis-error').text()).toBe('对象存储暂时不可用')
    expect(resume.error).toBe('')
    wrapper.unmount()
  })

  it('keeps a successful report visible without showing a later historical failure', () => {
    const resume = useResumeStore()
    const current = {
      resumeId: 'resume-success-with-history',
      originalName: '成功简历.pdf',
      suffix: 'pdf',
      parsed: {
        analysis: {
          overall_score: 88,
          summary: '当前成功报告',
        },
      },
    }
    resume.current = current
    resume.items = [current]
    resume.analysisTasks = {
      'resume-success-with-history': {
        taskId: 'task-later-failed',
        resourceKey: 'resume-success-with-history',
        status: 'failed',
        stage: 'failed',
        errorMessage: '从 MinIO 下载简历失败',
      },
    }

    const wrapper = mount(ResumeLibrary)

    expect(wrapper.find('.analysis-error').exists()).toBe(false)
    expect(wrapper.get('.resume-analysis-summary').text()).toContain('88')
    expect(wrapper.get('.primary-analysis').text()).toContain('当前成功报告')
    wrapper.unmount()
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

describe('ResumeLibrary selection and analysis boundaries', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
  })
  afterEach(() => vi.restoreAllMocks())

  function library(items = []) {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = items
    resume.current = items[0] || null
    vi.spyOn(resume, 'load').mockResolvedValue()
    const wrapper = mount(ResumeLibrary, { global: { stubs: { teleport: true } } })
    return { resume, wrapper }
  }

  it('validates uploads before handing files to the shared store', async () => {
    const { resume, wrapper } = library()
    const upload = vi.spyOn(resume, 'upload').mockResolvedValue()
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], 'bad.exe')] })
    await input.trigger('change')
    expect(resume.error).not.toBe('')
    expect(upload).not.toHaveBeenCalled()
    const file = new File(['pdf'], 'resume.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    expect(upload).toHaveBeenCalledWith(file, expect.any(String))
    await wrapper.get('.analysis-empty-card button').trigger('click')
    expect(wrapper.emitted('manage-resumes')).toHaveLength(1)
  })

  it('hydrates searchable picker details and selects the requested PDF', async () => {
    const items = [
      { resumeId: 'one', suffix: 'pdf', originalName: 'First.pdf', parsed: {} },
      { resumeId: 'two', suffix: 'pdf', originalName: 'Second.pdf', parsed: {} },
    ]
    const { resume, wrapper } = library(items)
    getResume.mockImplementation(async (id) => ({
      ...items.find((item) => item.resumeId === id),
      parsed: { summary: 'Cloud engineer', skills: ['Python'] },
    }))
    const select = vi.spyOn(resume, 'select').mockResolvedValue()
    await wrapper.get('.analysis-select-btn').trigger('click')
    await flushPromises()
    expect(getResume).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.analysis-resume-picker-item')).toHaveLength(2)
    await wrapper.get('.resume-picker-search input').setValue('Python')
    expect(wrapper.findAll('.analysis-resume-picker-item')).toHaveLength(2)
    await wrapper.get('.resume-picker-search input').setValue('no match')
    expect(wrapper.text()).toContain('没有匹配的简历')
    await wrapper.get('.resume-picker-search input').setValue('Second')
    await wrapper.get('.analysis-resume-picker-actions button').trigger('click')
    expect(select).toHaveBeenCalledWith(expect.objectContaining({ resumeId: 'two' }))
    expect(wrapper.find('.analysis-resume-picker-mask').exists()).toBe(false)
  })

  it('keeps lightweight entries when detail loading fails and ignores late closed-picker results', async () => {
    const item = { resumeId: 'one', suffix: 'pdf', originalName: 'First.pdf', parsed: {} }
    const { wrapper } = library([item])
    getResume.mockRejectedValueOnce(new Error('detail offline'))
    await wrapper.get('.analysis-select-btn').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.analysis-resume-picker-item')).toHaveLength(1)
    await wrapper.get('.analysis-resume-picker-close').trigger('click')
    let resolve
    getResume.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    await wrapper.get('.analysis-select-btn').trigger('click')
    await flushPromises()
    await wrapper.get('.analysis-resume-picker-close').trigger('click')
    resolve({ ...item, parsed: { summary: 'stale detail' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('stale detail')
  })

  it('uploads from an empty picker and keeps invalid input visible', async () => {
    const { resume, wrapper } = library()
    const upload = vi.spyOn(resume, 'upload').mockRejectedValue(new Error('upload offline'))
    await wrapper.get('.analysis-select-btn').trigger('click')
    expect(wrapper.text()).toContain('暂无可分析简历')
    const input = wrapper.get('.analysis-resume-picker-modal input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], 'bad.txt')] })
    await input.trigger('change')
    expect(upload).not.toHaveBeenCalled()
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['x'], 'resume.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await flushPromises()
    expect(upload).toHaveBeenCalledOnce()
  })

  it('renders structured report fields and score levels while ignoring invalid dimensions', async () => {
    const breakdown = Object.fromEntries([95, 87, 78, 67, 40].map((score) => [String(score), { score, weight: 20 }]))
    breakdown.invalid = { score: 'invalid', weight: 20 }
    breakdown.empty = null
    const item = {
      resumeId: 'one',
      suffix: 'pdf',
      parsed: {
        analysis: {
          advantages: { title: 'Strength', evidence: ['Python', 'Java'], custom: 'detail' },
          score_breakdown: breakdown,
        },
      },
    }
    const { resume, wrapper } = library([item])
    expect(wrapper.text()).toContain('标题：Strength')
    expect(wrapper.text()).toContain('依据：Python、Java')
    await wrapper.get('.score-breakdown-toggle').trigger('click')
    expect(wrapper.findAll('.score-dimension-value small').map((node) => node.text())).toEqual([
      '待提升',
      '合格',
      '良好',
      '优秀',
      '卓越',
    ])
    const analyze = vi.spyOn(resume, 'analyze').mockRejectedValue(new Error('analysis offline'))
    await wrapper.get('.analysis-start-btn').trigger('click')
    await flushPromises()
    expect(analyze).toHaveBeenCalledWith('one', expect.any(String))
    expect(wrapper.find('.score-breakdown-grid').exists()).toBe(false)
  })
})
