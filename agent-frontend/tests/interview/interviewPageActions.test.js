import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { useInterviewBankPage } from '../../src/composables/useInterviewBankPage'
import * as api from '../../src/api/interview'
import { copyText } from '../../src/utils/clipboard'

vi.mock('../../src/api/interview', () => ({
  listExams: vi.fn(),
  getExam: vi.fn(),
  deleteExam: vi.fn(),
  submitExam: vi.fn(),
  runCodeSample: vi.fn(),
  getQuestionMeta: vi.fn(),
  listQuestions: vi.fn(),
  batchQuestions: vi.fn(),
  createRandomExam: vi.fn(),
  deleteQuestion: vi.fn(),
}))
vi.mock('../../src/utils/clipboard', () => ({ copyText: vi.fn() }))
const question = { questionId: 'q', title: 'Question', questionType: '问答题' }
const coding = {
  questionId: 'code',
  bankType: 'leetcode',
  codingMeta: {
    language: 'python',
    functionName: 'solve',
    template: 'def solve(x): pass',
    parameterCount: 1,
    tests: [{ args: [1], expected: 2, sample: true }],
  },
}
const exam = () => ({
  examId: 'exam',
  title: 'Practice',
  status: 'running',
  remainingSeconds: 600,
  questions: [question, coding],
})
async function page(mode = 'exam') {
  const emit = vi.fn()
  const wrapper = mount({ setup: () => useInterviewBankPage({ mode }, emit), template: '<div />' })
  await flushPromises()
  return { vm: wrapper.vm, emit }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  api.getQuestionMeta.mockResolvedValue({})
  api.listQuestions.mockResolvedValue({ items: [question] })
  api.listExams.mockResolvedValue([exam()])
  api.getExam.mockImplementation(async () => exam())
  api.submitExam.mockResolvedValue({ ...exam(), status: 'submitted', answeredCount: 2 })
  api.runCodeSample.mockResolvedValue({ passed: true, rows: [{ passed: true }] })
  copyText.mockResolvedValue(true)
})
afterEach(() => vi.useRealTimers())

it('validates batch tags and refreshes metadata after question mutations', async () => {
  const { vm } = await page('bank')
  vm.batchTagDraft = 'a,b'
  vm.addBatchTag()
  expect(vm.batchTagError).toContain('一个标签')
  vm.batchTagDraft = 'Python'
  const preventDefault = vi.fn()
  vm.handleBatchTagKeydown({ key: 'Enter', preventDefault })
  expect(preventDefault).toHaveBeenCalledOnce()
  expect(vm.batchTags).toEqual(['Python'])
  vm.removeBatchTag('Python')
  expect(vm.batchTags).toEqual([])
  vm.selectedIds = ['q']
  await vm.applyBatchChanges()
  expect(api.batchQuestions).toHaveBeenCalledWith(expect.objectContaining({ questionIds: ['q'] }))
  await vm.handleQuestionSaved({ ...question, title: 'Updated' })
  vm.removeQuestion(question.questionId)
  await vm.confirmDelete()
  expect(api.deleteQuestion).toHaveBeenCalledWith('q')
  api.getQuestionMeta.mockRejectedValueOnce(new Error('metadata offline'))
  await vm.handleQuestionSaved(question)
  expect(vm.error).toBe('metadata offline')
})

it('resets filters and reports metadata loading errors without stale selections', async () => {
  const { vm } = await page('bank')
  vm.filters.keyword = 'old'
  vm.filters.category = 'old'
  vm.selectedIds = ['q']
  await vm.resetFilters()
  expect(vm.activeFilterCount).toBe(0)
  expect(vm.selectedIds).toEqual([])
  await vm.switchBankTab('qa')
  expect(vm.filters.bankType).toBe('qa')
  api.getQuestionMeta.mockRejectedValueOnce(new Error('search offline'))
  await vm.searchQuestions()
  expect(vm.error).toBe('search offline')
  api.getQuestionMeta.mockRejectedValueOnce(new Error('load offline'))
  await vm.loadAll()
  expect(vm.error).toBe('load offline')
  vm.editModalRef = { openCreate: vi.fn(), openEdit: vi.fn() }
  vm.openCreateModal()
  vm.openEditModal(question)
  expect(vm.editModalRef.openCreate).toHaveBeenCalledWith('qa')
  expect(vm.editModalRef.openEdit).toHaveBeenCalledWith(question)
  vm.practiceModalRef = { open: vi.fn() }
  vm.openPracticeModal()
  expect(vm.practiceModalRef.open).toHaveBeenCalledOnce()
})

