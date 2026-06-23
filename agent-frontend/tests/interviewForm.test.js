import { describe, expect, it } from 'vitest'
import {
  assertManualPracticeMatches,
  buildCodingMetaFromForm,
  buildQuestionPayload,
  codingResultSummary,
  defaultPracticeTitle,
  displayExamTitle,
  examRuleTotal,
  selectedAnswerKeys,
  validateAiForm,
  validatePracticeConfig,
  validateQuestionForm,
} from '../src/utils/interviewForm'

function baseForm(overrides = {}) {
  return {
    title: '题目',
    bankType: 'baguwen',
    category: 'Java',
    difficulty: '中等',
    questionType: '简答',
    tagsText: 'Java,集合',
    content: '请解释 HashMap 扩容',
    answer: '答案',
    options: [{ key: 'A', text: '' }, { key: 'B', text: '' }],
    codingLanguage: 'python',
    codingFunctionName: '',
    codingSignature: '',
    codingTemplate: '',
    codingTestsText: '',
    ...overrides,
  }
}

describe('defaultPracticeTitle', () => {
  it('formats date and time with zero padding', () => {
    const title = defaultPracticeTitle(new Date(2026, 0, 5, 9, 7))
    expect(title).toBe('随机组卷 2026-01-05 09:07')
  })
})

describe('displayExamTitle', () => {
  it('rewrites legacy wording', () => {
    expect(displayExamTitle({ title: 'LeetCode 模拟练习' })).toBe('算法 随机组卷')
    expect(displayExamTitle({})).toBe('未命名练习')
  })
})

describe('validateQuestionForm', () => {
  it('passes a valid short-answer form', () => {
    expect(() => validateQuestionForm(baseForm())).not.toThrow()
  })

  it('requires at least two choice options', () => {
    const form = baseForm({ questionType: '单选', options: [{ key: 'A', text: '选项A' }, { key: 'B', text: '' }], answer: 'A' })
    expect(() => validateQuestionForm(form)).toThrow('选择题至少需要 2 个有效选项')
  })

  it('requires a recognizable function for leetcode', () => {
    const form = baseForm({ bankType: 'leetcode', codingTemplate: '# no function here', codingLanguage: 'python' })
    expect(() => validateQuestionForm(form)).toThrow('代码模板中需要包含可识别的函数或方法声明')
  })
})

describe('validateAiForm', () => {
  it('requires topic or document', () => {
    expect(() => validateAiForm({ topic: '', documentText: '', count: 5 })).toThrow('请填写方向主题或上传参考资料')
  })

  it('bounds the count', () => {
    expect(() => validateAiForm({ topic: 'Java', count: 0 })).toThrow('生成数量需在 1-20 之间')
    expect(() => validateAiForm({ topic: 'Java', count: 5 })).not.toThrow()
  })
})

describe('examRuleTotal and validatePracticeConfig', () => {
  it('sums rule counts ignoring negatives', () => {
    expect(examRuleTotal([{ count: 3 }, { count: 2 }, { count: -5 }])).toBe(5)
  })

  it('rejects empty practice config', () => {
    expect(() => validatePracticeConfig({ title: '练习', durationMinutes: 30, rules: [{ count: 0 }] })).toThrow('请至少配置 1 道题')
  })

  it('accepts a valid config', () => {
    expect(() => validatePracticeConfig({ title: '练习', durationMinutes: 30, rules: [{ count: 5 }] })).not.toThrow()
  })
})

describe('assertManualPracticeMatches', () => {
  it('throws when selected ids differ from returned questions', () => {
    const exam = { questions: [{ questionId: 'q1' }] }
    expect(() => assertManualPracticeMatches(exam, ['q1', 'q2'])).toThrow('练习内容与所选题目不一致，请刷新题库后重试')
  })

  it('passes when ids match regardless of order', () => {
    const exam = { questions: [{ questionId: 'q2' }, { questionId: 'q1' }] }
    expect(() => assertManualPracticeMatches(exam, ['q1', 'q2'])).not.toThrow()
  })
})

describe('buildCodingMetaFromForm', () => {
  it('throws on invalid test JSON', () => {
    const form = baseForm({ bankType: 'leetcode', codingTestsText: '{ not json' })
    expect(() => buildCodingMetaFromForm(form)).toThrow('测试用例 JSON 格式不正确')
  })

  it('builds meta with extracted function name', () => {
    const form = baseForm({ bankType: 'leetcode', codingLanguage: 'python', codingTemplate: 'def two_sum(*args):\n    pass\n', codingTestsText: '[]' })
    const meta = buildCodingMetaFromForm(form)
    expect(meta.language).toBe('python')
    expect(meta.functionName).toBe('two_sum')
    expect(meta.tests).toEqual([])
  })
})

describe('buildQuestionPayload', () => {
  it('inlines options into content for choice questions', () => {
    const form = baseForm({ questionType: '单选', content: '选哪个', options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }], answer: ' A ', tagsText: 'x,y' })
    const payload = buildQuestionPayload(form)
    expect(payload.content).toContain('A. 甲')
    expect(payload.answer).toBe('A')
    expect(payload.tags).toEqual(['x', 'y'])
    expect(payload.options).toBeUndefined()
    expect(payload.tagsText).toBeUndefined()
  })

  it('attaches codingMeta for leetcode and strips coding form fields', () => {
    const form = baseForm({ bankType: 'leetcode', codingLanguage: 'python', codingTemplate: 'def solve(*args):\n    pass\n', codingTestsText: '[]' })
    const payload = buildQuestionPayload(form)
    expect(payload.questionType).toBe('编程题')
    expect(payload.codingMeta.functionName).toBe('solve')
    expect(payload.codingTemplate).toBeUndefined()
    expect(payload.codingTestsText).toBeUndefined()
  })
})

describe('codingResultSummary', () => {
  it('handles missing result, message and pass ratio', () => {
    expect(codingResultSummary(null)).toBe('尚未运行')
    expect(codingResultSummary({ message: '未维护测试用例' })).toBe('未维护测试用例')
    expect(codingResultSummary({ rows: [{ passed: true }, { passed: false }] })).toBe('1 / 2 通过')
  })
})

describe('selectedAnswerKeys', () => {
  it('splits multi-choice answers across separators', () => {
    expect(selectedAnswerKeys('A,B、C D')).toEqual(['A', 'B', 'C', 'D'])
    expect(selectedAnswerKeys('')).toEqual([])
  })
})
