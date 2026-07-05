<template>
  <section :class="embedded ? 'interview-embedded interview-manager-page' : 'system-page interview-page interview-manager-page'">
    <InterviewBankHeader
      :embedded="embedded"
      :active-mode="activeMode"
      :page-eyebrow="pageEyebrow"
      :page-title="pageTitle"
      :page-description="pageDescription"
      :loading="loading"
      :records-loading="recordsLoading"
      :bank-type-options="bankTypeOptions"
      :active-bank-type="filters.bankType"
      @create="openCreateModal"
      @refresh-bank="loadAll"
      @back-to-bank="emit('back-to-bank')"
      @practice="openPracticeModal"
      @refresh-exams="loadExams"
      @switch-bank="switchBankTab"
    />

    <p v-if="error" class="error settings-error">{{ error }}</p>

    <section v-if="activeMode === 'bank'" class="interview-bank-view glass-card">
      <div class="interview-filter-bar">
        <label class="history-search"><span>搜索</span><input v-model.trim="filters.keyword" placeholder="搜索标题、内容、答案或标签" @keyup.enter="loadQuestions" /></label>
        <select v-model="filters.category" @change="searchQuestions"><option value="">全部分类</option><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select>
        <select v-model="filters.difficulty" @change="searchQuestions"><option value="">全部难度</option><option v-for="item in difficulties" :key="item" :value="item">{{ item }}</option></select>
        <button class="secondary-btn" @click="searchQuestions">查询</button>
      </div>

      <div class="bank-summary-row">
        <span><strong>{{ pagination.total }}</strong> 题目</span>
        <span><strong>{{ categories.length }}</strong> 分类</span>
        <span v-if="selectedIds.length" class="bank-summary-selected"><strong>{{ selectedIds.length }}</strong> 已选</span>
      </div>

      <div v-if="selectedIds.length" class="selection-toolbar">
        <div class="selection-toolbar-main">
          <strong>已选 {{ selectedIds.length }} 题</strong>
          <button class="primary-btn" :disabled="examLoading" @click="startSelectedPractice">开始练习</button>
          <button class="secondary-btn" @click="showBatchEditor = !showBatchEditor">{{ showBatchEditor ? '收起批量设置' : '批量设置' }}</button>
          <button class="danger-btn" :disabled="saving" @click="applyBatchDelete">删除所选</button>
          <button class="secondary-btn" @click="clearSelection">取消选择</button>
        </div>
        <div v-if="showBatchEditor" class="selection-toolbar-editor">
          <input v-model.trim="batchForm.category" placeholder="分类，如 Java" />
          <select v-model="batchForm.difficulty"><option value="">难度不变</option><option>简单</option><option>中等</option><option>困难</option></select>
          <input v-model.trim="batchForm.tagsText" placeholder="标签，逗号分隔" />
          <button class="secondary-btn" :disabled="saving" @click="applyBatchUpdate">应用批量修改</button>
        </div>
      </div>

      <div class="interview-table-wrap">
        <div v-if="loading && !filteredQuestions.length" class="loading-state compact"><strong>题库加载中</strong><p>正在读取当前题库，请稍候。</p></div>
        <table v-else class="interview-table">
          <thead><tr><th class="select-col"><input type="checkbox" :checked="allCurrentPageSelected" @change="toggleCurrentPage($event.target.checked)" /></th><th class="index-col">序号</th><th>题目</th><th>分类</th><th>难度</th><th>题型</th><th>标签</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(item, index) in filteredQuestions" :key="item.questionId">
              <td class="select-col"><input type="checkbox" :checked="selectedSet.has(item.questionId)" @change="toggleSelection(item.questionId, $event.target.checked)" /></td>
              <td class="index-col"><span class="question-index">{{ rowNumber(index) }}</span></td>
              <td><strong>{{ displayTitle(item, index) }}</strong><p>{{ item.content }}</p></td>
              <td>{{ item.category || '通用' }}</td>
              <td><span class="state-badge warn">{{ item.difficulty || '中等' }}</span></td>
              <td>{{ item.questionType || '简答' }}</td>
              <td><div class="question-tags"><span v-for="tag in tagLabels(item).slice(0, 4)" :key="tag">{{ tag }}</span></div></td>
              <td><div class="table-actions"><button class="primary-text" @click="startSingleQuestionPractice(item)">单题练习</button><button class="secondary-btn" @click="openEditModal(item)">编辑</button><button class="danger-text" @click="removeQuestion(item.questionId)">删除</button></div></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !filteredQuestions.length" class="empty-state compact"><strong>暂无题目</strong><p>点击“新增题目”，可选择手动录入或 AI 生成。</p></div>
      </div>
      <div class="bank-pagination" v-if="pagination.total > 0">
        <span>第 {{ pagination.page }} / {{ pagination.pages || 1 }} 页，共 {{ pagination.total }} 题</span>
        <div>
          <select v-model.number="pagination.size" @change="changePageSize"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select>
          <button class="secondary-btn" :disabled="pagination.page <= 1" @click="goPage(pagination.page - 1)">上一页</button>
          <button v-for="page in visiblePages" :key="page" :class="['page-num-btn', { active: page === pagination.page }]" @click="goPage(page)">{{ page }}</button>
          <button class="secondary-btn" :disabled="pagination.page >= pagination.pages" @click="goPage(pagination.page + 1)">下一页</button>
        </div>
      </div>
    </section>

    <section v-else class="practice-desk-view">
      <div v-if="currentExam && activeQuestion" class="practice-leetcode-shell">
        <aside class="practice-left-panel glass-card">
          <div class="practice-left-top">
            <button class="secondary-btn compact" @click="emit('back-to-bank')">返回题库</button>
            <button class="primary-btn compact" @click="openPracticeModal">随机组卷</button>
          </div>
          <div class="practice-progress-card">
            <div>
              <strong>{{ answeredCount }}</strong>
              <span>/ {{ examTotalCount }} 已完成</span>
            </div>
            <div class="exam-progress-bar" aria-label="作答进度"><span :style="{ width: `${examProgressPercent}%` }"></span></div>
            <small v-if="currentExam.status !== 'submitted'">剩余 {{ remainingTimeText }}</small>
            <small v-else>得分 {{ currentExam.score }}</small>
          </div>
          <div class="practice-question-list" aria-label="题目列表">
            <button
              v-for="(item, index) in currentExam.questions || []"
              :key="item.questionId"
              type="button"
              :class="['practice-question-pill', { active: item.questionId === activeQuestion.questionId, answered: isQuestionAnswered(item) }]"
              @click="setActiveQuestion(item.questionId)"
            >
              <b>{{ index + 1 }}</b>
              <span>{{ item.title }}</span>
              <em>{{ isQuestionAnswered(item) ? '已答' : '未答' }}</em>
            </button>
          </div>
        </aside>

        <main class="practice-problem-panel glass-card">
          <div class="practice-panel-head">
            <div>
              <p class="eyebrow">{{ bankTypeLabel(activeQuestion.bankType) }}</p>
              <h2>{{ currentQuestionIndex }}. {{ activeQuestion.title }}</h2>
            </div>
            <span :class="['state-badge', difficultyClass(activeQuestion.difficulty)]">{{ activeQuestion.difficulty || '中等' }}</span>
          </div>
          <div class="practice-problem-body">
            <p class="practice-stem">{{ questionStem(activeQuestion) }}</p>
            <div class="question-tags">
              <span v-for="tag in tagLabels(activeQuestion).slice(0, 6)" :key="tag">{{ tag }}</span>
            </div>
            <section v-if="optionItems(activeQuestion).length" class="practice-description-block">
              <h3>选项</h3>
              <div class="exam-option-list readonly-options">
                <label v-for="option in optionItems(activeQuestion)" :key="option.key" class="exam-option readonly">
                  <b>{{ option.key }}</b><span>{{ option.text }}</span>
                </label>
              </div>
            </section>
            <section v-if="showAnswerMode || currentExam.status === 'submitted'" class="answer-review leetcode-answer-review">
              <strong :class="currentExam.status === 'submitted' ? (activeQuestion.correct ? 'ok-text' : 'error') : 'ok-text'">
                {{ currentExam.status === 'submitted' ? (activeQuestion.correct ? '正确' : '待改进') : '参考答案' }}
              </strong>
              <p>{{ activeQuestion.answer || '未维护参考答案' }}</p>
            </section>
          </div>
        </main>

        <section class="practice-answer-panel glass-card">
          <div class="practice-panel-head answer-head">
            <div>
              <p class="eyebrow">Answer</p>
              <h2>{{ isCodingQuestion(activeQuestion) ? '代码编辑器' : '作答区' }}</h2>
            </div>
            <span :class="['state-badge', currentExam.status === 'submitted' ? 'ok' : (timerRemaining <= 60 ? 'danger' : 'warn')]">
              {{ currentExam.status === 'submitted' ? `得分 ${currentExam.score}` : remainingTimeText }}
            </span>
          </div>

          <div v-if="isCodingQuestion(activeQuestion)" class="practice-code-panel leetcode-answer-editor">
            <div class="leetcode-editor-toolbar">
              <label><span>语言</span><select :value="currentCodingLanguage(activeQuestion.questionId)" :disabled="currentExam.status === 'submitted' || timerExpired" @change="setCodingLanguage(activeQuestion.questionId, $event.target.value)"><option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <span>{{ codingSignature(activeQuestion) }}</span>
            </div>
            <textarea v-model="answers[activeQuestion.questionId]" :disabled="currentExam.status === 'submitted' || timerExpired" spellcheck="false" class="leetcode-code-editor practice-code-editor" />
            <div class="leetcode-run-actions">
              <button class="secondary-btn" :disabled="codingRunning[activeQuestion.questionId] || currentExam.status === 'submitted'" @click="runCodingSample(activeQuestion)">{{ codingRunning[activeQuestion.questionId] ? '运行中' : '运行样例' }}</button>
              <span>{{ codingResultSummary(activeQuestion.questionId) }}</span>
            </div>
            <div v-if="codingResultRows(activeQuestion.questionId).length" class="leetcode-result-list compact-code-results">
              <article v-for="result in codingResultRows(activeQuestion.questionId)" :key="result.name" :class="['leetcode-result-item', result.passed ? 'passed' : 'failed']">
                <div><strong>{{ result.name }}</strong><span>{{ result.passed ? '通过' : '未通过' }}</span></div>
                <p>输入：{{ result.input }}</p><p>期望：{{ result.expected }}</p><p>实际：{{ result.actual }}</p>
                <p v-if="result.error" class="error">错误：{{ result.error }}</p>
              </article>
            </div>
          </div>

          <div v-else-if="optionItems(activeQuestion).length" class="practice-choice-answer">
            <label v-for="option in optionItems(activeQuestion)" :key="option.key" :class="['exam-option', { selected: isOptionSelected(activeQuestion, option.key) }]">
              <input :type="isMultiChoice(activeQuestion) ? 'checkbox' : 'radio'" :name="activeQuestion.questionId" :value="option.key" :checked="isOptionSelected(activeQuestion, option.key)" :disabled="currentExam.status === 'submitted' || timerExpired" @change="updateOptionAnswer(activeQuestion, option.key, $event.target.checked)" />
              <b>{{ option.key }}</b><span>{{ option.text }}</span>
            </label>
          </div>

          <textarea v-else v-model="answers[activeQuestion.questionId]" :disabled="currentExam.status === 'submitted' || timerExpired" class="practice-text-answer" placeholder="请输入你的答案" />

          <div class="practice-bottom-bar">
            <button class="secondary-btn" :disabled="currentQuestionIndex <= 1" @click="goAdjacentQuestion(-1)">上一题</button>
            <button class="secondary-btn" :disabled="currentQuestionIndex >= examTotalCount" @click="goAdjacentQuestion(1)">下一题</button>
            <span v-if="currentExam.status !== 'submitted'">还有 {{ unansweredQuestions.length }} 题未答</span>
            <button v-if="currentExam.status !== 'submitted'" class="primary-btn" :disabled="examLoading" @click="submitCurrentExam">{{ timerExpired ? '时间到，提交得分' : '提交练习' }}</button>
          </div>
        </section>
      </div>

      <div v-else class="practice-start-grid">
        <section class="glass-card practice-start-card">
          <div v-if="recordsLoading || examDetailLoading" class="loading-state compact"><strong>练习数据加载中</strong><p>正在读取练习记录。</p></div>
          <div v-else class="empty-state compact">
            <strong>选择一种练习方式</strong>
            <p>从题库点“单题练习”，或在这里随机组卷。</p>
            <div class="history-header-actions center-actions">
              <button class="secondary-btn" @click="emit('back-to-bank')">返回题库</button>
              <button class="primary-btn" @click="openPracticeModal">随机组卷</button>
            </div>
          </div>
        </section>
        <aside class="glass-card exam-record-card practice-records-clean">
          <div class="card-title"><h2>练习记录</h2><span>{{ exams.length }} 次</span></div>
          <div v-if="recordsLoading" class="loading-state compact"><strong>记录加载中</strong><p>正在同步最新练习记录。</p></div>
          <template v-else>
            <button v-for="exam in exams" :key="exam.examId" class="exam-record" @click="openExam(exam.examId)">
              <span>{{ displayExamTitle(exam) }}<em v-if="examShowAnswer(exam)" class="exam-mode-tag">学习模式</em></span><b>{{ exam.status === 'submitted' ? `${exam.score} 分` : '进行中' }}</b>
            </button>
          </template>
          <div v-if="!recordsLoading && !exams.length" class="empty-state compact"><strong>暂无记录</strong><p>完成练习后会在这里保存得分。</p></div>
        </aside>
      </div>
    </section>

    <PracticeConfigModal
      ref="practiceModalRef"
      :bank-type-options="bankTypeOptions"
      :categories="categories"
      :difficulties="difficulties"
      :question-types="questionTypes"
      @created="handlePracticeCreated"
    />

    <div v-if="deleteDialog.visible" class="modal-mask interview-delete-mask" @click.self="closeDeleteDialog">
      <div class="interview-delete-modal">
        <button class="close" @click="closeDeleteDialog">×</button>
        <p class="eyebrow">删除题目</p>
        <h2>{{ deleteDialog.mode === 'batch' ? '批量删除题目？' : '删除这道题目？' }}</h2>
        <p>{{ deleteDialog.mode === 'batch' ? `确认删除选中的 ${deleteDialog.count} 道笔试题？` : '确认删除这道笔试题？' }}</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeDeleteDialog">取消</button>
          <button class="danger-btn" :disabled="saving" @click="confirmDelete">{{ saving ? '删除中' : '确认删除' }}</button>
        </div>
      </div>
    </div>

    <QuestionEditModal ref="editModalRef" :bank-type-options="bankTypeOptions" @saved="handleQuestionSaved" />
  </section>
