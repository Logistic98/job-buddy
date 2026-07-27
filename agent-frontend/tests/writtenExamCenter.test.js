import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WrittenExamCenter from '../src/components/WrittenExamCenter.vue'
import PaperCompositionCenter from '../src/components/interview/PaperCompositionCenter.vue'
import PracticeConfigModal from '../src/components/interview/PracticeConfigModal.vue'
import QuestionEditModal from '../src/components/interview/QuestionEditModal.vue'
import SmartPracticePanel from '../src/components/interview/SmartPracticePanel.vue'

const mocks = vi.hoisted(() => ({
  listQuestions: vi.fn(),
  getQuestionMeta: vi.fn(),
  listExams: vi.fn(),
  getExam: vi.fn(),
  deleteExam: vi.fn(),
  createRandomExam: vi.fn(),
}))

vi.mock('../src/api/interview', () => ({
  batchQuestions: vi.fn(),
  createQuestion: vi.fn(),
  createRandomExam: mocks.createRandomExam,
  createSmartExam: vi.fn(),
  deleteExam: mocks.deleteExam,
  deleteQuestion: vi.fn(),
  generateQuestions: vi.fn(),
  getExam: mocks.getExam,
  getQuestionMeta: mocks.getQuestionMeta,
  listExams: mocks.listExams,
  listQuestions: mocks.listQuestions,
  runCodeSample: vi.fn(),
  submitExam: vi.fn(),
  updateQuestion: vi.fn(),
}))

beforeEach(() => {
  mocks.listQuestions.mockReset().mockResolvedValue({ items: [], total: 0, page: 1, size: 10, pages: 1 })
  mocks.getQuestionMeta.mockReset().mockResolvedValue({
    bankTypeOptions: [
      { value: 'leetcode', label: 'LeetCode' },
      { value: 'qa', label: '问答题库' },
    ],
    categories: ['Java'],
    difficulties: ['简单', '中等', '困难'],
    questionTypes: ['单选', '多选', '判断', '简答', '编程题'],
  })
  mocks.listExams.mockReset().mockResolvedValue([])
  mocks.getExam.mockReset()
  mocks.deleteExam.mockReset().mockResolvedValue({ deleted: true })
  mocks.createRandomExam.mockReset()
})

