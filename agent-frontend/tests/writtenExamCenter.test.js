import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WrittenExamCenter from '../src/components/WrittenExamCenter.vue'
import QuestionEditModal from '../src/components/interview/QuestionEditModal.vue'

const mocks = vi.hoisted(() => ({
  listQuestions: vi.fn(),
  getQuestionMeta: vi.fn(),
}))

vi.mock('../src/api/interview', () => ({
  batchQuestions: vi.fn(),
  createQuestion: vi.fn(),
  createRandomExam: vi.fn(),
  deleteQuestion: vi.fn(),
  generateQuestions: vi.fn(),
  getExam: vi.fn(),
  getQuestionMeta: mocks.getQuestionMeta,
  listExams: vi.fn().mockResolvedValue([]),
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
})
