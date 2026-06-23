// Pure, side-effect-free helpers for the interview maintain/practice forms. Extracted from
// InterviewBank.vue so validation, payload shaping and title formatting can be unit-tested in
// isolation, while the component keeps only its reactive state and API orchestration. None of
// these functions read or mutate component state; everything flows through explicit arguments.

import {
  buildDefaultTemplate,
  defaultSignature,
  extractFunctionName,
  isChoiceType,
  normalizeCodingLanguage,
  requireText,
  splitCleanTags,
} from './interviewBank'

export function defaultPracticeTitle(now = new Date()) {
  const pad = value => String(value).padStart(2, '0')
  return `随机组卷 ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
}

export function displayExamTitle(exam) {
  return String(exam?.title || '未命名练习')
    .replace(/模拟练习/g, '随机组卷')
    .replace(/LeetCode/gi, '算法')
}

// 校验手动录入的练习题表单；非法时抛出带用户提示的 Error，与原组件行为一致。
export function validateQuestionForm(form) {
  requireText(form.title, '请填写题目标题')
  const choice = isChoiceType(form.questionType)
  requireText(form.content, choice ? '请填写选择题题干' : '请填写题目内容')
  if (choice) {
    const validOptions = form.options.map(item => String(item.text || '').trim()).filter(Boolean)
    if (validOptions.length < 2) throw new Error('选择题至少需要 2 个有效选项')
    requireText(form.answer, '请填写正确答案')
  }
  if (form.bankType === 'leetcode') {
    requireText(form.codingTemplate, '请填写初始代码模板')
    if (!extractFunctionName(form.codingTemplate, form.codingLanguage)) throw new Error('代码模板中需要包含可识别的函数或方法声明')
  }
}

export function validateAiForm(aiForm) {
  if (!String(aiForm.topic || '').trim() && !String(aiForm.documentText || '').trim()) throw new Error('请填写方向主题或上传参考资料')
  const count = Number(aiForm.count || 0)
  if (!Number.isFinite(count) || count < 1 || count > 20) throw new Error('生成数量需在 1-20 之间')
}

export function examRuleTotal(rules = []) {
  return rules.reduce((sum, rule) => sum + Math.max(0, Number(rule.count || 0)), 0)
}

export function validatePracticeConfig(examConfig) {
  requireText(examConfig.title, '请填写练习名称')
  const duration = Number(examConfig.durationMinutes || 0)
  if (!Number.isFinite(duration) || duration < 1 || duration > 240) throw new Error('限时时长需在 1-240 分钟之间')
  if (!examRuleTotal(examConfig.rules)) throw new Error('请至少配置 1 道题')
  for (const rule of examConfig.rules) {
    const count = Number(rule.count || 0)
    if (!Number.isFinite(count) || count < 0 || count > 50) throw new Error('单条组卷规则题数需在 0-50 之间')
  }
}

// 校验手动组卷返回的练习题目集合与所选题目是否一致，不一致抛错以阻断错配练习。
export function assertManualPracticeMatches(exam, questionIds) {
  const expected = Array.from(new Set(questionIds.map(id => String(id || '').trim()).filter(Boolean))).sort()
  const actual = (exam?.questions || []).map(item => String(item.questionId || '').trim()).filter(Boolean).sort()
  const same = expected.length === actual.length && expected.every((id, index) => id === actual[index])
  if (!same) throw new Error('练习内容与所选题目不一致，请刷新题库后重试')
}

export function buildCodingMetaFromForm(form) {
  let tests = []
  if (form.codingTestsText.trim()) {
    try {
      const parsed = JSON.parse(form.codingTestsText)
      tests = Array.isArray(parsed) ? parsed : []
    } catch (err) {
      throw new Error('测试用例 JSON 格式不正确')
    }
  }
  const language = normalizeCodingLanguage(form.codingLanguage)
  const functionName = extractFunctionName(form.codingTemplate, language) || form.codingFunctionName || 'solution'
  return { language, functionName, signature: defaultSignature(functionName, language), template: form.codingTemplate || buildDefaultTemplate(functionName, language), tests }
}

export function buildQuestionPayload(form) {
  const options = form.options.map(item => ({ key: item.key, text: String(item.text || '').trim() })).filter(item => item.text)
  const content = isChoiceType(form.questionType) && options.length
    ? `${form.content.trim()}\n\n${options.map(item => `${item.key}. ${item.text}`).join('\n')}`
    : form.content
  const payload = { ...form, content, answer: form.answer.trim(), tags: splitCleanTags(form.tagsText), bankType: form.bankType, questionType: form.bankType === 'leetcode' ? '编程题' : form.questionType }
  if (payload.bankType === 'leetcode') payload.codingMeta = buildCodingMetaFromForm(form)
  delete payload.tagsText
  delete payload.options
  delete payload.codingLanguage
  delete payload.codingFunctionName
  delete payload.codingSignature
  delete payload.codingTemplate
  delete payload.codingTestsText
  return payload
}

export function codingResultSummary(result) {
  if (!result) return '尚未运行'
  if (result.message) return result.message
  const rows = result.rows || []
  return rows.length ? `${rows.filter(row => row.passed).length} / ${rows.length} 通过` : '尚未运行'
}

export function selectedAnswerKeys(answer) {
  return String(answer || '').split(/[,，、\s]+/).filter(Boolean)
}