describe('WrittenExamCenter', () => {
  it('refreshes question metadata after a question is saved or imported', async () => {
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()
    const initialMetaCalls = mocks.getQuestionMeta.mock.calls.length
    const initialQuestionCalls = mocks.listQuestions.mock.calls.length

    wrapper.findComponent(QuestionEditModal).vm.$emit('saved', null)
    await flushPromises()

    expect(mocks.getQuestionMeta).toHaveBeenCalledTimes(initialMetaCalls + 1)
    expect(mocks.listQuestions).toHaveBeenCalledTimes(initialQuestionCalls + 1)
    wrapper.unmount()
  })

  it('opens a bank-specific create form through the complete practice-center event chain', async () => {
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()

    const createButton = wrapper.findAll('button').find((button) => button.text() === '新增题目')
    expect(createButton).toBeTruthy()

    await createButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"][aria-labelledby="question-maintain-title"]').exists()).toBe(true)
    expect(wrapper.find('#question-maintain-title').text()).toBe('新增算法题')
    expect(wrapper.text()).toContain('维护可执行的算法题，也可基于题面资料智能生成后人工审核。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '问答题型')).toBe(false)

    await wrapper.find('.question-maintain-modal .close').trigger('click')
    const qaTab = wrapper.findAll('button').find((button) => button.text() === '问答题库')
    await qaTab.trigger('click')
    await flushPromises()
    await createButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('#question-maintain-title').text()).toBe('新增问答题')
    expect(wrapper.text()).toContain('维护知识问答题，可选择简答、单选或多选，也可使用 AI 辅助生成。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '问答题型')).toBe(true)

    wrapper.unmount()
  })

  it('uses practice records as the desk home and opens composition in a dialog', async () => {
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()

    const topLevelTabs = wrapper.findAll('.written-center-tabs button').map((button) => button.text())
    expect(topLevelTabs).toHaveLength(2)
    expect(topLevelTabs[0]).toContain('题库')
    expect(topLevelTabs[1]).toContain('练习台')
    expect(topLevelTabs.join('')).not.toContain('组卷练习')

    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()

    expect(wrapper.find('.practice-records-home').exists()).toBe(true)
    expect(wrapper.find('.practice-start-card').exists()).toBe(false)
    expect(wrapper.find('.practice-records-home-header h2').text()).toBe('练习记录')

    await wrapper.find('.practice-create-button').trigger('click')
    await flushPromises()

    const smartComposition = wrapper.findComponent(PaperCompositionCenter)
    expect(smartComposition.exists()).toBe(true)
    expect(smartComposition.props('modal')).toBe(true)
    expect(wrapper.find('[role="dialog"][aria-labelledby="paper-composition-title"]').exists()).toBe(true)
    expect(wrapper.find('.practice-records-home').exists()).toBe(true)
    const panel = wrapper.findComponent(SmartPracticePanel)
    expect(panel.exists()).toBe(true)
    expect(wrapper.find('.written-center-tabs button.active').text()).toContain('练习台')

    await wrapper.find('.paper-composition-dialog > .close').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(PaperCompositionCenter).exists()).toBe(false)

    await wrapper.find('.practice-create-button').trigger('click')
    await flushPromises()
    await wrapper
      .findAll('.paper-composition-tabs button')
      .find((button) => button.text().includes('规则组卷'))
      .trigger('click')
    await flushPromises()

    const rulePanel = wrapper.findComponent(PracticeConfigModal)
    expect(rulePanel.exists()).toBe(true)
    expect(wrapper.findComponent(PaperCompositionCenter).props('modal')).toBe(true)
    expect(rulePanel.props('embedded')).toBe(true)
    expect(wrapper.find('.paper-composition-tabs button.active').text()).toContain('规则组卷')
    rulePanel.vm.$emit('created', { examId: 'practice-rule-1' })
    await flushPromises()

    expect(wrapper.findComponent(PaperCompositionCenter).exists()).toBe(false)
    const activeTab = wrapper.find('.written-center-tabs button.active')
    expect(activeTab.text()).toContain('练习台')
    expect(wrapper.findComponent({ name: 'InterviewBank' }).props('initialExamId')).toBe('practice-rule-1')
    wrapper.unmount()
  })

  it('renders a stable loading state before practice records are available', async () => {
    let resolveExams
    mocks.listExams.mockReturnValue(
      new Promise((resolve) => {
        resolveExams = resolve
      }),
    )
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()

    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')

    expect(wrapper.find('.practice-records-loading').exists()).toBe(true)
    expect(wrapper.find('.practice-record-summary').exists()).toBe(false)
    expect(wrapper.find('.practice-records-home-toolbar').exists()).toBe(false)

    resolveExams([])
    await flushPromises()

    expect(wrapper.find('.practice-records-loading').exists()).toBe(false)
    expect(wrapper.find('.practice-record-summary').exists()).toBe(true)
    expect(wrapper.find('.practice-records-home-toolbar').exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the record search label inline and searches by practice name only', async () => {
    mocks.listExams.mockResolvedValue([
      {
        examId: 'practice-java',
        title: 'Java 并发专项',
        status: 'running',
        totalCount: 6,
        strategy: { mode: 'smart', showAnswer: false },
      },
      {
        examId: 'practice-redis',
        title: 'Redis 复盘练习',
        status: 'submitted',
        totalCount: 4,
        strategy: { mode: 'random', showAnswer: true },
      },
    ])
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()

    const search = wrapper.find('.practice-record-search')
    const input = search.find('input')
    expect(search.find('span').text()).toBe('搜索练习记录')
    expect(input.attributes('placeholder')).toBe('搜索练习名称')

    await input.setValue('智能组卷')
    expect(wrapper.findAll('.practice-record-row')).toHaveLength(0)

    await input.setValue('Java')
    expect(wrapper.findAll('.practice-record-row')).toHaveLength(1)
    expect(wrapper.find('.practice-record-row').text()).toContain('Java 并发专项')
    wrapper.unmount()
  })

  it('removes duplicate navigation actions from the empty practice desk header', async () => {
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()

    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()

    const headerButtons = wrapper.findAll('.embedded-actions .history-header-actions button')
    expect(headerButtons).toHaveLength(0)
    expect(wrapper.text()).not.toContain('随机组卷')
    expect(wrapper.findAll('.practice-records-home .practice-create-button')).toHaveLength(1)
    expect(wrapper.find('.practice-create-button').text()).toBe('创建练习')
    expect(wrapper.text()).not.toContain('题库中的单题练习不会加入记录')
    wrapper.unmount()
  })

  it('shows record actions and deletes a practice after confirmation', async () => {
    mocks.listExams.mockResolvedValue([
      {
        examId: 'practice-running',
        title: 'Java 并发专项',
        status: 'running',
        totalCount: 6,
        answeredCount: 2,
        durationMinutes: 45,
        startedAt: '2026-07-27T00:00:00Z',
        strategy: { mode: 'smart', showAnswer: false },
      },
      {
        examId: 'practice-finished',
        title: 'Redis 复盘练习',
        status: 'submitted',
        totalCount: 4,
        answeredCount: 4,
        score: 85,
        startedAt: '2026-07-26T00:00:00Z',
        strategy: { mode: 'random', showAnswer: true },
      },
      {
        examId: 'legacy-single',
        title: '旧版行内题 单题练习',
        status: 'submitted',
        totalCount: 1,
        score: 100,
        startedAt: '2026-07-25T00:00:00Z',
        strategy: { mode: 'manual', showAnswer: true },
      },
    ])
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.practice-record-row')).toHaveLength(2)
    expect(wrapper.find('.practice-record-summary').text()).toContain('进行中1')
    const actionTexts = wrapper.findAll('.practice-record-actions .secondary-btn').map((button) => button.text())
    expect(actionTexts).toEqual(['继续练习', '查看复盘'])

    await wrapper.findAll('.practice-record-actions .danger-text')[0].trigger('click')
    expect(wrapper.find('.practice-record-delete-modal').exists()).toBe(true)
    expect(wrapper.find('.practice-record-delete-modal h2').text()).toContain('Java 并发专项')
    await wrapper.find('.practice-record-delete-modal .danger-btn').trigger('click')
    await flushPromises()

    expect(mocks.deleteExam).toHaveBeenCalledWith('practice-running')
    expect(wrapper.findAll('.practice-record-row')).toHaveLength(1)
    wrapper.unmount()
  })

  it('opens a record directly and returns to the record-first practice home', async () => {
    const question = {
      questionId: 'q-record',
      title: 'HashMap 扩容机制',
      bankType: 'qa',
      difficulty: '中等',
      questionType: '简答',
      content: '说明 HashMap 扩容机制。',
      answer: '容量翻倍并重新分布桶。',
    }
    const exam = {
      examId: 'practice-record',
      title: 'Java 集合练习',
      status: 'running',
      totalCount: 1,
      answeredCount: 0,
      remainingSeconds: 1800,
      startedAt: '2026-07-27T00:00:00Z',
      strategy: { mode: 'smart', showAnswer: false },
      questions: [question],
    }
    mocks.listExams.mockResolvedValue([exam])
    mocks.getExam.mockResolvedValue(exam)
    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()

    await wrapper.find('.practice-record-main').trigger('click')
    await flushPromises()
    expect(mocks.getExam).toHaveBeenCalledWith('practice-record')
    expect(wrapper.find('.practice-active-workbench').exists()).toBe(true)

    await wrapper
      .findAll('.practice-overview-actions button')
      .find((button) => button.text() === '练习记录')
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.practice-records-home').exists()).toBe(true)

    await wrapper.findAll('.written-center-tabs button')[0].trigger('click')
    await wrapper.findAll('.written-center-tabs button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.practice-records-home').exists()).toBe(true)
    wrapper.unmount()
  })

  it('creates a transient single-question practice that is excluded from records', async () => {
    const question = {
      questionId: 'q-single',
      title: 'volatile 可见性',
      bankType: 'qa',
      category: 'Java',
      difficulty: '中等',
      questionType: '简答',
      content: '说明 volatile 的语义。',
      answer: '可见性与有序性',
    }
    mocks.listQuestions.mockResolvedValue({ items: [question], total: 1, page: 1, size: 10, pages: 1 })
    mocks.createRandomExam.mockResolvedValue({
      examId: 'practice-single',
      recorded: false,
      status: 'running',
      questions: [question],
      remainingSeconds: 1800,
    })
    mocks.getExam.mockResolvedValue({
      examId: 'practice-single',
      recorded: false,
      status: 'running',
      questions: [question],
      remainingSeconds: 1800,
    })

    const wrapper = mount(WrittenExamCenter, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('.interview-table .primary-text').trigger('click')
    await flushPromises()

    expect(mocks.createRandomExam).toHaveBeenCalledWith(
      expect.objectContaining({
        questionIds: ['q-single'],
        recorded: false,
      }),
    )
    expect(wrapper.find('.written-center-tabs button.active').text()).toContain('练习台')
    wrapper.unmount()
  })
})
