import { describe, expect, it } from 'vitest'
import {
  assertManualPracticeMatches,
  buildCodingMetaFromForm,
  buildDebugFormDefaults,
  buildDebugTestCase,
  buildQuestionPayload,
  codingResultSummary,
  displayExamTitle,
  examRuleTotal,
  formatExamStartedAt,
  isCurrentExam,
  selectedAnswerKeys,
  shouldShowExamOpening,
  validateAiForm,
  validatePracticeConfig,
  validateQuestionForm,
} from '../src/utils/interviewForm'

function baseForm(overrides = {}) {
  return {
    title: '题目',
    bankType: 'qa',
    category: 'Java',
    difficulty: '中等',
    questionType: '简答',
    tagsText: 'Java,集合',
    content: '请解释 HashMap 扩容',
    answer: '答案',
    options: [
      { key: 'A', text: '' },
      { key: 'B', text: '' },
    ],
    codingLanguage: 'python',
    codingFunctionName: '',
    codingSignature: '',
    codingTemplate: '',
    codingParameterCount: 1,
    codingTestsText: '',
    ...overrides,
  }
}

describe('displayExamTitle', () => {
  it('uses the persisted title and handles missing values', () => {
    expect(displayExamTitle({ title: '算法随机组卷' })).toBe('算法随机组卷')
    expect(displayExamTitle({})).toBe('未命名练习')
  })
})

describe('practice history formatting', () => {
  it('formats valid start times and handles missing values', () => {
    expect(formatExamStartedAt(new Date(2026, 6, 21, 8, 5))).toBe('2026-07-21 08:05')
    expect(formatExamStartedAt('invalid')).toBe('时间未知')
    expect(formatExamStartedAt('')).toBe('时间未知')
  })

  it('identifies the currently opened record', () => {
    expect(isCurrentExam({ examId: 'exam-1' }, { examId: 'exam-1' })).toBe(true)
    expect(isCurrentExam({ examId: 'exam-1' }, { examId: 'exam-2' })).toBe(false)
    expect(isCurrentExam({}, null)).toBe(false)
  })
})

describe('shouldShowExamOpening', () => {
  it('keeps the transition visible until the target exam is ready', () => {
    expect(shouldShowExamOpening('exam-2', null)).toBe(true)
    expect(shouldShowExamOpening('exam-2', { examId: 'exam-1' })).toBe(true)
    expect(shouldShowExamOpening('exam-2', { examId: 'exam-2' }, true)).toBe(true)
    expect(shouldShowExamOpening('exam-2', { examId: 'exam-2' }, false)).toBe(false)
  })

  it('does not mask the idle or failed state', () => {
    expect(shouldShowExamOpening('', null)).toBe(false)
    expect(shouldShowExamOpening('exam-2', null, false, '练习详情加载失败')).toBe(false)
  })
})

describe('validateQuestionForm', () => {
  it('passes a valid short-answer form', () => {
    expect(() => validateQuestionForm(baseForm())).not.toThrow()
  })

  it('requires at least two choice options', () => {
    const form = baseForm({
      questionType: '单选',
      options: [
        { key: 'A', text: '选项A' },
        { key: 'B', text: '' },
      ],
      answer: 'A',
    })
    expect(() => validateQuestionForm(form)).toThrow('选择题至少需要 2 个有效选项')
  })

  it('accepts non-empty leetcode code even when no function declaration can be recognized', () => {
    const form = baseForm({
      bankType: 'leetcode',
      questionType: '编程题',
      codingTemplate: '# 可填写脚本、代码片段或伪代码\nresult = input_data',
      codingLanguage: 'python',
      codingTestsText: '[{"name":"示例","args":[1],"expected":1}]',
    })
    expect(() => validateQuestionForm(form)).not.toThrow()
  })

  it('allows leetcode questions without test cases', () => {
    const form = baseForm({
      bankType: 'leetcode',
      questionType: '编程题',
      codingTemplate: 'def solution(value):\n    return value',
      codingTestsText: '',
    })
    expect(() => validateQuestionForm(form)).not.toThrow()
  })

  it('still requires a non-empty leetcode code template', () => {
    const form = baseForm({ bankType: 'leetcode', questionType: '编程题', codingTemplate: '  ' })
    expect(() => validateQuestionForm(form)).toThrow('请填写初始代码模板')
  })
})

describe('validateAiForm', () => {
  it('requires topic or document', () => {
    expect(() => validateAiForm({ topic: '', documentText: '', count: 5 })).toThrow('请填写方向主题或上传参考资料')
  })

  it('requires explicit generation settings and bounds the count', () => {
    const valid = {
      topic: 'Agent 工程',
      bankType: 'qa',
      category: 'Agent 工程',
      difficulty: '中等',
      questionType: '简答',
      count: 5,
    }
    expect(() => validateAiForm({ ...valid, bankType: '' })).toThrow('请选择题库')
    expect(() => validateAiForm({ ...valid, count: 0 })).toThrow('生成数量需在 1-20 之间')
    expect(() => validateAiForm(valid)).not.toThrow()
  })
})