</template>

<script setup>
// Interview bank container: composes the bank list (useQuestionBank), shared metadata
// (useQuestionMeta) and countdown timer (useExamTimer) with the practice desk state that
// remains local (current exam, answers, coding runs). Modal editing and random-exam
// configuration live in QuestionEditModal / PracticeConfigModal.
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getExam, listExams, runCodeSample, submitExam } from '../api/interview'
import InterviewBankHeader from './interview/InterviewBankHeader.vue'
import PracticeConfigModal from './interview/PracticeConfigModal.vue'
import QuestionEditModal from './interview/QuestionEditModal.vue'
import { useExamTimer } from '../composables/useExamTimer'
import { useQuestionBank } from '../composables/useQuestionBank'
import { useQuestionMeta } from '../composables/useQuestionMeta'
import { buildDefaultTemplate, codingLanguageOptions, codingMeta, defaultSignature, difficultyClass, displayTitle, extractFunctionName, isCodingQuestion, isMultiChoice, normalizeCodingLanguage, optionItems, questionStem, tagLabels } from '../utils/interviewBank'
import { codingResultSummary as codingResultSummaryUtil, displayExamTitle, selectedAnswerKeys as selectedAnswerKeysUtil } from '../utils/interviewForm'

const props = defineProps({
  mode: { type: String, default: 'bank' },
  embedded: { type: Boolean, default: false },
  initialExamId: { type: String, default: '' },
})

