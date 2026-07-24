import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getExam, listExams, runCodeSample, submitExam } from '../api/interview'
import InterviewBankHeader from '../components/interview/InterviewBankHeader.vue'
import PracticeConfigModal from '../components/interview/PracticeConfigModal.vue'
import PracticeMarkdown from '../components/interview/PracticeMarkdown.vue'
import QuestionEditModal from '../components/interview/QuestionEditModal.vue'
import { useExamTimer } from '../composables/useExamTimer'
import { useQuestionBank } from '../composables/useQuestionBank'
import { useQuestionMeta } from '../composables/useQuestionMeta'
import {
  answerContent,
  buildDefaultTemplate,
  codingLanguageOptions,
  codingMeta,
  difficultyClass,
  displayTitle,
  extractFunctionName,
  isCodingQuestion,
  isMultiChoice,
  normalizeCodingLanguage,
  optionItems,
  questionStem,
  tagLabels,
} from '../utils/interviewBank'
import { copyText } from '../utils/clipboard'
import {
  buildDebugFormDefaults,
  buildDebugTestCase,
  codingResultSummary as codingResultSummaryUtil,
  displayExamTitle,
  formatExamStartedAt,
  isCurrentExam,
  selectedAnswerKeys as selectedAnswerKeysUtil,
  shouldShowExamOpening,
} from '../utils/interviewForm'

