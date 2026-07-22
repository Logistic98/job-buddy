import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import QuestionEditModal from '../src/components/interview/QuestionEditModal.vue'
import PracticeMarkdown from '../src/components/interview/PracticeMarkdown.vue'

const mocks = vi.hoisted(() => ({
  createQuestion: vi.fn(),
  extractInterviewDocument: vi.fn(),
  generateQuestions: vi.fn(),
  updateQuestion: vi.fn(),
}))

vi.mock('../src/api/interview', () => ({
  createQuestion: mocks.createQuestion,
  extractInterviewDocument: mocks.extractInterviewDocument,
  generateQuestions: mocks.generateQuestions,
  updateQuestion: mocks.updateQuestion,
}))

const bankTypeOptions = [
  { value: 'qa', label: '问答题库' },
  { value: 'leetcode', label: '算法题库' },
]

function mountModal() {
  return mount(QuestionEditModal, { props: { bankTypeOptions }, attachTo: document.body })
}

function editableQuestion() {
  return {
    questionId: 'q-1',
    title: 'HashMap 扩容机制',
    bankType: 'qa',
    category: 'Java',
    difficulty: '中等',
    questionType: '简答',
    content: '请解释 HashMap 的扩容机制',
    answer: '容量翻倍并重新分配桶位置',
    tags: ['Java', '集合'],
  }
}

beforeEach(() => {
  mocks.createQuestion.mockReset().mockResolvedValue({ questionId: 'q-new' })
  mocks.extractInterviewDocument.mockReset().mockResolvedValue({
    fileName: 'reference.pdf',
    text: '上海 Java 大模型应用开发岗，月薪40-50k',
    characterCount: 28,
    truncated: false,
  })
  mocks.generateQuestions.mockReset().mockResolvedValue([])
  mocks.updateQuestion.mockReset().mockResolvedValue(editableQuestion())
})