const emit = defineEmits(['practice-created', 'back-to-bank'])

const activeMode = computed(() => props.mode === 'exam' ? 'exam' : 'bank')

const {
  loading, saving, examLoading, error, questions, selectedIds, filters, pagination, batchForm, showBatchEditor, deleteDialog,
  filteredQuestions, selectedSet, allCurrentPageSelected, visiblePages,
  loadQuestions, goPage, changePageSize, normalizeQuestionRow, rowNumber,
  toggleSelection, toggleCurrentPage, clearSelection,
  applyBatchUpdate, applyBatchDelete, createManualPractice, upsertQuestionRow,
  removeQuestion, closeDeleteDialog, confirmDelete,
} = useQuestionBank(props.mode === 'bank' ? 'leetcode' : '')

const { loadQuestionMeta, bankTypeOptions, categories, difficulties, questionTypes, bankTypeLabel } =
  useQuestionMeta(() => Array.from(new Set(questions.value.map(item => item.category).filter(Boolean))))

const { timerRemaining, remainingTimeText, startExamTimer, stopExamTimer } = useExamTimer(submitCurrentExam)

const practiceModalRef = ref(null)
const editModalRef = ref(null)
const recordsLoading = ref(false)
const examDetailLoading = ref(false)
const exams = ref([])
const currentExam = ref(null)
const activeQuestionId = ref('')
const answers = reactive({})
const codingResults = reactive({})
const codingRunning = reactive({})
const codingLanguageByQuestion = reactive({})

