import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../src/api/interview', () => ({
  listQuestions: vi.fn(async () => ({ items: [], total: 0, page: 1, size: 20, pages: 1 })),
  batchQuestions: vi.fn(async () => ({})),
  deleteQuestion: vi.fn(async () => ({})),
  createRandomExam: vi.fn(async () => ({ questions: [] })),
}))

import { batchQuestions, createRandomExam, deleteQuestion, listQuestions } from '../src/api/interview'
import { useQuestionBank } from '../src/composables/useQuestionBank'

describe('useQuestionBank', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loadQuestions normalizes rows and pagination', async () => {
    listQuestions.mockResolvedValue({
      items: [
        { questionId: 'q1', questionType: '编程题', tags: 'leetcode,数组' },
        { questionId: 'q2', questionType: '单选', tags: [] },
      ],
      total: 42,
      page: 2,
      size: 20,
      pages: 3,
    })
    const bank = useQuestionBank()
    await bank.loadQuestions()
    expect(bank.questions.value).toHaveLength(2)
    expect(bank.questions.value[0].bankType).toBe('leetcode')
    expect(bank.questions.value[0].tags.map(tag => tag.label)).toEqual(['算法', '数组'])
    expect(bank.questions.value[1].bankType).toBe('baguwen')
    expect(bank.pagination.total).toBe(42)
    expect(bank.pagination.pages).toBe(3)
    expect(bank.error.value).toBe('')
    expect(bank.loading.value).toBe(false)
  })

  it('loadQuestions surfaces API errors and clears loading', async () => {
    listQuestions.mockRejectedValue(new Error('后端不可用'))
    const bank = useQuestionBank()
    await bank.loadQuestions()
    expect(bank.error.value).toBe('后端不可用')
    expect(bank.loading.value).toBe(false)
  })

  it('goPage clamps page into valid range before reloading', async () => {
    const bank = useQuestionBank()
    bank.pagination.pages = 3
    await bank.goPage(99)
    expect(bank.pagination.page).toBe(3)
    await bank.goPage(-1)
    expect(bank.pagination.page).toBe(1)
    expect(listQuestions).toHaveBeenCalledTimes(2)
  })

  it('visiblePages returns a sliding window around the current page', () => {
    const bank = useQuestionBank()
    bank.pagination.pages = 10
    bank.pagination.page = 6
    expect(bank.visiblePages.value).toEqual([4, 5, 6, 7, 8])
    bank.pagination.page = 1
    expect(bank.visiblePages.value).toEqual([1, 2, 3, 4, 5])
    bank.pagination.page = 10
    expect(bank.visiblePages.value).toEqual([6, 7, 8, 9, 10])
  })

  it('selection toggles work across the current page', () => {
    const bank = useQuestionBank()
    bank.questions.value = [{ questionId: 'q1' }, { questionId: 'q2' }]
    bank.toggleSelection('q1', true)
    expect(bank.selectedIds.value).toEqual(['q1'])
    expect(bank.allCurrentPageSelected.value).toBe(false)
    bank.toggleCurrentPage(true)
    expect(bank.allCurrentPageSelected.value).toBe(true)
    bank.toggleCurrentPage(false)
    expect(bank.selectedIds.value).toEqual([])
  })

  it('applyBatchUpdate sends cleaned tags then clears selection and reloads', async () => {
    const bank = useQuestionBank()
    bank.selectedIds.value = ['q1', 'q2']
    Object.assign(bank.batchForm, { category: '数据库', difficulty: '中等', tagsText: 'MySQL, 索引' })
    await bank.applyBatchUpdate()
    expect(batchQuestions).toHaveBeenCalledWith({
      action: 'update',
      questionIds: ['q1', 'q2'],
      category: '数据库',
      difficulty: '中等',
      tags: ['MySQL', '索引'],
    })
    expect(bank.selectedIds.value).toEqual([])
    expect(bank.batchForm.tagsText).toBe('')
    expect(listQuestions).toHaveBeenCalledTimes(1)
  })

  it('confirmDelete handles single and batch modes', async () => {
    const bank = useQuestionBank()
    bank.removeQuestion('q9')
    expect(bank.deleteDialog.visible).toBe(true)
    expect(bank.deleteDialog.mode).toBe('single')
    await bank.confirmDelete()
    expect(deleteQuestion).toHaveBeenCalledWith('q9')
    expect(bank.deleteDialog.visible).toBe(false)

    bank.selectedIds.value = ['q1', 'q2']
    bank.applyBatchDelete()
    expect(bank.deleteDialog.mode).toBe('batch')
    expect(bank.deleteDialog.count).toBe(2)
    await bank.confirmDelete()
    expect(batchQuestions).toHaveBeenCalledWith({ action: 'delete', questionIds: ['q1', 'q2'] })
    expect(bank.selectedIds.value).toEqual([])
  })

  it('createManualPractice validates returned exam against selected ids', async () => {
    createRandomExam.mockResolvedValue({ questions: [{ questionId: 'q1' }, { questionId: 'q2' }] })
    const bank = useQuestionBank()
    const onCreated = vi.fn()
    await bank.createManualPractice(['q1', 'q2'], '手动练习', true, onCreated)
    expect(onCreated).toHaveBeenCalledTimes(1)
    expect(bank.error.value).toBe('')
    expect(createRandomExam).toHaveBeenCalledWith(expect.objectContaining({ durationMinutes: 45, questionIds: ['q1', 'q2'] }))
  })

  it('createManualPractice reports mismatched exam content as an error', async () => {
    createRandomExam.mockResolvedValue({ questions: [{ questionId: 'other' }] })
    const bank = useQuestionBank()
    const onCreated = vi.fn()
    await bank.createManualPractice(['q1'], '手动练习', true, onCreated)
    expect(onCreated).not.toHaveBeenCalled()
    expect(bank.error.value).toContain('练习内容与所选题目不一致')
    expect(bank.examLoading.value).toBe(false)
  })

  it('upsertQuestionRow replaces existing rows and prepends new ones', () => {
    const bank = useQuestionBank()
    bank.questions.value = [{ questionId: 'q1', title: 'old' }]
    bank.upsertQuestionRow({ questionId: 'q1', title: 'new' })
    expect(bank.questions.value[0].title).toBe('new')
    bank.upsertQuestionRow({ questionId: 'q2', title: 'added' })
    expect(bank.questions.value[0].questionId).toBe('q2')
    bank.upsertQuestionRow(null)
    expect(bank.questions.value).toHaveLength(2)
  })
})