describe('examRuleTotal and validatePracticeConfig', () => {
  it('sums rule counts ignoring negatives', () => {
    expect(examRuleTotal([{ count: 3 }, { count: 2 }, { count: -5 }])).toBe(5)
  })

  it('rejects incomplete practice config', () => {
    expect(() =>
      validatePracticeConfig({ title: '练习', durationMinutes: 30, answerMode: '', rules: [{ count: 0 }] }),
    ).toThrow('请选择练习模式')
    expect(() =>
      validatePracticeConfig({
        title: '练习',
        durationMinutes: 30,
        answerMode: 'hidden',
        rules: [{ bankType: 'qa', questionType: '简答', count: 0 }],
      }),
    ).toThrow('请至少配置 1 道题')
  })

  it('accepts a valid config with explicitly selected fields', () => {
    expect(() =>
      validatePracticeConfig({
        title: '练习',
        durationMinutes: 30,
        answerMode: 'hidden',
        rules: [{ bankType: 'qa', questionType: '简答', count: 5 }],
      }),
    ).not.toThrow()
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
  it('allows empty test JSON and preserves compatible metadata defaults', () => {
    const meta = buildCodingMetaFromForm(
      baseForm({ bankType: 'leetcode', codingTemplate: 'def solution(value):\n    return value', codingTestsText: '' }),
    )
    expect(meta.tests).toEqual([])
    expect(meta.parameterCount).toBe(1)
  })

  it('throws on invalid test JSON', () => {
    const form = baseForm({ bankType: 'leetcode', codingTestsText: '{ not json' })
    expect(() => buildCodingMetaFromForm(form)).toThrow('测试用例 JSON 格式不正确')
  })

  it('builds meta with extracted function name', () => {
    const form = baseForm({
      bankType: 'leetcode',
      codingLanguage: 'python',
      codingTemplate: 'def two_sum(nums, target):\n    pass\n',
      codingParameterCount: 1,
      codingTestsText: '[{"name":"示例","args":[[2,7],9],"expected":[0,1],"sample":true}]',
    })
    const meta = buildCodingMetaFromForm(form)
    expect(meta.language).toBe('python')
    expect(meta.functionName).toBe('two_sum')
    expect(meta.parameterCount).toBe(2)
    expect(meta.tests).toHaveLength(1)
  })

  it('detects the language from code instead of trusting the hidden compatibility value', () => {
    const meta = buildCodingMetaFromForm(
      baseForm({
        bankType: 'leetcode',
        codingLanguage: 'python',
        codingTemplate: 'const solve = (value) => value + 1',
        codingTestsText: '[{"name":"示例","args":[1],"expected":2}]',
      }),
    )
    expect(meta.language).toBe('javascript')
    expect(meta.functionName).toBe('solution')
    expect(meta.parameterCount).toBe(1)
  })

  it('falls back to the stored function name or solution for arbitrary code', () => {
    const common = {
      bankType: 'leetcode',
      codingLanguage: 'python',
      codingTemplate: 'result = input_data',
      codingTestsText: '[{"name":"示例","args":[1],"expected":1}]',
    }
    expect(buildCodingMetaFromForm(baseForm({ ...common, codingFunctionName: 'run_custom' })).functionName).toBe(
      'run_custom',
    )
    expect(buildCodingMetaFromForm(baseForm(common)).functionName).toBe('solution')
  })
})

describe('buildQuestionPayload', () => {
  it('inlines options into content for choice questions', () => {
    const form = baseForm({
      questionType: '单选',
      content: '选哪个',
      options: [
        { key: 'A', text: '甲' },
        { key: 'B', text: '乙' },
      ],
      answer: ' A ',
      tagsText: 'x,y',
    })
    const payload = buildQuestionPayload(form)
    expect(payload.content).toContain('A. 甲')
    expect(payload.answer).toBe('A')
    expect(payload.tags).toEqual(['x', 'y'])
    expect(payload.options).toBeUndefined()
    expect(payload.tagsText).toBeUndefined()
  })

  it('preserves spaces inside tags added through the tag editor', () => {
    const payload = buildQuestionPayload(
      baseForm({
        tags: ['Java', 'Spring Boot', 'Java'],
        tagsText: '',
      }),
    )
    expect(payload.tags).toEqual(['Java', 'Spring Boot'])
  })

  it('attaches codingMeta for leetcode and strips coding form fields', () => {
    const form = baseForm({
      bankType: 'leetcode',
      codingLanguage: 'python',
      codingTemplate: 'def solve(value):\n    pass\n',
      codingTestsText: '[{"name":"示例","args":[1],"expected":1,"sample":true}]',
    })
    const payload = buildQuestionPayload(form)
    expect(payload.questionType).toBe('编程题')
    expect(payload.codingMeta.functionName).toBe('solve')
    expect(payload.codingTemplate).toBeUndefined()
    expect(payload.codingTestsText).toBeUndefined()
  })
})

describe('debug test cases', () => {
  it('loads the editable default from structured coding metadata', () => {
    const item = {
      codingMeta: {
        parameterCount: 1,
        tests: [
          {
            name: '示例',
            args: [
              [
                [1, 3],
                [2, 6],
              ],
            ],
            expected: [[1, 6]],
            sample: true,
          },
        ],
      },
    }
    expect(buildDebugFormDefaults(item)).toEqual({ argsText: '[[1,3],[2,6]]', expectedText: '[[1,6]]' })
  })

  it('wraps a single parameter and keeps multiple parameters ordered', () => {
    expect(buildDebugTestCase('[[1,2],[2,3]]', '', 1)).toEqual({
      name: '自定义调试',
      args: [
        [
          [1, 2],
          [2, 3],
        ],
      ],
      debug: true,
    })
    expect(buildDebugTestCase('[[2,7],9]', '[0,1]', 2)).toEqual({
      name: '自定义调试',
      args: [[2, 7], 9],
      expected: [0, 1],
      debug: true,
    })
  })

  it('rejects invalid multi-parameter input or expected JSON', () => {
    expect(() => buildDebugTestCase('{"value":1}', '', 2)).toThrow('多参数函数需要使用 JSON 数组')
    expect(() => buildDebugTestCase('[1]', '{bad json', 1)).toThrow('期望结果 JSON 格式不正确')
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