const timerExpired = computed(() => Boolean(currentExam.value && currentExam.value.status !== 'submitted' && timerRemaining.value <= 0))
const pageEyebrow = computed(() => activeMode.value === 'exam' ? 'Practice Desk' : 'Question Bank')
const pageTitle = computed(() => activeMode.value === 'exam' ? '练习台' : '题库')
const pageDescription = computed(() => activeMode.value === 'exam'
  ? '查看练习记录、继续作答或通过随机组卷开始新练习。'
  : '按题库维护题目，支持单题练习、勾选后练习和批量设置。')
const showAnswerMode = computed(() => Boolean(currentExam.value?.strategy?.showAnswer))
const examTotalCount = computed(() => currentExam.value?.questions?.length || 0)
const answeredCount = computed(() => (currentExam.value?.questions || []).filter(isQuestionAnswered).length)
const unansweredQuestions = computed(() => (currentExam.value?.questions || []).filter(item => !isQuestionAnswered(item)))
const examProgressPercent = computed(() => examTotalCount.value ? Math.round((answeredCount.value / examTotalCount.value) * 100) : 0)
const currentQuestionIndexMap = computed(() => Object.fromEntries((currentExam.value?.questions || []).map((item, index) => [item.questionId, index + 1])))
const activeQuestion = computed(() => {
  const list = currentExam.value?.questions || []
  return list.find(item => item.questionId === activeQuestionId.value) || list[0] || null
})
const currentQuestionIndex = computed(() => activeQuestion.value ? (currentQuestionIndexMap.value[activeQuestion.value.questionId] || 1) : 0)