describe('QuestionEditModal', () => {
  it('splits manual creation into three steps with one scrollable content area', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate()
    await nextTick()

    expect(wrapper.find('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.find('.question-maintain-scroll').exists()).toBe(true)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(2)
    const stepButtons = wrapper.findAll('.question-wizard-steps button')
    expect(stepButtons).toHaveLength(3)
    expect(stepButtons.every((button) => !Object.hasOwn(button.attributes(), 'disabled'))).toBe(true)
    expect(wrapper.find('.practice-section').text()).toContain('基本信息')
    expect(wrapper.find('.practice-modal-actions .primary-btn').text()).toBe('下一步')
    expect(wrapper.findAll('.maintain-field-grid select').map((select) => select.element.value)).toEqual(['', '', ''])
    expect(
      wrapper
        .findAll('.maintain-field-grid input')
        .slice(0, 2)
        .map((input) => input.element.value),
    ).toEqual(['', ''])

    await stepButtons[2].trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('参考答案 / 判分关键词')
    expect(wrapper.find('.question-wizard-error').exists()).toBe(false)

    await stepButtons[0].trigger('click')
    const basicInputs = wrapper.findAll('.maintain-field-grid input')
    const basicSelects = wrapper.findAll('.maintain-field-grid select')
    await basicInputs[0].setValue('Agent 并发基础')
    await basicSelects[0].setValue('qa')
    await basicInputs[1].setValue('Agent 工程')
    await basicSelects[1].setValue('中等')
    await basicSelects[2].setValue('简答')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('题目内容')
    expect(wrapper.find('.question-content-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-actions').text()).not.toContain('第 2 / 3 步')

    await wrapper.find('#question-content-markdown').setValue('请说明线程池的核心参数。')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.practice-section > .practice-field-label').exists()).toBe(false)
    expect(wrapper.find('.practice-section').text()).toContain('参考答案 / 判分关键词')
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-progress').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-actions').text()).not.toContain('第 3 / 3 步')

    await wrapper.find('.practice-modal-actions .secondary-btn:nth-child(2)').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('题目内容')

    wrapper.unmount()
  })

  it('splits AI generation into settings and reference material steps', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.findAll('.question-wizard-steps button')).toHaveLength(2)
    expect(wrapper.find('.practice-section').text()).toContain('生成设置')
    expect(wrapper.findAll('.maintain-field-grid input').map((input) => input.element.value)).toEqual(['', '', ''])
    expect(wrapper.findAll('.maintain-field-grid select').map((select) => select.element.value)).toEqual(['', '', ''])

    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('参考资料')
    const documentInput = wrapper.find('.doc-upload-box input[type="file"]')
    expect(documentInput.exists()).toBe(true)
    expect(documentInput.attributes('accept')).toContain('.pdf')
    expect(documentInput.attributes('accept')).toContain('.doc')
    expect(documentInput.attributes('accept')).toContain('.docx')
    expect(wrapper.find('.doc-upload-box b').text()).toBe('选择文档')
    expect(wrapper.find('.question-document-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-requirements-textarea').exists()).toBe(true)
    expect(wrapper.find('.practice-modal-actions .primary-btn').text()).toBe('生成并入库')

    wrapper.unmount()
  })

  it('extracts uploaded PDF or Word content and keeps existing text when extraction fails', async () => {
    let resolveExtraction
    mocks.extractInterviewDocument.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveExtraction = resolve
      }),
    )
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')

    const input = wrapper.find('.doc-upload-box input[type="file"]')
    const pdf = new File(['pdf'], 'reference.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [pdf] })
    await input.trigger('change')

    expect(mocks.extractInterviewDocument).toHaveBeenCalledWith(pdf)
    expect(wrapper.find('.doc-upload-box b').text()).toBe('正在读取')
    expect(wrapper.find('.practice-modal-actions .primary-btn').attributes()).toHaveProperty('disabled')

    resolveExtraction({
      fileName: 'reference.pdf',
      text: '上海 Java 大模型应用开发岗，月薪40-50k',
      characterCount: 22000,
      truncated: true,
    })
    await flushPromises()

    expect(wrapper.find('.question-document-textarea').element.value).toBe('上海 Java 大模型应用开发岗，月薪40-50k')
    expect(wrapper.find('.doc-upload-box').text()).toContain('reference.pdf')
    expect(wrapper.find('.field-hint').text()).toContain('已提取前 20000 个字符')

    mocks.extractInterviewDocument.mockRejectedValueOnce(new Error('Word 文档解析失败'))
    const docx = new File(['broken'], 'broken.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [docx] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.find('.question-wizard-error').text()).toBe('Word 文档解析失败')
    expect(wrapper.find('.question-document-textarea').element.value).toBe('上海 Java 大模型应用开发岗，月薪40-50k')
    expect(wrapper.find('.doc-upload-box').text()).toContain('reference.pdf')

    wrapper.unmount()
  })

  it('switches between full-width Markdown editing and preview for content and answers', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    const contentSource = wrapper.find('#question-content-markdown')
    const contentTabs = wrapper.find('[aria-label="题目内容编辑模式"]')
    expect(contentSource.exists()).toBe(true)
    expect(wrapper.find('[aria-label="题目内容 Markdown 预览"]').exists()).toBe(false)
    expect(contentTabs.findAll('[role="tab"]')[0].attributes('aria-selected')).toBe('true')

    await contentSource.setValue('## 扩容流程\n\n- 容量翻倍\n- 重新分桶')
    await contentTabs.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('#question-content-markdown').exists()).toBe(false)
    expect(wrapper.find('[aria-label="题目内容 Markdown 预览"]').exists()).toBe(true)
    expect(wrapper.findComponent(PracticeMarkdown).props('content')).toContain('## 扩容流程')

    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    const answerSource = wrapper.find('#question-answer-markdown')
    const answerTabs = wrapper.find('[aria-label="参考答案编辑模式"]')
    expect(answerSource.exists()).toBe(true)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(false)
    await answerSource.setValue('`resize()` 会重新计算桶位置。')
    await answerTabs.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('#question-answer-markdown').exists()).toBe(false)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(true)
    expect(wrapper.findComponent(PracticeMarkdown).props('content')).toContain('`resize()`')

    wrapper.unmount()
  })

  it('uses dedicated editing areas for coding templates, tests, and answers', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    await wrapper.find('.practice-field input').setValue('两数之和')
    await wrapper.findAll('.practice-field select')[0].setValue('leetcode')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.question-content-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-code-template-textarea').exists()).toBe(true)
    expect(wrapper.find('[aria-label="题目内容编辑模式"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="题目内容 Markdown 预览"]').exists()).toBe(false)

    await wrapper.find('#question-content-markdown').setValue('给定整数数组和目标值，返回两个下标。')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.question-tests-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-answer-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(false)
    expect(wrapper.find('[aria-label="参考答案编辑模式"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('returns to the first invalid step when final submission validates the whole form', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    await wrapper.findAll('.question-wizard-steps button')[2].trigger('click')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')

    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('基本信息')
    expect(wrapper.find('.question-wizard-error').text()).toBe('请填写题目标题')
    expect(mocks.createQuestion).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the final step open when save validation fails', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    await wrapper.find('.practice-field input').setValue('两数之和')
    const basicSelects = wrapper.findAll('.practice-field select')
    await basicSelects[0].setValue('leetcode')
    await wrapper.find('.maintain-field-grid .practice-field:nth-child(3) input').setValue('算法')
    await basicSelects[1].setValue('中等')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    await wrapper.find('#question-content-markdown').setValue('给定整数数组和目标值，返回两个下标。')
    await wrapper.find('.coding-meta-editor select').setValue('python')
    await wrapper.find('.coding-meta-editor input[type="number"]').setValue(1)
    await wrapper.find('.question-code-template-textarea').setValue('def solution(*args):\n    return None')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    await wrapper.find('.question-tests-textarea').setValue('11')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')

    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('答案与判题')
    expect(wrapper.find('.question-wizard-error').text()).toBe('请维护至少一个测试用例')
    expect(wrapper.find('.question-tests-textarea').element.value).toBe('11')
    expect(mocks.createQuestion).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('adds and removes question tags one at a time', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    expect(wrapper.find('input[placeholder*="逗号分隔"]').exists()).toBe(false)
    expect(wrapper.findAll('.question-tag-list > span').map((tag) => tag.text())).toEqual(['Java×', '集合×'])

    const tagInput = wrapper.find('.question-tag-input-row input')
    await tagInput.setValue('Spring Boot')
    await tagInput.trigger('keydown', { key: 'Enter' })
    expect(wrapper.findAll('.question-tag-list > span').map((tag) => tag.text())).toEqual([
      'Java×',
      '集合×',
      'Spring Boot×',
    ])

    await wrapper.find('[aria-label="移除标签 集合"]').trigger('click')
    expect(wrapper.findAll('.question-tag-list > span').map((tag) => tag.text())).toEqual(['Java×', 'Spring Boot×'])

    await tagInput.setValue('Java,Spring')
    await wrapper.find('.question-tag-input-row button').trigger('click')
    expect(wrapper.find('.question-tag-error').text()).toBe('请一次添加一个标签。')
    expect(wrapper.findAll('.question-tag-list > span')).toHaveLength(2)

    wrapper.unmount()
  })

  it('keeps edit mode focused on updating the current question', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    expect(wrapper.text()).toContain('编辑题目')
    expect(wrapper.text()).toContain('分步修改当前题目的内容、分类和答案。')
    expect(wrapper.find('.interview-modal-tabs').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('手动录入')
    expect(wrapper.text()).not.toContain('AI 生成')

    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.question-content-textarea--standalone').exists()).toBe(true)
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    expect(wrapper.find('.practice-section > .practice-field-label').exists()).toBe(false)
    expect(wrapper.find('.practice-section').text()).toContain('参考答案 / 判分关键词')
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.practice-modal-actions .primary-btn').text()).toBe('保存修改')

    wrapper.unmount()
  })

  it('prevents closing and step changes while an update is in progress', async () => {
    let resolveUpdate
    mocks.updateQuestion.mockReturnValue(
      new Promise((resolve) => {
        resolveUpdate = resolve
      }),
    )
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    await wrapper.find('.practice-modal-actions .primary-btn').trigger('click')
    await nextTick()

    expect(wrapper.find('.close').attributes()).toHaveProperty('disabled')
    expect(
      wrapper
        .findAll('.question-wizard-steps button')
        .every((button) => Object.hasOwn(button.attributes(), 'disabled')),
    ).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    resolveUpdate(editableQuestion())
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
