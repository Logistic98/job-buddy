<template>
  <div v-if="visible" class="modal-mask" @click.self="close">
    <div class="modal-card interview-modal-card practice-create-modal">
      <button class="close" @click="close">×</button>
      <div class="practice-modal-head"><h2>随机组卷</h2><p>设置练习名称、限时和练习模式，再按题库、分类、难度、题型组合抽题。</p></div>

      <div class="practice-form">
        <div class="practice-section">
          <label class="practice-field">
            <span class="practice-field-label">练习名称</span>
            <input v-model="examConfig.title" placeholder="例如：Java 后端算法与理论组合练习" />
            <small class="field-hint">建议写清方向和题型组合，便于练习记录复盘。</small>
          </label>
          <div class="practice-field">
            <span class="practice-field-label">限时时长</span>
            <div class="practice-duration">
              <button
                v-for="min in durationPresets"
                :key="min"
                type="button"
                :class="['practice-chip', { active: examConfig.durationMinutes === min }]"
                @click="examConfig.durationMinutes = min"
              >
                {{ min }} 分钟
              </button>
              <label class="practice-duration-custom">
                <input v-model.number="examConfig.durationMinutes" type="number" min="1" max="240" />
                <span>分钟</span>
              </label>
              <small class="field-hint inline">限时范围 1-240 分钟。</small>
            </div>
          </div>
        </div>

        <div class="practice-section">
          <span class="practice-field-label">练习模式</span>
          <div class="practice-mode-cards">
            <label :class="['practice-mode-card', { active: examConfig.answerMode === 'hidden' }]">
              <input type="radio" value="hidden" v-model="examConfig.answerMode" />
              <b>考试模式</b>
              <small>先独立作答，提交后再看参考答案</small>
            </label>
            <label :class="['practice-mode-card', { active: examConfig.answerMode === 'visible' }]">
              <input type="radio" value="visible" v-model="examConfig.answerMode" />
              <b>学习模式</b>
              <small>练习中展示参考答案，适合快速巩固</small>
            </label>
          </div>
        </div>

        <div class="practice-section">
          <div class="practice-section-head">
            <span class="practice-field-label">组卷规则</span>
            <span class="practice-total-pill">共 {{ examRuleTotal }} 题</span>
          </div>
          <div class="practice-rule-table">
            <div class="practice-rule-head">
              <span>题库</span><span>分类</span><span>难度</span><span>题型</span><span>题数</span><span></span>
            </div>
            <div v-for="(rule, index) in examConfig.rules" :key="rule.id" class="practice-rule-row">
              <select v-model="rule.bankType"><option value="">全部题库</option><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select>
              <select v-model="rule.category"><option value="">全部分类</option><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select>
              <select v-model="rule.difficulty"><option value="">全部难度</option><option v-for="item in difficulties" :key="item" :value="item">{{ item }}</option></select>
              <select v-model="rule.questionType"><option value="">全部题型</option><option v-for="item in questionTypes" :key="item" :value="item">{{ item }}</option></select>
              <input v-model.number="rule.count" type="number" min="1" max="50" />
              <button type="button" class="practice-rule-remove" :disabled="examConfig.rules.length <= 1" title="删除该组合" @click="removeExamRule(index)">×</button>
            </div>
          </div>
          <p class="section-hint">可参考主流题单和套卷的设计方式，按题库、分类、难度、题型组合抽题；留空表示不限制。</p>
          <button type="button" class="practice-add-rule" @click="addExamRule">+ 添加规则</button>
        </div>
      </div>

      <p v-if="practiceModalError" class="error settings-error">{{ practiceModalError }}</p>
      <div class="modal-actions practice-modal-actions">
        <button class="secondary-btn" @click="close">取消</button>
        <button class="primary-btn" :disabled="examLoading || !examRuleTotal" @click="startExam">{{ examLoading ? '组卷中…' : `开始练习（${examRuleTotal} 题）` }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
// Random-exam configuration modal. Owns the exam config form (title, duration, answer mode,
// draw rules) and the createRandomExam call; emits `created` with the exam plus the configured
// duration in seconds so the parent can start the practice desk timer.
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createRandomExam } from '../../api/interview'
import { defaultPracticeTitle, examRuleTotal as computeExamRuleTotal, validatePracticeConfig } from '../../utils/interviewForm'

defineProps({
  bankTypeOptions: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  difficulties: { type: Array, default: () => [] },
  questionTypes: { type: Array, default: () => [] },
})

const emit = defineEmits(['created'])

const visible = ref(false)
const examLoading = ref(false)
const practiceModalError = ref('')
const durationPresets = [15, 30, 45, 60, 90]
const examConfig = reactive({ title: '', durationMinutes: 30, answerMode: 'hidden', rules: [newExamRule()] })
const examRuleTotal = computed(() => computeExamRuleTotal(examConfig.rules))

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

function handleKeydown(event) {
  if (!['Escape', 'Esc'].includes(event.key)) return
  if (visible.value) close()
}

function open() {
  practiceModalError.value = ''
  if (!examConfig.title.trim()) examConfig.title = defaultPracticeTitle()
  visible.value = true
}
function close() {
  if (examLoading.value) return
  visible.value = false
}
function newExamRule(data = {}) { return { id: crypto.randomUUID(), bankType: '', category: '', difficulty: '', questionType: '', count: 5, ...data } }
function addExamRule() { examConfig.rules.push(newExamRule({ count: 3 })) }
function removeExamRule(index) {
  if (examConfig.rules.length <= 1) return
  examConfig.rules.splice(index, 1)
}
async function startExam() {
  examLoading.value = true
  practiceModalError.value = ''
  try {
    validatePracticeConfig(examConfig)
    const payload = {
      title: examConfig.title,
      durationMinutes: Number(examConfig.durationMinutes || 30),
      showAnswer: examConfig.answerMode === 'visible',
      rules: examConfig.rules.map(rule => ({ bankType: rule.bankType, category: rule.category, difficulty: rule.difficulty, questionType: rule.questionType, count: Number(rule.count || 0) })).filter(rule => rule.count > 0),
    }
    const exam = await createRandomExam(payload)
    visible.value = false
    emit('created', exam, payload.durationMinutes * 60)
  } catch (err) { practiceModalError.value = err.message || '随机组卷失败' } finally { examLoading.value = false }
}

defineExpose({ open })
</script>