onMounted(() => {
  loadAll()
  document.addEventListener('keydown', handleGlobalKeydown)
})
onBeforeUnmount(() => {
  stopExamTimer()
  document.removeEventListener('keydown', handleGlobalKeydown)
})

watch(() => props.initialExamId, async examId => {
  if (activeMode.value !== 'exam' || !examId) return
  await loadExams()
  await openExam(examId)
}, { immediate: true })

async function loadAll() {
  if (activeMode.value === 'exam') await Promise.all([loadQuestionMeta(filters.bankType), loadExams()])
  else await Promise.all([loadQuestionMeta(filters.bankType), loadQuestions()])
}
async function searchQuestions() {
  pagination.page = 1
  clearSelection()
  questions.value = []
  return Promise.all([loadQuestionMeta(filters.bankType), loadQuestions()])
}
function switchBankTab(value) {
  if (filters.bankType === value) return
  filters.bankType = value
  filters.category = ''
  return searchQuestions()
}
function handleGlobalKeydown(event) {
  if (!['Escape', 'Esc'].includes(event.key)) return
  if (deleteDialog.visible) closeDeleteDialog()
}

function openCreateModal() { editModalRef.value?.openCreate(filters.bankType) }
function openEditModal(item) { editModalRef.value?.openEdit(item) }
async function handleQuestionSaved(saved) {
  if (saved) upsertQuestionRow(normalizeQuestionRow(saved))
  await loadQuestions()
}

function openPracticeModal() { practiceModalRef.value?.open() }
async function handlePracticeCreated(exam, fallbackSeconds) {
  error.value = ''
  currentExam.value = exam
  resetPracticeAnswers(exam)
  startExamTimer(Number(exam.remainingSeconds || fallbackSeconds), true)
  await loadExams()
}

async function startSelectedPractice() {
  if (!selectedIds.value.length || examLoading.value) return
  const selectedQuestions = questions.value.filter(item => selectedSet.value.has(item.questionId))
  const title = `${currentBankTypeLabel()} 所选题练习（${selectedIds.value.length} 题）`
  await createManualPractice(selectedIds.value, title, selectedQuestions.length === 1, exam => emit('practice-created', exam))
}
async function startSingleQuestionPractice(item) {
  if (!item?.questionId || examLoading.value) return
  await createManualPractice([item.questionId], `${displayTitle(item, 0)} 单题练习`, true, exam => emit('practice-created', exam))
}
function currentBankTypeLabel() { return bankTypeLabel(filters.bankType) || '题库' }

