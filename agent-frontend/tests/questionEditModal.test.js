import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CodeHighlightEditor from '../src/components/interview/CodeHighlightEditor.vue'
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
    text: '动态规划状态转移与复杂度分析',
    characterCount: 14,
    truncated: false,
  })
  mocks.generateQuestions.mockReset().mockResolvedValue([])
  mocks.updateQuestion.mockReset().mockResolvedValue(editableQuestion())
})

describe('QuestionEditModal', () => {
  it('uses the current QA bank and keeps save available while steps remain freely navigable', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    expect(wrapper.find('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.find('.question-maintain-scroll').exists()).toBe(true)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('分步维护问答题，也可上传资料后由 AI 辅助生成。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.find('input[placeholder*="最多 200 字"]').exists()).toBe(false)
    expect(wrapper.find('input[placeholder="例如：Agent 工具调用与失败恢复"]').attributes('maxlength')).toBe('200')
    expect(wrapper.find('.question-tag-field').classes()).toContain('wide')
    expect(wrapper.find('.question-wizard-action-buttons').text()).not.toContain('取消')
    expect(wrapper.find('.question-wizard-previous').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-next').text()).toBe('下一步')
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存题目')

    const stepButtons = wrapper.findAll('.question-wizard-steps button')
    expect(stepButtons).toHaveLength(3)
    expect(stepButtons.every((button) => !Object.hasOwn(button.attributes(), 'disabled'))).toBe(true)
    await stepButtons[2].trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('参考答案')
    expect(wrapper.find('.question-wizard-error').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-next').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-previous').text()).toBe('上一步')
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存题目')

    await stepButtons[0].trigger('click')
    await wrapper.find('input[placeholder*="Agent 工具调用"]').setValue('Agent 并发基础')
    await wrapper.find('input[placeholder*="Agent 工程"]').setValue('Agent 工程')
    const basicSelects = wrapper.findAll('.maintain-field-grid select')
    expect(basicSelects).toHaveLength(1)
    await basicSelects[0].setValue('中等')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('题目描述')
    expect(wrapper.find('.question-content-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-previous').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-next').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-save').exists()).toBe(true)

    await wrapper.find('#question-content-markdown').setValue('请说明线程池的核心参数。')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('参考答案')
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(true)

    await wrapper.find('.question-wizard-previous').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('题目描述')
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()
    expect(mocks.createQuestion).toHaveBeenCalledWith(expect.objectContaining({ bankType: 'qa', questionType: '简答' }))

    wrapper.unmount()
  })

  it('splits algorithm AI generation into settings and streamlined requirement steps', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.findAll('.question-wizard-steps button')).toHaveLength(2)
    expect(wrapper.find('.practice-section').text()).toContain('生成设置')
    expect(wrapper.text()).toContain('分步维护算法题，也可上传资料后由 AI 辅助生成。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.findAll('.maintain-field-grid select').map((select) => select.element.value)).toEqual([''])
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题型')).toBe(false)
    expect(wrapper.find('.question-wizard-save').text()).toBe('生成并入库')
    await wrapper.find('input[placeholder="例如：动态规划、图论、二分查找"]').setValue('动态规划')
    await wrapper.find('input[placeholder="例如：动态规划"]').setValue('动态规划')
    await wrapper.find('.maintain-field-grid select').setValue('中等')
    await wrapper.find('input[type="number"]').setValue(3)

    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('上传资料')
    expect(wrapper.find('.practice-section').text()).toContain('出题要求')
    const documentInput = wrapper.find('.doc-upload-box input[type="file"]')
    expect(documentInput.exists()).toBe(true)
    expect(documentInput.attributes('accept')).toContain('.pdf')
    expect(documentInput.attributes('accept')).toContain('.doc')
    expect(documentInput.attributes('accept')).toContain('.docx')
    expect(wrapper.find('.doc-upload-box b').text()).toBe('选择文档')
    expect(wrapper.find('.doc-upload-box').text()).toContain('可上传算法题、题解或样例数据')
    expect(wrapper.find('.doc-upload-box').text()).not.toContain('上传后自动填入下方资料区')
    expect(wrapper.find('.question-document-textarea').exists()).toBe(false)
    expect(wrapper.find('.question-requirements-textarea').attributes('placeholder')).toContain(
      '生成 3 道动态规划算法题',
    )
    expect(wrapper.find('[role="dialog"]').classes()).toContain('question-maintain-modal--compact')
    expect(wrapper.text()).not.toContain('岗位 JD')
    expect(wrapper.text()).not.toContain('技术文档')
    expect(wrapper.text()).not.toContain('知识点材料')
    expect(wrapper.find('.question-wizard-next').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-save').text()).toBe('生成并入库')
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()
    expect(mocks.generateQuestions).toHaveBeenCalledWith(
      expect.objectContaining({ bankType: 'leetcode', questionType: '编程题', count: 3 }),
    )

    wrapper.unmount()
  })

  it('keeps extracted document text in the generation payload when a later extraction fails', async () => {
    let resolveExtraction
    mocks.extractInterviewDocument.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveExtraction = resolve
      }),
    )
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('input[placeholder="例如：动态规划、图论、二分查找"]').setValue('动态规划')
    await wrapper.find('input[placeholder="例如：动态规划"]').setValue('动态规划')
    await wrapper.find('.maintain-field-grid select').setValue('困难')
    await wrapper.find('input[type="number"]').setValue(2)
    await wrapper.find('.question-wizard-next').trigger('click')

    const input = wrapper.find('.doc-upload-box input[type="file"]')
    const pdf = new File(['pdf'], 'reference.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [pdf] })
    await input.trigger('change')

    expect(mocks.extractInterviewDocument).toHaveBeenCalledWith(pdf)
    expect(wrapper.find('.doc-upload-box b').text()).toBe('正在读取')
    expect(wrapper.find('.question-wizard-save').attributes()).toHaveProperty('disabled')

    resolveExtraction({
      fileName: 'reference.pdf',
      text: '动态规划状态转移与复杂度分析',
      characterCount: 22000,
      truncated: true,
    })
    await flushPromises()

    expect(wrapper.find('.question-document-textarea').exists()).toBe(false)
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
    expect(wrapper.find('.doc-upload-box').text()).toContain('reference.pdf')

    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()
    expect(mocks.generateQuestions).toHaveBeenCalledWith(
      expect.objectContaining({ documentName: 'reference.pdf', documentText: '动态规划状态转移与复杂度分析' }),
    )

    wrapper.unmount()
  })

  it('switches between full-width Markdown editing and preview for content and answers', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    await wrapper.find('.question-wizard-next').trigger('click')
    const contentSource = wrapper.find('#question-content-markdown')
    const contentTabs = wrapper.find('[aria-label="题目描述编辑模式"]')
    expect(contentSource.exists()).toBe(true)
    expect(wrapper.find('[aria-label="题目描述 Markdown 预览"]').exists()).toBe(false)
    expect(contentTabs.findAll('[role="tab"]')[0].attributes('aria-selected')).toBe('true')

    await contentSource.setValue('## 扩容流程\n\n- 容量翻倍\n- 重新分桶')
    await contentTabs.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('#question-content-markdown').exists()).toBe(false)
    expect(wrapper.find('[aria-label="题目描述 Markdown 预览"]').exists()).toBe(true)
    expect(wrapper.findComponent(PracticeMarkdown).props('content')).toContain('## 扩容流程')

    await wrapper.find('.question-wizard-next').trigger('click')
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

    expect(wrapper.text()).toContain('分步维护算法题，也可上传资料后由 AI 辅助生成。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题型')).toBe(false)
    await wrapper.find('input[placeholder*="Agent 工具调用"]').setValue('两数之和')
    await wrapper.find('input[placeholder*="Agent 工程"]').setValue('算法')
    await wrapper.find('.maintain-field-grid select').setValue('中等')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.question-content-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-code-template-textarea').exists()).toBe(true)
    expect(wrapper.findAllComponents(CodeHighlightEditor)).toHaveLength(1)
    expect(wrapper.find('#question-content-markdown').element.tagName).toBe('TEXTAREA')
    expect(wrapper.find('[aria-label="题目描述编辑模式"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="题目描述 Markdown 预览"]').exists()).toBe(false)

    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '默认语言')).toBe(false)
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '参数个数')).toBe(false)
    expect(wrapper.find('.coding-meta-editor select').exists()).toBe(false)
    expect(wrapper.find('.coding-meta-editor input[type="number"]').exists()).toBe(false)
    await wrapper.find('#question-content-markdown').setValue('给定整数数组和目标值，返回两个下标。')
    await wrapper.find('.question-code-template-textarea').setValue('def solution(nums, target):\n    return []')
    expect(wrapper.findAll('.code-token-keyword').some((token) => token.text() === 'def')).toBe(true)
    await wrapper.find('.question-wizard-next').trigger('click')
    const testsTextarea = wrapper.find('.question-tests-textarea')
    expect(testsTextarea.exists()).toBe(true)
    expect(testsTextarea.attributes('aria-required')).toBeUndefined()
    expect(testsTextarea.element.closest('label').textContent).toContain('测试用例 JSON（可选）')
    expect(wrapper.find('.question-answer-textarea').exists()).toBe(true)
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(false)
    expect(wrapper.find('[aria-label="参考答案编辑模式"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('allows free step navigation and validates the whole form when saving from any step', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    await wrapper.findAll('.question-wizard-steps button')[2].trigger('click')
    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('答案与判题')
    expect(wrapper.find('.question-wizard-error').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存题目')

    await wrapper.find('.question-wizard-save').trigger('click')
    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('基本信息')
    expect(wrapper.find('.question-wizard-error').text()).toBe('请填写题目标题')
    expect(mocks.createQuestion).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the final step open when save validation fails', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    await wrapper.find('input[placeholder*="Agent 工具调用"]').setValue('两数之和')
    await wrapper.find('input[placeholder*="Agent 工程"]').setValue('算法')
    await wrapper.find('.maintain-field-grid select').setValue('中等')
    await wrapper.find('.question-wizard-next').trigger('click')
    await wrapper.find('#question-content-markdown').setValue('给定整数数组和目标值，返回两个下标。')
    await wrapper.find('.question-code-template-textarea').setValue('def solution(*args):\n    return None')
    await wrapper.find('.question-wizard-next').trigger('click')
    await wrapper.find('.question-tests-textarea').setValue('{ not json')
    await wrapper.find('.question-wizard-save').trigger('click')

    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('答案与判题')
    expect(wrapper.find('.question-wizard-error').text()).toBe('测试用例 JSON 格式不正确')
    expect(wrapper.find('.question-tests-textarea').element.value).toBe('{ not json')
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
    expect(wrapper.text()).toContain('分步修改当前问答题的内容、分类和答案。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题型')).toBe(false)
    expect(wrapper.find('.question-wizard-action-buttons').text()).not.toContain('取消')
    expect(wrapper.find('.interview-modal-tabs').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('手动录入')
    expect(wrapper.text()).not.toContain('AI 生成')

    expect(wrapper.find('.question-wizard-save').text()).toBe('保存修改')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.question-content-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存修改')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.practice-section > .practice-field-label').exists()).toBe(false)
    expect(wrapper.find('.practice-section').text()).toContain('参考答案')
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(true)
    expect(wrapper.find('.question-wizard-next').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存修改')

    wrapper.unmount()
  })

  it('normalizes legacy coding metadata and updates exactly once from the final save action', async () => {
    const legacyQuestion = {
      questionId: 'q-legacy',
      title: '两数之和',
      bankType: 'leetcode',
      category: '数组',
      difficulty: '简单',
      questionType: '',
      content: '给定整数数组和目标值，返回两个下标。',
      answer: '使用哈希表记录已访问元素。',
      tags: ['算法'],
      codingMeta: {
        template: 'def two_sum(nums, target):\n    return [0, 1]',
        tests: [{ name: '示例', args: [[2, 7], 9], expected: [0, 1], sample: true }],
      },
    }
    mocks.updateQuestion.mockResolvedValueOnce({ ...legacyQuestion, questionType: '编程题' })
    const wrapper = mountModal()
    wrapper.vm.openEdit(legacyQuestion)
    await nextTick()

    expect(wrapper.text()).toContain('分步修改当前算法题的内容、分类和答案。')
    expect(wrapper.findAll('.practice-field-label').some((label) => label.text() === '题库')).toBe(false)
    expect(wrapper.find('.question-tag-field').classes()).toContain('wide')
    expect(wrapper.find('.question-wizard-action-buttons').text()).not.toContain('取消')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.coding-meta-editor select').exists()).toBe(false)
    expect(wrapper.find('.coding-meta-editor input[type="number"]').exists()).toBe(false)
    expect(wrapper.find('.question-code-template-textarea').element.value).toContain('def two_sum')
    expect(wrapper.find('.coding-meta-editor .code-highlight-editor').attributes('data-language')).toBe('python')
    await wrapper.find('.question-wizard-next').trigger('click')

    const saveButton = wrapper.find('.question-wizard-save')
    expect(saveButton.text()).toBe('保存修改')
    expect(saveButton.attributes('type')).toBe('button')
    await saveButton.trigger('click')
    await flushPromises()

    expect(mocks.updateQuestion).toHaveBeenCalledTimes(1)
    expect(mocks.updateQuestion).toHaveBeenCalledWith(
      'q-legacy',
      expect.objectContaining({
        questionType: '编程题',
        codingMeta: expect.objectContaining({ language: 'python', functionName: 'two_sum', parameterCount: 2 }),
      }),
    )
    expect(mocks.createQuestion).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

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

    await wrapper.find('.question-wizard-save').trigger('click')
    await wrapper.find('.question-wizard-save').trigger('click')
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
