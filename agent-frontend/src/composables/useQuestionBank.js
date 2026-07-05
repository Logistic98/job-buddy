// Question-bank list state for the bank panel: paging, filtering, row selection, batch update,
// deletion dialog flow and manual-practice creation. Pure list orchestration around the
// interview API; modal editing lives in QuestionEditModal and metadata in useQuestionMeta.

import { computed, reactive, ref } from 'vue'
import { batchQuestions, createRandomExam, deleteQuestion, listQuestions } from '../api/interview'
import { splitCleanTags, tagLabels } from '../utils/interviewBank'
import { assertManualPracticeMatches } from '../utils/interviewForm'

export function useQuestionBank(initialBankType = 'leetcode') {
  const loading = ref(false)
  const saving = ref(false)
  const examLoading = ref(false)
  const error = ref('')
  const questions = ref([])
  const selectedIds = ref([])
  const filters = reactive({ keyword: '', bankType: initialBankType, category: '', difficulty: '' })
  const pagination = reactive({ page: 1, size: 20, total: 0, pages: 1 })
  const batchForm = reactive({ category: '', difficulty: '', tagsText: '' })
  const showBatchEditor = ref(false)
  const deleteDialog = reactive({ visible: false, mode: 'single', questionId: '', count: 0 })

  const filteredQuestions = computed(() => questions.value)
  const selectedSet = computed(() => new Set(selectedIds.value))
  const allCurrentPageSelected = computed(() => questions.value.length > 0 && questions.value.every(item => selectedSet.value.has(item.questionId)))
  const visiblePages = computed(() => {
    const total = Math.max(1, pagination.pages || 1)
    const current = Math.min(Math.max(1, pagination.page), total)
    const start = Math.max(1, Math.min(current - 2, total - 4))
    const end = Math.min(total, start + 4)
    const pages = []
    for (let page = start; page <= end; page++) pages.push(page)
    return pages
  })

  async function loadQuestions() {
    loading.value = true
    error.value = ''
    try {
      const data = await listQuestions({ ...filters, page: pagination.page, size: pagination.size, _ts: Date.now() })
      questions.value = (data.items || []).map(normalizeQuestionRow)
      pagination.total = Number(data.total || questions.value.length || 0)
      pagination.page = Number(data.page || pagination.page || 1)
      pagination.size = Number(data.size || pagination.size || 20)
      pagination.pages = Math.max(1, Number(data.pages || Math.ceil(pagination.total / pagination.size) || 1))
    } catch (err) { error.value = err.message || '题库加载失败' } finally { loading.value = false }
  }
  function goPage(page) {
    pagination.page = Math.max(1, Math.min(page, pagination.pages || 1))
    questions.value = []
    return loadQuestions()
  }
  function changePageSize() { pagination.page = 1; clearSelection(); questions.value = []; return loadQuestions() }
  function normalizeQuestionRow(item) {
    if (!item || typeof item !== 'object') return item
    const bankType = item.bankType || (item.questionType === '编程题' ? 'leetcode' : 'baguwen')
    return { ...item, bankType, tags: tagLabels(item).map(label => ({ label })), codingMeta: item.codingMeta || {} }
  }
  function rowNumber(index) { return (pagination.page - 1) * pagination.size + index + 1 }
  function toggleSelection(questionId, checked) {
    const set = new Set(selectedIds.value)
    checked ? set.add(questionId) : set.delete(questionId)
    selectedIds.value = Array.from(set)
  }
  function toggleCurrentPage(checked) {
    const set = new Set(selectedIds.value)
    for (const item of questions.value) checked ? set.add(item.questionId) : set.delete(item.questionId)
    selectedIds.value = Array.from(set)
  }
  function clearSelection() {
    selectedIds.value = []
    showBatchEditor.value = false
  }
  async function applyBatchUpdate() {
    if (!selectedIds.value.length) return
    saving.value = true
    error.value = ''
    try {
      await batchQuestions({
        action: 'update',
        questionIds: selectedIds.value,
        category: batchForm.category,
        difficulty: batchForm.difficulty,
        tags: splitCleanTags(batchForm.tagsText),
      })
      Object.assign(batchForm, { category: '', difficulty: '', tagsText: '' })
      clearSelection()
      await loadQuestions()
    } catch (err) { error.value = err.message || '批量修改失败' } finally { saving.value = false }
  }
  function applyBatchDelete() {
    if (!selectedIds.value.length) return
    Object.assign(deleteDialog, { visible: true, mode: 'batch', questionId: '', count: selectedIds.value.length })
  }
  async function createManualPractice(questionIds, title, showAnswer = true, onCreated = () => {}) {
    examLoading.value = true
    error.value = ''
    try {
      const exam = await createRandomExam({
        title,
        durationMinutes: questionIds.length > 1 ? 45 : 30,
        showAnswer,
        questionIds,
      })
      assertManualPracticeMatches(exam, questionIds)
      clearSelection()
      onCreated(exam)
    } catch (err) {
      error.value = err.message || '创建练习失败'
    } finally {
      examLoading.value = false
    }
  }
  function upsertQuestionRow(saved) {
    if (!saved?.questionId) return
    const idx = questions.value.findIndex(item => item.questionId === saved.questionId)
    if (idx >= 0) questions.value.splice(idx, 1, saved)
    else questions.value.unshift(saved)
  }
  function removeQuestion(questionId) {
    Object.assign(deleteDialog, { visible: true, mode: 'single', questionId, count: 1 })
  }
  function closeDeleteDialog() {
    if (saving.value) return
    Object.assign(deleteDialog, { visible: false, mode: 'single', questionId: '', count: 0 })
  }
  async function confirmDelete() {
    saving.value = true
    error.value = ''
    try {
      if (deleteDialog.mode === 'batch') {
        await batchQuestions({ action: 'delete', questionIds: selectedIds.value })
        clearSelection()
      } else if (deleteDialog.questionId) {
        await deleteQuestion(deleteDialog.questionId)
      }
      Object.assign(deleteDialog, { visible: false, mode: 'single', questionId: '', count: 0 })
      await loadQuestions()
    } catch (err) {
      error.value = err.message || '删除失败'
    } finally {
      saving.value = false
    }
  }

  return {
    loading, saving, examLoading, error, questions, selectedIds, filters, pagination, batchForm, showBatchEditor, deleteDialog,
    filteredQuestions, selectedSet, allCurrentPageSelected, visiblePages,
    loadQuestions, goPage, changePageSize, normalizeQuestionRow, rowNumber,
    toggleSelection, toggleCurrentPage, clearSelection,
    applyBatchUpdate, applyBatchDelete, createManualPractice, upsertQuestionRow,
    removeQuestion, closeDeleteDialog, confirmDelete,
  }
}
