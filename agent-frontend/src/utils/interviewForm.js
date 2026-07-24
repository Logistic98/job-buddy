// Pure, side-effect-free helpers for the interview maintain/practice forms. Extracted from
// InterviewBank.vue so validation, payload shaping and title formatting can be unit-tested in
// isolation, while the component keeps only its reactive state and API orchestration. None of
// these functions read or mutate component state; everything flows through explicit arguments.

import { detectCodeLanguage } from './codeHighlight'
import { validateInteger, validateLength, validateTags } from './formValidation'
import {
  buildDefaultTemplate,
  defaultSignature,
  extractFunctionName,
  isChoiceType,
  normalizeCodingLanguage,
  requireText,
  splitCleanTags,
} from './interviewBank'

export function displayExamTitle(exam) {
  return String(exam?.title || '未命名练习')
}

export function shouldShowExamOpening(targetExamId, currentExam, detailLoading = false, error = '') {
  const targetId = String(targetExamId || '').trim()
  if (!targetId || String(error || '').trim()) return false
  return Boolean(detailLoading) || String(currentExam?.examId || '').trim() !== targetId
}

export function formatExamStartedAt(value) {
  if (!value) return '时间未知'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  const pad = (item) => String(item).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function isCurrentExam(exam, currentExam) {
  return Boolean(exam?.examId && currentExam?.examId && exam.examId === currentExam.examId)
}

// 按向导步骤校验手动录入表单，提交失败时用于定位首个需要修正的步骤。
export function validateQuestionStep(form, step) {
  const choice = isChoiceType(form.questionType)
  if (step === 0) {
    validateLength(form.title, '题目标题', { max: 200, required: true })
    requireText(form.bankType, '请选择题库')
    validateLength(form.category, '分类', { max: 64, required: true })
    requireText(form.difficulty, '请选择难度')
    requireText(form.questionType, '请选择题型')
    validateTags(form.tags, '标签', { maxCount: 20, maxLength: 32 })
    return
  }
  if (step === 1) {
    requireText(
      form.content,
      form.bankType === 'leetcode' ? '请填写算法题面' : choice ? '请填写选择题题干' : '请填写问答题题干',
    )
    if (choice) {
      const validOptions = form.options.map((item) => String(item.text || '').trim()).filter(Boolean)
      if (validOptions.length < 2) throw new Error('选择题至少需要 2 个有效选项')
    }
    if (form.bankType === 'leetcode') requireText(form.codingTemplate, '请填写初始代码模板')
    return
  }
  if (choice) {
    requireText(form.answer, '请填写正确答案')
    const validKeys = new Set(form.options.filter((item) => String(item.text || '').trim()).map((item) => item.key))
    const answerKeys = selectedAnswerKeys(form.answer)
    if (form.questionType === '单选' && answerKeys.length !== 1) throw new Error('单选题只能填写一个正确答案')
    if (!answerKeys.length || answerKeys.some((key) => !validKeys.has(key.toUpperCase())))
      throw new Error('正确答案必须使用现有选项编号')
  } else if (form.bankType === 'qa') {
    requireText(form.answer, '请填写参考答案和评分要点')
  }
  validateLength(form.answer, '参考答案', { max: 20000 })
  if (form.bankType === 'leetcode') buildCodingMetaFromForm(form)
}

// 校验完整题目表单；保存接口调用前仍执行全量校验，防止绕过步骤导航。
export function validateQuestionForm(form) {
  validateQuestionStep(form, 0)
  validateQuestionStep(form, 1)
  validateQuestionStep(form, 2)
}

export function validateAiForm(aiForm) {
  if (
    !String(aiForm.topic || '').trim() &&
    !String(aiForm.sourceUrl || '').trim() &&
    !String(aiForm.documentText || '').trim() &&
    !String(aiForm.requirements || '').trim()
  )
    throw new Error(aiSourceRequiredMessage(aiForm.bankType))
  validateAiStep(aiForm, 0)
  validateLength(aiForm.requirements, '补充要求', { max: 2000 })
  validateLength(aiForm.sourceUrl, 'LeetCode 题目链接', { max: 500 })
  validateLeetCodeSourceUrl(aiForm.sourceUrl)
  validateLength(aiForm.documentText, '参考资料', { max: 20000 })
}

export function validateAiStep(aiForm, step) {
  validateLength(aiForm.topic, '方向主题', { max: 200 })
  requireText(aiForm.bankType, '请选择题库')
  validateLength(aiForm.category, '分类', { max: 64, required: true })
  requireText(aiForm.difficulty, '请选择难度')
  requireText(aiForm.questionType, '请选择题型')
  if (aiForm.bankType === 'leetcode' && !['python', 'java', 'javascript'].includes(aiForm.language))
    throw new Error('请选择生成代码语言')
  validateInteger(aiForm.count, '生成数量', { min: 1, max: 20 })
  if (step > 0) {
    validateLength(aiForm.sourceUrl, 'LeetCode 题目链接', { max: 500 })
    validateLeetCodeSourceUrl(aiForm.sourceUrl)
    if (
      !String(aiForm.topic || '').trim() &&
      !String(aiForm.sourceUrl || '').trim() &&
      !String(aiForm.documentText || '').trim() &&
      !String(aiForm.requirements || '').trim()
    )
      throw new Error(aiSourceRequiredMessage(aiForm.bankType))
  }
}

function aiSourceRequiredMessage(bankType) {
  return bankType === 'leetcode'
    ? '请填写算法主题、LeetCode 链接、题面或上传算法资料'
    : '请填写知识主题、参考文本、出题要求或上传问答资料'
}

function validateLeetCodeSourceUrl(value) {
  const sourceUrl = String(value || '').trim()
  if (!sourceUrl) return
  let parsed
  try {
    parsed = new URL(sourceUrl)
  } catch (_) {
    throw new Error('LeetCode 题目链接格式不正确')
  }
  const supportedHosts = new Set(['leetcode.com', 'www.leetcode.com', 'leetcode.cn', 'www.leetcode.cn'])
  if (
    parsed.protocol !== 'https:' ||
    !supportedHosts.has(parsed.hostname) ||
    !/^\/problems\/[^/?#]+\/?$/.test(parsed.pathname)
  )
    throw new Error('请输入 leetcode.com 或 leetcode.cn 的标准 HTTPS 题目链接')
}

export function examRuleTotal(rules = []) {
  return rules.reduce((sum, rule) => sum + Math.max(0, Number(rule.count || 0)), 0)
}

export function validatePracticeConfig(examConfig) {
  requireText(examConfig.title, '请填写练习名称')
  validateLength(examConfig.title, '练习名称', { max: 120, required: true })
  validateInteger(examConfig.durationMinutes, '限时时长', { min: 1, max: 240 })
  requireText(examConfig.answerMode, '请选择练习模式')
  if (!examRuleTotal(examConfig.rules)) throw new Error('请至少配置 1 道题')
  for (const rule of examConfig.rules) {
    requireText(rule.bankType, '请选择组卷规则的题库')
    requireText(rule.questionType, '请选择组卷规则的题型')
    validateInteger(rule.count, '单条组卷规则题数', { min: 1, max: 50 })
  }
}

// 校验手动组卷返回的练习题目集合与所选题目是否一致，不一致抛错以阻断错配练习。
export function assertManualPracticeMatches(exam, questionIds) {
  const expected = Array.from(new Set(questionIds.map((id) => String(id || '').trim()).filter(Boolean))).sort()
  const actual = (exam?.questions || [])
    .map((item) => String(item.questionId || '').trim())
    .filter(Boolean)
    .sort()
  const same = expected.length === actual.length && expected.every((id, index) => id === actual[index])
  if (!same) throw new Error('练习内容与所选题目不一致，请刷新题库后重试')
}

export function buildCodingMetaFromForm(form) {
  const tests = buildCodingTests(form)
  if (!tests.length) throw new Error('请至少添加 1 条测试用例')
  const parameterCount = tests[0].args.length
  if (parameterCount < 1 || parameterCount > 10) throw new Error('每条测试用例需包含 1-10 个函数参数')
  tests.forEach((test, index) => {
    if (test.args.length !== parameterCount) throw new Error(`第 ${index + 1} 条测试用例的参数数量与首条不一致`)
  })
  if (!tests.some((test) => test.sample)) tests[0].sample = true
  const language = normalizeCodingLanguage(
    form.codingLanguage || detectCodeLanguage(`${form.codingTemplate || ''}\n${form.content || ''}`, 'python'),
  )
  const detectedFunctionName = extractFunctionName(form.codingTemplate, language)
  const configuredFunctionName = String(form.codingFunctionName || '').trim()
  if (detectedFunctionName && configuredFunctionName && detectedFunctionName !== configuredFunctionName)
    throw new Error('函数入口与代码模板中的函数名不一致，请重建模板或同步修改代码')
  const functionName = configuredFunctionName || detectedFunctionName || 'solution'
  if (!/^[A-Za-z_$][\w$]*$/.test(functionName)) throw new Error('函数入口名称格式不正确')
  return {
    language,
    functionName,
    parameterCount,
    signature: defaultSignature(functionName, language),
    template: form.codingTemplate || buildDefaultTemplate(functionName, language),
    tests,
  }
}

function buildCodingTests(form) {
  if (Array.isArray(form.codingTests)) {
    return form.codingTests.map((row, index) => parseCodingTestRow(row, index)).filter(Boolean)
  }
  const rawText = String(form.codingTestsText || '').trim()
  if (!rawText) return []
  let parsed
  try {
    parsed = JSON.parse(rawText)
  } catch (_) {
    throw new Error('测试用例 JSON 格式不正确')
  }
  if (!Array.isArray(parsed)) throw new Error('测试用例 JSON 必须是数组')
  return parsed.map((test, index) => normalizeStructuredTest(test, index))
}

function parseCodingTestRow(row, index) {
  const name = String(row?.name || '').trim()
  const argsText = String(row?.argsText || '').trim()
  const expectedText = String(row?.expectedText || '').trim()
  if (!name && !argsText && !expectedText) return null
  if (!argsText) throw new Error(`请填写第 ${index + 1} 条测试用例的参数`)
  if (!expectedText) throw new Error(`请填写第 ${index + 1} 条测试用例的期望结果`)
  let args
  let expected
  try {
    args = JSON.parse(argsText)
  } catch (_) {
    throw new Error(`第 ${index + 1} 条测试用例的参数 JSON 格式不正确`)
  }
  if (!Array.isArray(args)) throw new Error(`第 ${index + 1} 条测试用例的参数必须是 JSON 数组`)
  try {
    expected = JSON.parse(expectedText)
  } catch (_) {
    throw new Error(`第 ${index + 1} 条测试用例的期望结果 JSON 格式不正确`)
  }
  return {
    name: name || `用例 ${index + 1}`,
    args,
    expected,
    sample: Boolean(row?.sample),
  }
}

function normalizeStructuredTest(test, index) {
  if (
    !test ||
    typeof test !== 'object' ||
    !Array.isArray(test.args) ||
    !Object.prototype.hasOwnProperty.call(test, 'expected')
  ) {
    throw new Error(`第 ${index + 1} 条测试用例必须包含 args 数组和 expected 字段`)
  }
  return {
    name: String(test.name || `用例 ${index + 1}`),
    args: test.args,
    expected: test.expected,
    sample: Boolean(test.sample),
  }
}

export function createCodingTestRow(test = {}, index = 0) {
  return {
    id: String(test.id || `coding-test-${Date.now()}-${index}-${Math.random().toString(16).slice(2)}`),
    name: String(test.name || `用例 ${index + 1}`),
    argsText: Object.prototype.hasOwnProperty.call(test, 'args') ? JSON.stringify(test.args) : '',
    expectedText: Object.prototype.hasOwnProperty.call(test, 'expected') ? JSON.stringify(test.expected) : '',
    sample: Object.prototype.hasOwnProperty.call(test, 'sample') ? Boolean(test.sample) : index === 0,
  }
}

export function formatCodingTestRows(tests) {
  const rows = Array.isArray(tests) ? tests.map((test, index) => createCodingTestRow(test, index)) : []
  return rows.length ? rows : [createCodingTestRow({}, 0)]
}

export function buildQuestionPayload(form) {
  const options = form.options
    .map((item) => ({ key: item.key, text: String(item.text || '').trim() }))
    .filter((item) => item.text)
  const content =
    isChoiceType(form.questionType) && options.length
      ? `${form.content.trim()}\n\n${options.map((item) => `${item.key}. ${item.text}`).join('\n')}`
      : form.content
  const payload = {
    ...form,
    content,
    answer: form.answer.trim(),
    tags: Array.isArray(form.tags)
      ? Array.from(new Set(form.tags.map((tag) => String(tag || '').trim()).filter(Boolean)))
      : splitCleanTags(form.tagsText),
    bankType: form.bankType,
    questionType: form.bankType === 'leetcode' ? '编程题' : form.questionType,
  }
  if (payload.bankType === 'leetcode') payload.codingMeta = buildCodingMetaFromForm(form)
  delete payload.tagsText
  delete payload.options
  delete payload.codingLanguage
  delete payload.codingFunctionName
  delete payload.codingSignature
  delete payload.codingTemplate
  delete payload.codingParameterCount
  delete payload.codingTestsText
  delete payload.codingTests
  return payload
}

export function buildDebugFormDefaults(item) {
  const meta = item?.codingMeta && typeof item.codingMeta === 'object' ? item.codingMeta : {}
  const parameterCount = Math.max(0, Number(meta.parameterCount || 0))
  const tests = Array.isArray(meta.tests) ? meta.tests : []
  const sample = tests.find((test) => test?.sample) || tests[0]
  if (!sample) return { argsText: parameterCount === 1 ? 'null' : '[]', expectedText: '' }

  const storedArgs = Array.isArray(sample.args) ? sample.args : []
  const input = parameterCount === 1 ? storedArgs[0] : storedArgs
  return {
    argsText: JSON.stringify(input ?? (parameterCount === 1 ? null : [])),
    expectedText: Object.prototype.hasOwnProperty.call(sample, 'expected') ? JSON.stringify(sample.expected) : '',
  }
}

export function buildDebugTestCase(argsText, expectedText = '', argumentCount = 0) {
  const rawArgs = String(argsText || '').trim()
  if (!rawArgs) throw new Error('请填写参数 JSON')
  let parsedArgs
  try {
    parsedArgs = JSON.parse(rawArgs)
  } catch (_) {
    throw new Error('参数 JSON 格式不正确')
  }
  if (argumentCount !== 1 && !Array.isArray(parsedArgs)) throw new Error('多参数函数需要使用 JSON 数组按参数顺序填写')

  const test = { name: '自定义调试', args: argumentCount === 1 ? [parsedArgs] : parsedArgs, debug: true }
  const rawExpected = String(expectedText || '').trim()
  if (!rawExpected) return test
  try {
    test.expected = JSON.parse(rawExpected)
  } catch (_) {
    throw new Error('期望结果 JSON 格式不正确')
  }
  return test
}

export function codingResultSummary(result) {
  if (!result) return '尚未运行'
  if (result.message) return result.message
  const rows = result.rows || []
  return rows.length ? `${rows.filter((row) => row.passed).length} / ${rows.length} 通过` : '尚未运行'
}

export function selectedAnswerKeys(answer) {
  return String(answer || '')
    .split(/[,，、\s]+/)
    .filter(Boolean)
}
