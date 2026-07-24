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
  importQuestions: vi.fn(),
  updateQuestion: vi.fn(),
}))

vi.mock('../src/api/interview', () => ({
  createQuestion: mocks.createQuestion,
  extractInterviewDocument: mocks.extractInterviewDocument,
  generateQuestions: mocks.generateQuestions,
  importQuestions: mocks.importQuestions,
  updateQuestion: mocks.updateQuestion,
}))

const bankTypeOptions = [
  { value: 'qa', label: '问答题库' },
  { value: 'leetcode', label: '算法题库' },
]
const questionTypeOptions = ['简答', '单选', '多选', '编程题']

function mountModal() {
  return mount(QuestionEditModal, { props: { bankTypeOptions, questionTypeOptions }, attachTo: document.body })
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

function algorithmCandidate(overrides = {}) {
  return {
    title: '区间合并计数',
    bankType: 'leetcode',
    category: '数组',
    difficulty: '中等',
    questionType: '编程题',
    content: '给定区间数组，合并重叠区间并返回合并后的数量。',
    answer: '排序后线性扫描，时间复杂度 O(n log n)。',
    tags: ['数组', '排序'],
    codingMeta: {
      language: 'python',
      functionName: 'merge_count',
      signature: 'merge_count(intervals)',
      template: 'def merge_count(intervals):\n    # TODO: implement\n    pass\n',
      parameterCount: 1,
      tests: [
        {
          name: '公开样例',
          args: [
            [
              [1, 3],
              [2, 4],
            ],
          ],
          expected: 1,
          sample: true,
        },
        { name: '空数组', args: [[]], expected: 0, sample: false },
        {
          name: '互不重叠',
          args: [
            [
              [1, 2],
              [3, 4],
            ],
          ],
          expected: 2,
          sample: false,
        },
      ],
    },
    ...overrides,
  }
}

function qaChoiceCandidate(overrides = {}) {
  return {
    title: '线程池拒绝策略',
    bankType: 'qa',
    category: 'Java 并发',
    difficulty: '中等',
    questionType: '单选',
    content: '当线程池和工作队列都已满时，哪种拒绝策略会在调用线程中执行任务？\n\nA. AbortPolicy\nB. CallerRunsPolicy',
    answer: 'B',
    tags: ['Java 并发', '线程池'],
    ...overrides,
  }
}

async function fillQaBasics(wrapper) {
  await wrapper.find('input[placeholder="例如：解释 Agent 工具调用的失败恢复机制"]').setValue('Agent 并发基础')
  await wrapper.find('input[placeholder="例如：Agent 工程"]').setValue('Agent 工程')
}

async function fillAlgorithmBasics(wrapper) {
  await wrapper.find('input[placeholder="例如：合并重叠区间"]').setValue('数组求和')
  await wrapper.find('input[placeholder="例如：数组与排序"]').setValue('数组')
}

beforeEach(() => {
  mocks.createQuestion.mockReset().mockResolvedValue({ questionId: 'q-new' })
  mocks.extractInterviewDocument.mockReset().mockResolvedValue({
    fileName: 'reference.pdf',
    text: '动态规划状态转移与复杂度分析',
    characterCount: 14,
    truncated: false,
  })
  mocks.generateQuestions.mockReset().mockResolvedValue({ count: 1, items: [algorithmCandidate()] })
  mocks.importQuestions.mockReset().mockResolvedValue({ count: 1, items: [] })
  mocks.updateQuestion.mockReset().mockResolvedValue(editableQuestion())
})

describe('QuestionEditModal', () => {
  it('guides QA creation step by step and only saves on the final step', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    expect(wrapper.find('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.find('#question-maintain-title').text()).toBe('新增问答题')
    expect(wrapper.text()).toContain('先确定知识分类和问答题型')
    expect(wrapper.find('.question-guidance-card').exists()).toBe(false)
    expect(wrapper.find('select[aria-required="true"]').element.value).toBe('简答')
    expect(wrapper.find('.question-wizard-save').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-previous').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-next').text()).toBe('下一步')
    expect(wrapper.findAll('.question-wizard-steps button')[1].attributes()).toHaveProperty('disabled')
    expect(wrapper.find('.question-segmented-control button.active').text()).toBe('中等')

    await fillQaBasics(wrapper)
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.practice-section').text()).toContain('问答题干')
    expect(wrapper.find('.question-wizard-save').exists()).toBe(false)
    await wrapper.find('#question-content-markdown').setValue('请说明线程池的核心参数。')
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.find('.practice-section').text()).toContain('参考答案 / 评分要点')
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存题目')
    await wrapper.find('.question-answer-textarea').setValue('核心参数包括核心线程数、最大线程数和队列。')
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()

    expect(mocks.createQuestion).toHaveBeenCalledWith(
      expect.objectContaining({ bankType: 'qa', questionType: '简答', difficulty: '中等' }),
    )
    wrapper.unmount()
  })

  it('switches the QA manual form to choice-specific fields and prompts', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    const questionTypeSelect = wrapper.find('.maintain-field-grid select[aria-required="true"]')
    await questionTypeSelect.setValue('单选')
    expect(wrapper.find('input[placeholder="例如：以下关于 Agent 工具权限的说法哪项正确"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('选择题需要在下一步维护至少两个选项')

    await wrapper.find('input[placeholder="例如：以下关于 Agent 工具权限的说法哪项正确"]').setValue('Agent 工具权限')
    await wrapper.find('input[placeholder="例如：Agent 工程"]').setValue('Agent 安全')
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.text()).toContain('问答题干与选项')
    expect(wrapper.find('#question-content-markdown').attributes('placeholder')).toBe(
      '请输入完整题干，不要在这里重复填写选项',
    )
    expect(wrapper.findAll('.choice-option-row')).toHaveLength(4)
    await wrapper.find('#question-content-markdown').setValue('以下关于线程池拒绝策略的描述哪项正确？')
    await wrapper.findAll('.choice-option-row input')[0].setValue('AbortPolicy 会直接抛出异常')
    await wrapper.findAll('.choice-option-row input')[1].setValue('所有拒绝策略都会静默丢弃任务')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.text()).toContain('正确选项编号')
    expect(wrapper.text()).toContain('填写唯一正确选项的编号')
    expect(wrapper.find('input[placeholder="例如：A"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('generates editable candidates and imports only after explicit review', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.findAll('.question-wizard-steps button')).toHaveLength(3)
    expect(wrapper.find('.question-guidance-card').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-save').exists()).toBe(false)
    expect(wrapper.find('select').element.value).toBe('python')
    expect(wrapper.find('input[type="number"]').element.value).toBe('3')
    expect(wrapper.find('.question-segmented-control button.active').text()).toBe('中等')

    await wrapper.find('input[placeholder="例如：动态规划、图论、二分查找"]').setValue('区间问题')
    await wrapper.find('input[placeholder="例如：动态规划"]').setValue('数组')
    await wrapper.find('input[type="number"]').setValue(1)
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.text()).toContain('LeetCode 链接只作为来源标识')
    await wrapper
      .find('input[placeholder="https://leetcode.com/problems/two-sum/"]')
      .setValue('https://leetcode.com/problems/merge-intervals/')
    expect(wrapper.find('.question-wizard-next').text()).toBe('生成候选题')
    await wrapper.find('.question-wizard-next').trigger('click')
    await flushPromises()

    expect(mocks.generateQuestions).toHaveBeenCalledWith(
      expect.objectContaining({
        bankType: 'leetcode',
        questionType: '编程题',
        topic: '区间问题',
        category: '数组',
        difficulty: '中等',
        language: 'python',
        count: 1,
        sourceUrl: 'https://leetcode.com/problems/merge-intervals/',
      }),
    )
    expect(mocks.importQuestions).not.toHaveBeenCalled()
    expect(wrapper.findAll('.question-candidate-card')).toHaveLength(1)
    expect(wrapper.find('.question-wizard-save').text()).toBe('确认导入 1 道题')

    await wrapper.find('.question-wizard-save').trigger('click')
    expect(wrapper.find('.question-wizard-error').text()).toBe('请先核对并确认候选题 1')
    await wrapper.find('.candidate-field-grid input').setValue('人工修订后的区间题')
    expect(wrapper.find('.question-wizard-error').exists()).toBe(false)
    await wrapper.find('.candidate-confirm-toggle input').setValue(true)
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()

    expect(mocks.importQuestions).toHaveBeenCalledWith({
      items: [
        expect.objectContaining({
          title: '人工修订后的区间题',
          bankType: 'leetcode',
          codingMeta: expect.objectContaining({
            functionName: 'merge_count',
            parameterCount: 1,
            tests: expect.arrayContaining([expect.objectContaining({ sample: true })]),
          }),
        }),
      ],
    })
    wrapper.unmount()
  })

  it('uses QA-specific AI prompts and imports editable choice candidates', async () => {
    mocks.generateQuestions.mockResolvedValueOnce({ count: 1, items: [qaChoiceCandidate()] })
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('#question-maintain-title').text()).toBe('新增问答题')
    expect(wrapper.find('.question-guidance-card').exists()).toBe(false)
    expect(wrapper.find('input[placeholder="例如：Agent 工程、RAG 与模型评测"]').exists()).toBe(true)

    const qaTypeSelect = wrapper.find('.ai-generate-panel select[aria-required="true"]')
    await qaTypeSelect.setValue('单选')
    await wrapper.find('input[placeholder="例如：Agent 工程、RAG 与模型评测"]').setValue('Java 线程池')
    await wrapper.find('input[placeholder="例如：Agent 工程"]').setValue('Agent 安全')
    await wrapper.find('input[type="number"]').setValue(1)
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.text()).toContain('知识资料 / 参考文本')
    expect(wrapper.find('.question-source-textarea').attributes('placeholder')).toContain('技术文档、岗位要求')
    expect(wrapper.find('.question-requirements-textarea').attributes('placeholder')).toContain('4 个互斥选项')
    await wrapper.find('.question-wizard-next').trigger('click')
    await flushPromises()

    expect(mocks.generateQuestions).toHaveBeenCalledWith(
      expect.objectContaining({ bankType: 'qa', questionType: '单选', topic: 'Java 线程池' }),
    )
    expect(wrapper.findAll('.question-candidate-card .choice-option-row')).toHaveLength(2)
    expect(wrapper.find('.candidate-content-textarea').element.value).not.toContain('A. AbortPolicy')
    expect(wrapper.text()).toContain('已核对题干、选项和正确答案')

    await wrapper.find('.candidate-confirm-toggle input').setValue(true)
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()
    expect(mocks.importQuestions).toHaveBeenCalledWith({
      items: [
        expect.objectContaining({
          bankType: 'qa',
          questionType: '单选',
          answer: 'B',
          content: expect.stringContaining('A. AbortPolicy'),
        }),
      ],
    })
    wrapper.unmount()
  })

  it('keeps extracted document text when a later extraction fails', async () => {
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
    await wrapper.find('input[placeholder="例如：动态规划"]').setValue('动态规划')
    await wrapper.find('.question-wizard-next').trigger('click')

    const input = wrapper.find('.doc-upload-box input[type="file"]')
    const pdf = new File(['pdf'], 'reference.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [pdf] })
    await input.trigger('change')
    expect(wrapper.find('.doc-upload-box b').text()).toBe('正在读取')
    expect(wrapper.find('.question-wizard-next').attributes()).toHaveProperty('disabled')

    resolveExtraction({
      fileName: 'reference.pdf',
      text: '动态规划状态转移与复杂度分析',
      characterCount: 22000,
      truncated: true,
    })
    await flushPromises()
    expect(wrapper.find('.doc-upload-box').text()).toContain('reference.pdf')
    expect(wrapper.text()).toContain('已提取前 20000 个字符')

    mocks.extractInterviewDocument.mockRejectedValueOnce(new Error('Word 文档解析失败'))
    const docx = new File(['broken'], 'broken.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [docx] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.find('.question-wizard-error').text()).toBe('Word 文档解析失败')
    expect(wrapper.find('.doc-upload-box').text()).toContain('reference.pdf')
    await wrapper.find('.question-wizard-next').trigger('click')
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
    const contentTabs = wrapper.find('[aria-label="题目描述编辑模式"]')
    await wrapper.find('#question-content-markdown').setValue('## 扩容流程\n\n- 容量翻倍\n- 重新分桶')
    await contentTabs.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('[aria-label="题目描述 Markdown 预览"]').exists()).toBe(true)
    expect(wrapper.findComponent(PracticeMarkdown).props('content')).toContain('## 扩容流程')

    await wrapper.find('.question-wizard-next').trigger('click')
    const answerTabs = wrapper.find('[aria-label="参考答案编辑模式"]')
    await wrapper.find('#question-answer-markdown').setValue('`resize()` 会重新计算桶位置。')
    await answerTabs.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(true)
    expect(wrapper.findComponent(PracticeMarkdown).props('content')).toContain('`resize()`')
    wrapper.unmount()
  })

  it('provides a visual coding test editor without injecting a hardcoded question', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()

    expect(wrapper.text()).not.toContain('不再手写整段 codingMeta 或测试 JSON')
    expect(wrapper.find('.question-guidance-card').exists()).toBe(false)
    expect(wrapper.find('.question-example-button').exists()).toBe(false)
    await fillAlgorithmBasics(wrapper)
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.findAllComponents(CodeHighlightEditor)).toHaveLength(1)
    expect(wrapper.find('.coding-meta-editor select').element.value).toBe('python')
    expect(wrapper.find('input[placeholder="例如：twoSum"]').element.value).toBe('solution')
    expect(wrapper.find('.question-code-template-textarea').element.value).toContain('def solution')
    await wrapper.find('#question-content-markdown').setValue('给定整数数组，返回所有元素之和。')
    await wrapper.find('.question-wizard-next').trigger('click')

    expect(wrapper.find('.question-tests-textarea').exists()).toBe(false)
    expect(wrapper.findAll('.coding-test-card')).toHaveLength(1)
    await wrapper.findAll('.coding-test-value')[0].setValue('[[1,2,3]]')
    await wrapper.findAll('.coding-test-value')[1].setValue('6')
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()

    expect(mocks.createQuestion).toHaveBeenCalledWith(
      expect.objectContaining({
        bankType: 'leetcode',
        codingMeta: expect.objectContaining({
          language: 'python',
          functionName: 'solution',
          parameterCount: 1,
          tests: expect.arrayContaining([expect.objectContaining({ sample: true })]),
        }),
      }),
    )
    wrapper.unmount()
  })

  it('blocks future steps and reports the first incomplete field before advancing', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('qa')
    await nextTick()

    await wrapper.findAll('.question-wizard-steps button')[2].trigger('click')
    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('基本信息')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.question-wizard-error').text()).toBe('请填写题目标题')

    await fillQaBasics(wrapper)
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('问答题干')
    expect(wrapper.findAll('.question-wizard-steps button')[1].attributes()).not.toHaveProperty('disabled')
    wrapper.unmount()
  })

  it('keeps the coding-test step open and identifies the invalid row', async () => {
    const wrapper = mountModal()
    wrapper.vm.openCreate('leetcode')
    await nextTick()
    await fillAlgorithmBasics(wrapper)
    await wrapper.find('.question-wizard-next').trigger('click')
    await wrapper.find('#question-content-markdown').setValue('给定数组并返回总和。')
    await wrapper.find('.question-wizard-next').trigger('click')
    await wrapper.findAll('.coding-test-value')[0].setValue('not-json')
    await wrapper.findAll('.coding-test-value')[1].setValue('6')
    await wrapper.find('.question-wizard-save').trigger('click')

    expect(wrapper.find('.question-wizard-steps button[aria-current="step"]').text()).toContain('判题与题解')
    expect(wrapper.find('.question-wizard-error').text()).toBe('第 1 条测试用例的参数 JSON 格式不正确')
    expect(mocks.createQuestion).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('adds and removes question tags one at a time', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

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
    wrapper.unmount()
  })

  it('keeps edit mode focused on updating the current question', async () => {
    const wrapper = mountModal()
    wrapper.vm.openEdit(editableQuestion())
    await nextTick()

    expect(wrapper.text()).toContain('编辑问答题')
    expect(wrapper.find('.interview-modal-tabs').exists()).toBe(false)
    expect(wrapper.find('.question-wizard-save').exists()).toBe(false)
    await wrapper.findAll('.question-wizard-steps button')[2].trigger('click')
    expect(wrapper.find('.question-wizard-save').text()).toBe('保存修改')
    expect(wrapper.find('.question-answer-textarea--standalone').exists()).toBe(true)
    wrapper.unmount()
  })

  it('normalizes legacy coding metadata and updates exactly once', async () => {
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

    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.find('.coding-meta-editor select').element.value).toBe('python')
    expect(wrapper.find('.question-code-template-textarea').element.value).toContain('def two_sum')
    await wrapper.find('.question-wizard-next').trigger('click')
    expect(wrapper.findAll('.coding-test-card')).toHaveLength(1)
    await wrapper.find('.question-wizard-save').trigger('click')
    await flushPromises()

    expect(mocks.updateQuestion).toHaveBeenCalledTimes(1)
    expect(mocks.updateQuestion).toHaveBeenCalledWith(
      'q-legacy',
      expect.objectContaining({
        questionType: '编程题',
        codingMeta: expect.objectContaining({ language: 'python', functionName: 'two_sum', parameterCount: 2 }),
      }),
    )
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
    await wrapper.findAll('.question-wizard-steps button')[2].trigger('click')
    await wrapper.find('.question-wizard-save').trigger('click')
    await nextTick()

    expect(wrapper.find('.close').attributes()).toHaveProperty('disabled')
    expect(
      wrapper.findAll('.question-wizard-steps button').every((button) => button.attributes().disabled !== undefined),
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