it('keeps pending answers behind switch, compose and leave confirmations', async () => {
  const { vm, emit } = await page()
  await vm.openExam('exam')
  vm.answers.q = 'answer'
  vm.requestComposePractice('rule')
  expect(vm.practiceDialogTitle).toContain('新的练习')
  expect(vm.practiceDialogDescription).toContain('创建新练习')
  expect(vm.practiceDialogConfirmText).toBe('继续创建')
  vm.confirmPracticeDialog()
  expect(emit).toHaveBeenCalledWith('compose-practice', 'rule')
  vm.requestBackToBank()
  expect(vm.practiceDialogEyebrow).toBe('离开练习')
  vm.confirmPracticeDialog()
  expect(emit).toHaveBeenCalledWith('back-to-bank')
  vm.requestOpenExam('next')
  expect(vm.practiceDialogTitle).toContain('切换')
  vm.confirmPracticeDialog()
  await flushPromises()
  expect(api.getExam).toHaveBeenLastCalledWith('next')
  vm.returnToPracticeHome()
  vm.requestOpenExam('exam')
  expect(vm.practiceHomeVisible).toBe(false)
})

it('deletes an active exam only after success and leaves failed deletion visible', async () => {
  const { vm, emit } = await page()
  await vm.openExam('exam')
  vm.openExamDeleteDialog(exam())
  api.deleteExam.mockRejectedValueOnce(new Error('delete offline'))
  await vm.confirmExamDelete()
  expect(vm.examDeleteDialog.error).toBe('delete offline')
  expect(vm.currentExam.examId).toBe('exam')
  await vm.confirmExamDelete()
  expect(vm.currentExam).toBeNull()
  expect(vm.practiceHomeVisible).toBe(true)
  expect(vm.exams).toEqual([])
  expect(emit).toHaveBeenCalledWith('exam-deleted', 'exam')
})

it('filters records and reports list and detail failures', async () => {
  const { vm } = await page()
  vm.exams = [exam(), { ...exam(), examId: 'done', title: 'Finished', status: 'submitted' }]
  vm.recordStatus = 'submitted'
  expect(vm.filteredExams.map((item) => item.examId)).toEqual(['done'])
  vm.recordStatus = 'running'
  expect(vm.filteredExams.map((item) => item.examId)).toEqual(['exam'])
  vm.recordKeyword = 'absent'
  expect(vm.filteredExams).toEqual([])
  vm.resetRecordFilters()
  expect(vm.filteredExams).toHaveLength(2)
  api.listExams.mockRejectedValueOnce(new Error('records offline'))
  await vm.loadExams()
  expect(vm.recordsError).toBe('records offline')
  api.getExam.mockRejectedValueOnce(new Error('detail offline'))
  await vm.openExam('missing')
  expect(vm.error).toBe('detail offline')
  expect(vm.examDetailLoading).toBe(false)
  expect(vm.practiceHomeVisible).toBe(true)
})