async function loadExams() {
  recordsLoading.value = true
  try {
    exams.value = await listExams()
  } catch (_) {
    exams.value = []
  } finally {
    recordsLoading.value = false
  }
}
function examShowAnswer(exam) { return Boolean(exam?.strategy?.showAnswer) }

function currentCodingLanguage(questionId) {
  return normalizeCodingLanguage(codingLanguageByQuestion[questionId] || 'python')
}
function codingSignature(item) {
  const meta = codingMeta(item)
  const language = currentCodingLanguage(item.questionId)
  if (meta.signature && normalizeCodingLanguage(meta.language) === language) return meta.signature
  const functionName = meta.functionName || extractFunctionName(meta.template, normalizeCodingLanguage(meta.language)) || 'solution'
  return defaultSignature(functionName, language)
}
function setCodingLanguage(questionId, value) {
  const oldLanguage = currentCodingLanguage(questionId)
  const nextLanguage = normalizeCodingLanguage(value)
  const meta = codingMeta((currentExam.value?.questions || []).find(item => item.questionId === questionId) || {})
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
  const index = list.findIndex(item => item.questionId === activeQuestion.value?.questionId)
  const next = list[Math.min(Math.max(index + delta, 0), list.length - 1)]
  if (next) setActiveQuestion(next.questionId)
}
function selectedAnswerKeys(item) { return selectedAnswerKeysUtil(answers[item.questionId]) }
function isOptionSelected(item, key) { return selectedAnswerKeys(item).includes(key) }
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
    if (currentExam.value?.status !== 'submitted') startExamTimer(Number(currentExam.value.remainingSeconds || 0), true)
  } catch (err) {
    error.value = err.message || '练习详情加载失败'
  } finally {
    examDetailLoading.value = false
  }
}
function resetPracticeAnswers(exam, keepUserAnswer = false) {
  Object.keys(answers).forEach(key => delete answers[key])
  Object.keys(codingResults).forEach(key => delete codingResults[key])
  Object.keys(codingRunning).forEach(key => delete codingRunning[key])
  Object.keys(codingLanguageByQuestion).forEach(key => delete codingLanguageByQuestion[key])
  const list = exam?.questions || []
  for (const q of list) {
    const meta = codingMeta(q)
    const language = 'python'
    const functionName = meta.functionName || extractFunctionName(meta.template, normalizeCodingLanguage(meta.language)) || 'solution'
    codingLanguageByQuestion[q.questionId] = language
    answers[q.questionId] = keepUserAnswer ? (q.userAnswer || (isCodingQuestion(q) ? buildDefaultTemplate(functionName, language) : '')) : (isCodingQuestion(q) ? buildDefaultTemplate(functionName, language) : '')
    if (q.correct != null) codingResults[q.questionId] = { passed: Boolean(q.correct), rows: [] }
  }
  activeQuestionId.value = list[0]?.questionId || ''
}
async function submitCurrentExam() {
  if (!currentExam.value || examLoading.value) return
  examLoading.value = true
  error.value = ''
  try {
    await runAllCodingBeforeSubmit()
    currentExam.value = await submitExam(currentExam.value.examId, answers, codingSubmitPayload())
    stopExamTimer()
    for (const q of currentExam.value.questions || []) answers[q.questionId] = q.userAnswer || answers[q.questionId] || ''
    await loadExams()
  } catch (err) { error.value = err.message || '提交失败' } finally { examLoading.value = false }
}
async function runCodingSample(item) {
  const meta = codingMeta(item)
  const tests = (Array.isArray(meta.tests) ? meta.tests : []).filter(row => row.sample)
  return runCodingTests(item, tests.length ? tests : (Array.isArray(meta.tests) ? meta.tests : []))
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
    const result = await runCodeSample({ language, source: answers[item.questionId] || '', functionName, tests: rows })
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
function codingResultRows(questionId) { return codingResults[questionId]?.rows || [] }
function codingResultSummary(questionId) { return codingResultSummaryUtil(codingResults[questionId]) }
</script>