export function useInterviewBankPage(props, emit) {
  const activeMode = computed(() => (props.mode === 'exam' ? 'exam' : 'bank'))

  const {
    loading,
    saving,
    examLoading,
    error,
    questions,
    selectedIds,
    filters,
    pagination,
    batchForm,
    showBatchEditor,
    deleteDialog,
    filteredQuestions,
    selectedSet,
    allCurrentPageSelected,
    visiblePages,
    loadQuestions,
    goPage,
    changePageSize,
    normalizeQuestionRow,
    rowNumber,
    toggleSelection,
    toggleCurrentPage,
    clearSelection,
    applyBatchUpdate,
    applyBatchDelete,
    createManualPractice,
    upsertQuestionRow,
    removeQuestion,
    closeDeleteDialog,
    confirmDelete: confirmQuestionDelete,
  } = useQuestionBank(props.mode === 'bank' ? 'leetcode' : '')

  const { loadQuestionMeta, bankTypeOptions, categories, difficulties, questionTypes, bankTypeLabel } =
    useQuestionMeta()

  const { timerRemaining, remainingTimeText, startExamTimer, stopExamTimer } = useExamTimer(submitCurrentExam)

  const practiceModalRef = ref(null)
  const editModalRef = ref(null)
  const batchTagDraft = ref('')
  const batchTagError = ref('')
  const recordsLoading = ref(false)
  const recordsError = ref('')
  const recordsDrawerOpen = ref(false)
  const examDetailLoading = ref(false)
  const exams = ref([])
  const currentExam = ref(null)
  const activeQuestionId = ref('')
  const answers = reactive({})
  const codingResults = reactive({})
  const codingRunning = reactive({})
  const codingLanguageByQuestion = reactive({})
  const codingDebugOpen = reactive({})
  const codingDebugForms = reactive({})
  const codeCopyState = reactive({})
  const practiceDialog = reactive({ visible: false, mode: 'submit', pendingExamId: '' })

  const timerExpired = computed(() =>
    Boolean(currentExam.value && currentExam.value.status !== 'submitted' && timerRemaining.value <= 0),
  )
  const activeFilterCount = computed(
    () => [filters.keyword, filters.category, filters.difficulty].filter(Boolean).length,
  )
  const batchTags = computed(() => tagLabels({ tags: batchForm.tags }))
  function addBatchTag() {
    const normalized = batchTagDraft.value.trim()
    batchTagError.value = ''
    if (!normalized) return
    if (/[,，、;；\n\r\t]/.test(normalized)) {
      batchTagError.value = '请一次添加一个标签。'
      return
    }
    if (!batchTags.value.some((tag) => tag.toLowerCase() === normalized.toLowerCase())) {
      batchForm.tags = [...batchTags.value, normalized]
    }
    batchTagDraft.value = ''
  }
  function removeBatchTag(tag) {
    batchForm.tags = batchTags.value.filter((item) => item !== tag)
    batchTagError.value = ''
  }
  function handleBatchTagKeydown(event) {
    if (event.key === 'Enter') {
      event.preventDefault()
      addBatchTag()
    }
  }
  async function applyBatchChanges() {
    await applyBatchUpdate()
    await refreshQuestionMetaAfterMutation()
    if (!batchForm.tags.length && !batchForm.tagsText) {
      batchTagDraft.value = ''
      batchTagError.value = ''
    }
  }

  const pageEyebrow = computed(() => (activeMode.value === 'exam' ? 'Practice Desk' : 'Question Bank'))
  const pageTitle = computed(() => (activeMode.value === 'exam' ? '练习台' : '题库'))
  const pageDescription = computed(() =>
    activeMode.value === 'exam'
      ? '查看练习记录、继续作答或通过随机组卷开始新练习。'
      : '按题库维护题目，支持单题练习、勾选后练习和批量设置。',
  )
  const showAnswerMode = computed(() => Boolean(currentExam.value?.strategy?.showAnswer))
  const isOpeningTargetExam = computed(() =>
    shouldShowExamOpening(props.initialExamId, currentExam.value, examDetailLoading.value, error.value),
  )
  const examTotalCount = computed(() => currentExam.value?.questions?.length || 0)
  const answeredCount = computed(() => (currentExam.value?.questions || []).filter(isQuestionAnswered).length)
  const unansweredQuestions = computed(() =>
    (currentExam.value?.questions || []).filter((item) => !isQuestionAnswered(item)),
  )
  const examProgressPercent = computed(() =>
    examTotalCount.value ? Math.round((answeredCount.value / examTotalCount.value) * 100) : 0,
  )
  const currentQuestionIndexMap = computed(() =>
    Object.fromEntries((currentExam.value?.questions || []).map((item, index) => [item.questionId, index + 1])),
  )
  const activeQuestion = computed(() => {
    const list = currentExam.value?.questions || []
    return list.find((item) => item.questionId === activeQuestionId.value) || list[0] || null
  })
  const currentQuestionIndex = computed(() =>
    activeQuestion.value ? currentQuestionIndexMap.value[activeQuestion.value.questionId] || 1 : 0,
  )
  const practiceDialogEyebrow = computed(() =>
    practiceDialog.mode === 'submit' ? '提交练习' : practiceDialog.mode === 'switch' ? '切换练习' : '离开练习',
  )
  const practiceDialogTitle = computed(() =>
    practiceDialog.mode === 'submit'
      ? `还有 ${unansweredQuestions.value.length} 题未作答`
      : practiceDialog.mode === 'switch'
        ? '切换到其他练习？'
        : '暂时离开当前练习？',
  )
  const practiceDialogDescription = computed(() =>
    practiceDialog.mode === 'submit'
      ? '提交后将立即结束本次练习，未作答题目不会得分。'
      : '未提交答案只保留在本次页面会话中，刷新页面或重新登录后无法恢复。',
  )
  const practiceDialogConfirmText = computed(() =>
    practiceDialog.mode === 'submit' ? '仍要提交' : practiceDialog.mode === 'switch' ? '确认切换' : '确认离开',
  )

  onMounted(() => {
    loadAll()
    document.addEventListener('keydown', handleGlobalKeydown)
  })
  onBeforeUnmount(() => {
    stopExamTimer()
    document.removeEventListener('keydown', handleGlobalKeydown)
  })

  watch(
    () => props.initialExamId,
    async (examId) => {
      if (activeMode.value !== 'exam' || !examId) return
      error.value = ''
      await loadExams()
      await openExam(examId)
    },
    { immediate: true },
  )

  async function loadAll() {
    try {
      if (activeMode.value === 'exam') await Promise.all([loadQuestionMeta(filters.bankType), loadExams()])
      else await Promise.all([loadQuestionMeta(filters.bankType), loadQuestions()])
    } catch (err) {
      error.value = err?.message || '题库元数据加载失败，请稍后重试'
    }
  }
  async function searchQuestions() {
    pagination.page = 1
    clearSelection()
    questions.value = []
    try {
      await Promise.all([loadQuestionMeta(filters.bankType), loadQuestions()])
    } catch (err) {
      error.value = err?.message || '题库元数据加载失败，请稍后重试'
    }
  }
  function resetFilters() {
    filters.keyword = ''
    filters.category = ''
    filters.difficulty = ''
    return searchQuestions()
  }
  function switchBankTab(value) {
    if (filters.bankType === value) return
    filters.bankType = value
    filters.category = ''
    return searchQuestions()
  }
  function handleGlobalKeydown(event) {
    if (!['Escape', 'Esc'].includes(event.key)) return
    if (practiceDialog.visible) closePracticeDialog()
    else if (recordsDrawerOpen.value) closeRecordsDrawer()
    else if (deleteDialog.visible) closeDeleteDialog()
  }

  function openCreateModal() {
    editModalRef.value?.openCreate(filters.bankType)
  }
  function openEditModal(item) {
    editModalRef.value?.openEdit(item)
  }
  async function handleQuestionSaved(saved) {
    if (saved) upsertQuestionRow(normalizeQuestionRow(saved))
    await Promise.all([loadQuestions(), refreshQuestionMetaAfterMutation()])
  }
  async function confirmDelete() {
    await confirmQuestionDelete()
    await refreshQuestionMetaAfterMutation()
  }
  async function refreshQuestionMetaAfterMutation() {
    try {
      await loadQuestionMeta(filters.bankType)
    } catch (err) {
      error.value = err?.message || '题库元数据刷新失败，请稍后重试'
    }
  }

  function openPracticeModal() {
    practiceModalRef.value?.open()
  }
  async function handlePracticeCreated(exam, fallbackSeconds) {
    error.value = ''
    closeRecordsDrawer()
    currentExam.value = exam
    resetPracticeAnswers(exam)
    startExamTimer(Number(exam.remainingSeconds || fallbackSeconds), true)
    await loadExams()
  }

  async function startSelectedPractice() {
    if (!selectedIds.value.length || examLoading.value) return
    const selectedQuestions = questions.value.filter((item) => selectedSet.value.has(item.questionId))
    const title = `${currentBankTypeLabel()} 所选题练习（${selectedIds.value.length} 题）`
    await createManualPractice(selectedIds.value, title, selectedQuestions.length === 1, (exam) =>
      emit('practice-created', exam),
    )
  }
  async function startSingleQuestionPractice(item) {
    if (!item?.questionId || examLoading.value) return
    await createManualPractice([item.questionId], `${displayTitle(item, 0)} 单题练习`, true, (exam) =>
      emit('practice-created', exam),
    )
  }
  function currentBankTypeLabel() {
    return bankTypeLabel(filters.bankType) || '题库'
  }

  async function loadExams() {
    recordsLoading.value = true
    recordsError.value = ''
    try {
      exams.value = await listExams()
    } catch (err) {
      recordsError.value = err?.message || '练习记录加载失败，请稍后重试'
    } finally {
      recordsLoading.value = false
    }
  }
  function examShowAnswer(exam) {
    return Boolean(exam?.strategy?.showAnswer)
  }
  async function openRecordsDrawer() {
    recordsDrawerOpen.value = true
    if (!exams.value.length || recordsError.value) await loadExams()
  }
  function closeRecordsDrawer() {
    recordsDrawerOpen.value = false
  }
  function requestOpenExam(examId) {
    if (!examId || examId === currentExam.value?.examId) {
      closeRecordsDrawer()
      return
    }
    if (currentExam.value?.status !== 'submitted' && answeredCount.value > 0) {
      Object.assign(practiceDialog, { visible: true, mode: 'switch', pendingExamId: examId })
      return
    }
    closeRecordsDrawer()
    openExam(examId)
  }

  function currentCodingLanguage(questionId) {
    return normalizeCodingLanguage(codingLanguageByQuestion[questionId] || 'python')
  }
  function setCodingLanguage(questionId, value) {
    const oldLanguage = currentCodingLanguage(questionId)
    const nextLanguage = normalizeCodingLanguage(value)
    const meta = codingMeta((currentExam.value?.questions || []).find((item) => item.questionId === questionId) || {})
    const functionName = meta.functionName || extractFunctionName(answers[questionId], oldLanguage) || 'solution'
    const oldTemplate = buildDefaultTemplate(functionName, oldLanguage).trim()
    const current = String(answers[questionId] || '').trim()
    codingLanguageByQuestion[questionId] = nextLanguage
    if (!current || current === oldTemplate) answers[questionId] = buildDefaultTemplate(functionName, nextLanguage)
    delete codingResults[questionId]
  }
  function isQuestionAnswered(item) {
    const value = String(answers[item.questionId] || '').trim()
    if (!value) return false
    if (isCodingQuestion(item)) {
      const language = currentCodingLanguage(item.questionId)
      const meta = codingMeta(item)
      const functionName = meta.functionName || extractFunctionName(value, language) || 'solution'
      const template = buildDefaultTemplate(functionName, language).trim()
      return value !== template && !/(TODO|pass\s*$)/i.test(value)
    }
    return true
  }
  function setActiveQuestion(questionId) {
    activeQuestionId.value = questionId
  }
  function goAdjacentQuestion(delta) {
    const list = currentExam.value?.questions || []
    const index = list.findIndex((item) => item.questionId === activeQuestion.value?.questionId)
    const next = list[Math.min(Math.max(index + delta, 0), list.length - 1)]
    if (next) setActiveQuestion(next.questionId)
  }
  function selectedAnswerKeys(item) {
    return selectedAnswerKeysUtil(answers[item.questionId])
  }
  function isOptionSelected(item, key) {
    return selectedAnswerKeys(item).includes(key)
  }
  function updateOptionAnswer(item, key, checked) {
    if (isMultiChoice(item)) {
      const set = new Set(selectedAnswerKeys(item))
      checked ? set.add(key) : set.delete(key)
      answers[item.questionId] = Array.from(set).sort().join(',')
    } else {
      answers[item.questionId] = key
    }
  }

  async function openExam(examId) {
    stopExamTimer()
    examDetailLoading.value = true
    currentExam.value = null
    activeQuestionId.value = ''
    error.value = ''
    try {
      currentExam.value = await getExam(examId)
      resetPracticeAnswers(currentExam.value, true)
      if (currentExam.value?.status !== 'submitted')
        startExamTimer(Number(currentExam.value.remainingSeconds || 0), true)
    } catch (err) {
      error.value = err.message || '练习详情加载失败'
    } finally {
      examDetailLoading.value = false
    }
  }
  function resetPracticeAnswers(exam, keepUserAnswer = false) {
    Object.keys(answers).forEach((key) => delete answers[key])
    Object.keys(codingResults).forEach((key) => delete codingResults[key])
    Object.keys(codingRunning).forEach((key) => delete codingRunning[key])
    Object.keys(codingLanguageByQuestion).forEach((key) => delete codingLanguageByQuestion[key])
    Object.keys(codingDebugOpen).forEach((key) => delete codingDebugOpen[key])
    Object.keys(codingDebugForms).forEach((key) => delete codingDebugForms[key])
    Object.keys(codeCopyState).forEach((key) => delete codeCopyState[key])
    const list = exam?.questions || []
    for (const q of list) {
      const meta = codingMeta(q)
      const language = 'python'
      const functionName =
        meta.functionName || extractFunctionName(meta.template, normalizeCodingLanguage(meta.language)) || 'solution'
      codingLanguageByQuestion[q.questionId] = language
      answers[q.questionId] = keepUserAnswer
        ? q.userAnswer || (isCodingQuestion(q) ? buildDefaultTemplate(functionName, language) : '')
        : isCodingQuestion(q)
          ? buildDefaultTemplate(functionName, language)
          : ''
      if (q.correct != null) codingResults[q.questionId] = { passed: Boolean(q.correct), rows: [] }
    }
    activeQuestionId.value = list[0]?.questionId || ''
  }
  async function submitCurrentExam(force = false) {
    if (!currentExam.value || examLoading.value) return
    if (!force && !timerExpired.value && unansweredQuestions.value.length) {
      Object.assign(practiceDialog, { visible: true, mode: 'submit' })
      return
    }
    examLoading.value = true
    error.value = ''
    try {
      await runAllCodingBeforeSubmit()
      currentExam.value = await submitExam(currentExam.value.examId, answers, codingSubmitPayload())
      stopExamTimer()
      Object.keys(codingResults).forEach((key) => delete codingResults[key])
      Object.keys(codingDebugOpen).forEach((key) => delete codingDebugOpen[key])
      Object.keys(codingDebugForms).forEach((key) => delete codingDebugForms[key])
      for (const q of currentExam.value.questions || [])
        answers[q.questionId] = q.userAnswer || answers[q.questionId] || ''
      await loadExams()
    } catch (err) {
      Object.keys(codingResults).forEach((key) => delete codingResults[key])
      Object.keys(codingDebugOpen).forEach((key) => delete codingDebugOpen[key])
      error.value = err.message || '提交失败'
    } finally {
      examLoading.value = false
    }
  }
  function requestBackToBank() {
    if (currentExam.value?.status !== 'submitted' && answeredCount.value > 0) {
      Object.assign(practiceDialog, { visible: true, mode: 'leave' })
      return
    }
    emit('back-to-bank')
  }
  function closePracticeDialog() {
    practiceDialog.visible = false
    practiceDialog.pendingExamId = ''
  }
  function confirmPracticeDialog() {
    const mode = practiceDialog.mode
    const pendingExamId = practiceDialog.pendingExamId
    closePracticeDialog()
    if (mode === 'leave') emit('back-to-bank')
    else if (mode === 'switch') {
      closeRecordsDrawer()
      openExam(pendingExamId)
    } else submitCurrentExam(true)
  }

  function codingDebugForm(item) {
    const questionId = item?.questionId || ''
    if (!codingDebugForms[questionId]) codingDebugForms[questionId] = buildDebugFormDefaults(item)
    return codingDebugForms[questionId]
  }
  function toggleCodingDebug(item) {
    codingDebugForm(item)
    codingDebugOpen[item.questionId] = !codingDebugOpen[item.questionId]
  }
  async function runCodingSample(item) {
    const meta = codingMeta(item)
    const tests = (Array.isArray(meta.tests) ? meta.tests : []).filter((row) => row.sample)
    const availableTests = tests.length ? tests : Array.isArray(meta.tests) ? meta.tests : []
    if (!availableTests.length) {
      codingDebugForm(item)
      codingDebugOpen[item.questionId] = true
      codingResults[item.questionId] = {
        passed: false,
        rows: [],
        message: '题目未维护结构化测试用例，请先在题库中补充',
      }
      return codingResults[item.questionId]
    }
    return runCodingTests(item, availableTests)
  }
  async function runCodingDebug(item) {
    try {
      const form = codingDebugForm(item)
      const parameterCount = Number(codingMeta(item).parameterCount || 0)
      return await runCodingTests(item, [buildDebugTestCase(form.argsText, form.expectedText, parameterCount)])
    } catch (err) {
      codingResults[item.questionId] = { passed: false, rows: [], message: err?.message || '调试参数格式不正确' }
      return codingResults[item.questionId]
    }
  }
  async function runCodingTests(item, tests) {
    const meta = codingMeta(item)
    const rows = Array.isArray(tests) ? tests : []
    const language = currentCodingLanguage(item.questionId)
    const functionName = meta.functionName || extractFunctionName(answers[item.questionId], language) || 'solution'
    if (!rows.length) {
      codingResults[item.questionId] = { passed: false, rows: [], message: '未维护测试用例' }
      return codingResults[item.questionId]
    }
    codingRunning[item.questionId] = true
    try {
      const result = await runCodeSample({
        language,
        source: answers[item.questionId] || '',
        functionName,
        tests: rows,
      })
      const resultRows = Array.isArray(result.rows) ? result.rows : []
      const passed = Boolean(result.passed)
      codingResults[item.questionId] = { passed, rows: resultRows, message: result.message || '' }
      return codingResults[item.questionId]
    } catch (err) {
      codingResults[item.questionId] = { passed: false, rows: [], message: err?.message || '运行失败' }
      return codingResults[item.questionId]
    } finally {
      codingRunning[item.questionId] = false
    }
  }
  async function runAllCodingBeforeSubmit() {
    const codingQuestions = (currentExam.value?.questions || []).filter(isCodingQuestion)
    for (const item of codingQuestions) {
      const meta = codingMeta(item)
      const tests = Array.isArray(meta.tests) ? meta.tests : []
      await runCodingTests(item, tests)
    }
  }
  function codingSubmitPayload() {
    const result = {}
    for (const q of currentExam.value?.questions || []) {
      if (isCodingQuestion(q)) result[q.questionId] = Boolean(codingResults[q.questionId]?.passed)
    }
    return result
  }
  async function copyPracticeCode(item) {
    const questionId = item?.questionId
    if (!questionId) return
    const success = await copyText(answers[questionId] || '')
    codeCopyState[questionId] = success ? '已复制' : '复制失败'
    window.setTimeout(() => {
      delete codeCopyState[questionId]
    }, 1800)
  }
  function codingResultRows(questionId) {
    return codingResults[questionId]?.rows || []
  }
  function codingResultSummary(questionId) {
    return codingResultSummaryUtil(codingResults[questionId])
  }

  return {
    activeMode,
    loading,
    saving,
    examLoading,
    error,
    questions,
    selectedIds,
    filters,
    pagination,
    batchForm,
    showBatchEditor,
    deleteDialog,
    filteredQuestions,
    selectedSet,
    allCurrentPageSelected,
    visiblePages,
    loadQuestions,
    goPage,
    changePageSize,
    normalizeQuestionRow,
    rowNumber,
    toggleSelection,
    toggleCurrentPage,
    clearSelection,
    applyBatchUpdate,
    applyBatchDelete,
    createManualPractice,
    upsertQuestionRow,
    removeQuestion,
    closeDeleteDialog,
    confirmDelete,
    loadQuestionMeta,
    bankTypeOptions,
    categories,
    difficulties,
    questionTypes,
    bankTypeLabel,
    timerRemaining,
    remainingTimeText,
    startExamTimer,
    stopExamTimer,
    practiceModalRef,
    editModalRef,
    batchTagDraft,
    batchTagError,
    recordsLoading,
    recordsError,
    recordsDrawerOpen,
    examDetailLoading,
    exams,
    currentExam,
    activeQuestionId,
    answers,
    codingResults,
    codingRunning,
    codingLanguageByQuestion,
    codingDebugOpen,
    codingDebugForms,
    codeCopyState,
    practiceDialog,
    timerExpired,
    activeFilterCount,
    batchTags,
    addBatchTag,
    removeBatchTag,
    handleBatchTagKeydown,
    applyBatchChanges,
    pageEyebrow,
    pageTitle,
    pageDescription,
    showAnswerMode,
    isOpeningTargetExam,
    examTotalCount,
    answeredCount,
    unansweredQuestions,
    examProgressPercent,
    currentQuestionIndexMap,
    activeQuestion,
    currentQuestionIndex,
    practiceDialogEyebrow,
    practiceDialogTitle,
    practiceDialogDescription,
    practiceDialogConfirmText,
    loadAll,
    searchQuestions,
    resetFilters,
    switchBankTab,
    handleGlobalKeydown,
    openCreateModal,
    openEditModal,
    handleQuestionSaved,
    openPracticeModal,
    handlePracticeCreated,
    startSelectedPractice,
    startSingleQuestionPractice,
    currentBankTypeLabel,
    loadExams,
    examShowAnswer,
    openRecordsDrawer,
    closeRecordsDrawer,
    requestOpenExam,
    currentCodingLanguage,
    setCodingLanguage,
    isQuestionAnswered,
    setActiveQuestion,
    goAdjacentQuestion,
    selectedAnswerKeys,
    isOptionSelected,
    updateOptionAnswer,
    openExam,
    resetPracticeAnswers,
    submitCurrentExam,
    requestBackToBank,
    closePracticeDialog,
    confirmPracticeDialog,
    codingDebugForm,
    toggleCodingDebug,
    runCodingSample,
    runCodingDebug,
    runCodingTests,
    runAllCodingBeforeSubmit,
    codingSubmitPayload,
    copyPracticeCode,
    codingResultRows,
    codingResultSummary,
    answerContent,
    codingLanguageOptions,
    difficultyClass,
    displayTitle,
    tagLabels,
    isCodingQuestion,
    codingMeta,
    isMultiChoice,
    optionItems,
    questionStem,
    displayExamTitle,
    formatExamStartedAt,
    isCurrentExam,
    InterviewBankHeader,
    PracticeConfigModal,
    PracticeMarkdown,
    QuestionEditModal,
  }
}