it('preserves edited code across language changes and navigates choice answers', async () => {
  const { vm } = await page()
  await vm.handlePracticeCreated(exam(), 600)
  expect(vm.isQuestionAnswered(coding)).toBe(false)
  vm.setCodingLanguage('code', 'javascript')
  expect(vm.answers.code).toContain('function solve')
  vm.answers.code = 'function solve(x) { return x + 1; }'
  vm.setCodingLanguage('code', 'python')
  expect(vm.answers.code).toContain('return x + 1')
  expect(vm.isQuestionAnswered(coding)).toBe(true)
  const multi = { ...question, questionType: '多选' }
  vm.updateOptionAnswer(multi, 'B', true)
  vm.updateOptionAnswer(multi, 'A', true)
  expect(vm.selectedAnswerKeys(multi)).toEqual(['A', 'B'])
  vm.updateOptionAnswer(multi, 'A', false)
  expect(vm.isOptionSelected(multi, 'A')).toBe(false)
  vm.updateOptionAnswer(question, 'C', true)
  expect(vm.answers.q).toBe('C')
  vm.goAdjacentQuestion(1)
  expect(vm.activeQuestionId).toBe('code')
  vm.goAdjacentQuestion(-1)
  expect(vm.currentQuestionIndex).toBe(1)
})

it('runs samples and debug input with useful validation and transport failures', async () => {
  const { vm } = await page()
  await vm.openExam('exam')
  expect((await vm.runCodingSample(coding)).passed).toBe(true)
  expect(api.runCodeSample).toHaveBeenCalledWith(
    expect.objectContaining({ functionName: 'solve', tests: coding.codingMeta.tests }),
  )
  vm.toggleCodingDebug(coding)
  vm.codingDebugForms.code.argsText = 'invalid'
  expect((await vm.runCodingDebug(coding)).passed).toBe(false)
  vm.codingDebugForms.code.argsText = '[1]'
  vm.codingDebugForms.code.expectedText = '2'
  expect((await vm.runCodingDebug(coding)).passed).toBe(true)
  api.runCodeSample.mockRejectedValueOnce(new Error('sandbox offline'))
  expect((await vm.runCodingSample(coding)).message).toBe('sandbox offline')
  expect(vm.codingRunning.code).toBe(false)
  expect((await vm.runCodingSample({ questionId: 'empty', bankType: 'leetcode' })).message).toContain('未维护')
  expect((await vm.runCodingTests(coding, [])).message).toContain('未维护')
})

it('submits only after confirmation and includes code execution outcome', async () => {
  const { vm } = await page()
  await vm.openExam('exam')
  await vm.submitCurrentExam()
  expect(vm.practiceDialog.visible).toBe(true)
  expect(api.submitExam).not.toHaveBeenCalled()
  vm.confirmPracticeDialog()
  await flushPromises()
  expect(api.submitExam).toHaveBeenCalledWith('exam', expect.any(Object), { code: true })
  expect(vm.currentExam.status).toBe('submitted')
  expect(vm.answeredCount).toBe(2)
  expect(vm.examProgressPercent).toBe(100)
})

it('clears stale code results on failed submission and resets copy feedback', async () => {
  const { vm } = await page()
  await vm.openExam('exam')
  api.submitExam.mockRejectedValueOnce(new Error('submit offline'))
  await vm.submitCurrentExam(true)
  expect(vm.error).toBe('submit offline')
  expect(vm.codingResults).toEqual({})
  expect(vm.examLoading).toBe(false)
  await vm.copyPracticeCode(coding)
  expect(vm.codeCopyState.code).toBe('已复制')
  await vi.advanceTimersByTimeAsync(1800)
  expect(vm.codeCopyState.code).toBeUndefined()
})

it('Escape closes the topmost confirmation and respects busy deletion', async () => {
  const { vm } = await page()
  vm.practiceDialog.visible = true
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
  expect(vm.practiceDialog.visible).toBe(false)
  vm.openExamDeleteDialog(exam())
  vm.examDeleteDialog.loading = true
  vm.closeExamDeleteDialog()
  expect(vm.examDeleteDialog.visible).toBe(true)
  vm.examDeleteDialog.loading = false
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
  expect(vm.examDeleteDialog.visible).toBe(false)
  vm.deleteDialog.visible = true
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
  expect(vm.deleteDialog.visible).toBe(false)
})
