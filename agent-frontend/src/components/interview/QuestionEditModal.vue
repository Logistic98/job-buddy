<template>
  <div v-if="visible" class="modal-mask" @click.self="close">
    <div class="modal-card interview-modal-card practice-create-modal maintain-modal">
      <button class="close" @click="close">×</button>
      <div class="practice-modal-head"><h2>{{ modalTitle }}</h2><p>维护算法题和问答题，可手动录入，也可上传资料后由 AI 辅助生成。</p></div>
      <div class="interview-modal-tabs">
        <button :class="{ active: modalMode === 'manual' }" @click="modalMode = 'manual'">手动录入</button>
        <button :class="{ active: modalMode === 'ai' }" @click="modalMode = 'ai'">AI 生成</button>
      </div>

      <div v-if="modalMode === 'manual'" class="practice-form">
        <div class="practice-section">
          <span class="practice-field-label">基本信息</span>
          <div class="maintain-field-grid">
            <label class="practice-field wide"><span class="practice-field-label">标题</span><input v-model="form.title" placeholder="例如：Java HashMap 扩容机制" /><small class="field-hint">标题用于列表检索和练习展示，建议保持短句且明确知识点。</small></label>
            <label class="practice-field"><span class="practice-field-label">题库</span><select v-model="form.bankType" @change="syncFormBankType"><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
            <label class="practice-field"><span class="practice-field-label">分类</span><input v-model="form.category" placeholder="Java / Spring / MySQL / 数组" /></label>
            <label class="practice-field"><span class="practice-field-label">难度</span><select v-model="form.difficulty"><option>简单</option><option>中等</option><option>困难</option></select></label>
            <label v-if="form.bankType !== 'leetcode'" class="practice-field"><span class="practice-field-label">题型</span><select v-model="form.questionType"><option v-for="item in formQuestionTypes" :key="item" :value="item">{{ item }}</option></select></label>
            <label class="practice-field"><span class="practice-field-label">标签</span><input v-model="form.tagsText" placeholder="逗号分隔，如 Java,集合" /><small class="field-hint">用于后续筛选与组卷，可填写多个。</small></label>
          </div>
        </div>

        <div class="practice-section">
          <span class="practice-field-label">{{ isChoiceForm ? '题干与选项' : '题目内容' }}</span>
          <label class="practice-field"><span class="practice-field-label">{{ isChoiceForm ? '题干' : '内容' }}</span><textarea v-model="form.content" :placeholder="isChoiceForm ? '请输入选择题题干，选项在下方填写' : '请输入笔试题内容'" /><small class="field-hint">题干只写问题本身，选择项在下方独立维护，避免重复解析出错。</small></label>
          <div v-if="isChoiceForm" class="choice-option-editor">
            <div class="choice-option-head"><span>选项</span><button type="button" class="secondary-btn" @click="addOption">新增选项</button></div>
            <label v-for="(option, index) in form.options" :key="option.key" class="choice-option-row">
              <b>{{ option.key }}</b>
              <input v-model="option.text" :placeholder="`选项 ${option.key}`" />
              <button type="button" class="danger-text" :disabled="form.options.length <= 2" @click="removeOption(index)">删除</button>
            </label>
            <label class="practice-field"><span class="practice-field-label">正确答案</span><input v-model="form.answer" :placeholder="form.questionType === '多选' ? '例如：A,C' : '例如：A'" /></label>
          </div>
          <div v-else-if="form.bankType === 'leetcode'" class="coding-meta-editor">
            <label class="practice-field"><span class="practice-field-label">默认语言</span><select v-model="form.codingLanguage" @change="resetCodingTemplateForLanguage"><option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
            <label class="practice-field wide"><span class="practice-field-label">初始代码模板</span><textarea v-model="form.codingTemplate" :placeholder="buildDefaultTemplate('solution', form.codingLanguage)" /></label>
            <label class="practice-field wide"><span class="practice-field-label">测试用例 JSON</span><textarea v-model="form.codingTestsText" placeholder="[{&quot;name&quot;:&quot;示例&quot;,&quot;args&quot;:[[2,7],9],&quot;expected&quot;:[0,1],&quot;sample&quot;:true}]" /><small class="field-hint">每条用例需包含 name、args、expected；sample=true 会作为练习中的样例运行。</small></label>
            <label class="practice-field wide"><span class="practice-field-label">参考答案 / 判分说明</span><textarea v-model="form.answer" placeholder="以测试用例通过情况作为主要评分依据" /></label>
          </div>
          <label v-else class="practice-field"><span class="practice-field-label">参考答案 / 判分关键词</span><textarea v-model="form.answer" placeholder="练习提交时会用参考答案做简单自动判分" /></label>
        </div>
      </div>

      <div v-else class="practice-form ai-generate-panel">
        <div class="practice-section">
          <span class="practice-field-label">生成设置</span>
          <div class="maintain-field-grid">
            <label class="practice-field wide"><span class="practice-field-label">方向 / 主题</span><input v-model="aiForm.topic" placeholder="例如：Java 后端、Agent 工程" /></label>
            <label class="practice-field"><span class="practice-field-label">题库</span><select v-model="aiForm.bankType" @change="syncAiBankType"><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
            <label class="practice-field"><span class="practice-field-label">分类</span><input v-model="aiForm.category" placeholder="Java" /></label>
            <label class="practice-field"><span class="practice-field-label">难度</span><select v-model="aiForm.difficulty"><option>简单</option><option>中等</option><option>困难</option></select></label>
            <label v-if="aiForm.bankType !== 'leetcode'" class="practice-field"><span class="practice-field-label">题型</span><select v-model="aiForm.questionType"><option v-for="item in aiQuestionTypes" :key="item" :value="item">{{ item }}</option></select></label>
            <label class="practice-field"><span class="practice-field-label">数量</span><input v-model.number="aiForm.count" type="number" min="1" max="20" /></label>
          </div>
        </div>

        <div class="practice-section">
          <span class="practice-field-label">参考资料</span>
          <div class="doc-upload-field">
            <label class="doc-upload-box">
              <input type="file" accept=".txt,.md,.markdown,.json,.csv" @change="handleAiDocumentChange" />
              <b>选择文档</b>
              <small>{{ aiForm.documentName || '支持 TXT / MD / JSON / CSV，上传后自动填入下方资料区' }}</small>
            </label>
            <button v-if="aiForm.documentName" type="button" class="doc-clear-btn" @click="clearAiDocument">清除文档</button>
          </div>
          <label class="practice-field"><span class="practice-field-label">文档内容 / 补充资料</span><textarea v-model="aiForm.documentText" placeholder="可上传文档自动填入，也可以粘贴岗位 JD、技术文档、知识点材料" /></label>
          <label class="practice-field"><span class="practice-field-label">补充要求</span><textarea v-model="aiForm.requirements" placeholder="例如：偏工程实践，包含性能优化、排障、系统设计" /></label>
        </div>
      </div>

      <p v-if="modalError" class="error settings-error">{{ modalError }}</p>
      <div class="modal-actions practice-modal-actions">
        <button class="secondary-btn" @click="close">取消</button>
        <button class="primary-btn" :disabled="saving" @click="submitModal">{{ saving ? '处理中' : modalSubmitText }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
// Create/edit modal for interview questions with manual entry and AI generation tabs.
// Owns the two form states and question CRUD/generate API calls; emits `saved` with the raw
// saved row (or null after AI generation) so the parent can refresh the bank list.
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createQuestion, generateQuestions, updateQuestion } from '../../api/interview'
import { buildDefaultTemplate, codingLanguageOptions, codingMeta, defaultOptions, extractFunctionName, formatCodingTests, isChoiceType, normalizeCodingLanguage, optionItems, questionStem, tagLabels } from '../../utils/interviewBank'
import { buildQuestionPayload, validateAiForm, validateQuestionForm } from '../../utils/interviewForm'

defineProps({
  bankTypeOptions: { type: Array, default: () => [] },
})

const emit = defineEmits(['saved'])

const visible = ref(false)
const modalMode = ref('manual')
const editingId = ref('')
const saving = ref(false)
const modalError = ref('')
const form = reactive({ title: '', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', tagsText: '', content: '', answer: '', options: defaultOptions(), codingLanguage: 'python', codingFunctionName: '', codingSignature: '', codingTemplate: '', codingTestsText: '' })
const aiForm = reactive({ topic: 'Java 后端', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', count: 5, requirements: '', documentName: '', documentText: '' })

const modalTitle = computed(() => editingId.value ? '编辑题目' : '新增题目')
const modalSubmitText = computed(() => modalMode.value === 'manual' ? (editingId.value ? '保存修改' : '保存题目') : '生成并入库')
const isChoiceForm = computed(() => isChoiceType(form.questionType))
const formQuestionTypes = computed(() => form.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答'])
const aiQuestionTypes = computed(() => aiForm.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答'])

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

function handleKeydown(event) {
  if (!['Escape', 'Esc'].includes(event.key)) return
  if (visible.value && !saving.value) close()
}

function openCreate(defaultBankType = '') {
  editingId.value = ''
  modalMode.value = 'manual'
  modalError.value = ''
  resetForm()
  if (defaultBankType) {
    form.bankType = defaultBankType
    aiForm.bankType = defaultBankType
    syncFormBankType()
    syncAiBankType()
  }
  visible.value = true
}
function openEdit(item) {
  editingId.value = item.questionId
  modalMode.value = 'manual'
  modalError.value = ''
  const meta = codingMeta(item)
  Object.assign(form, {
    title: item.title || '', bankType: item.bankType || 'baguwen', category: item.category || 'Java', difficulty: item.difficulty || '中等', questionType: item.questionType || (item.bankType === 'leetcode' ? '编程题' : '单选'),
    tagsText: tagLabels(item).join(','), content: questionStem(item), answer: item.answer || '', options: optionItems(item).length ? optionItems(item) : defaultOptions(),
    codingLanguage: normalizeCodingLanguage(meta.language || 'python'), codingFunctionName: meta.functionName || '', codingSignature: meta.signature || '', codingTemplate: meta.template || '', codingTestsText: formatCodingTests(meta.tests),
  })
  syncFormBankType()
  if (form.bankType === 'leetcode' && !form.codingTemplate) form.codingTemplate = buildDefaultTemplate(form.codingFunctionName || 'solution', form.codingLanguage)
  visible.value = true
}
function close() { visible.value = false }
function resetForm() { Object.assign(form, { title: '', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', tagsText: '', content: '', answer: '', options: defaultOptions(), codingLanguage: 'python', codingFunctionName: '', codingSignature: '', codingTemplate: '', codingTestsText: '' }) }
async function submitModal() {
  modalError.value = ''
  if (modalMode.value === 'manual') return saveQuestion()
  return submitAiGenerate()
}
async function saveQuestion() {
  saving.value = true
  try {
    validateQuestionForm(form)
    const payload = buildQuestionPayload(form)
    const saved = editingId.value ? await updateQuestion(editingId.value, payload) : await createQuestion(payload)
    visible.value = false
    emit('saved', saved)
  } catch (err) { modalError.value = err.message || '保存失败' } finally { saving.value = false }
}
async function submitAiGenerate() {
  saving.value = true
  try {
    validateAiForm(aiForm)
    await generateQuestions(aiForm)
    visible.value = false
    emit('saved', null)
  } catch (err) { modalError.value = err.message || 'AI 生成失败' } finally { saving.value = false }
}
function addOption() {
  const key = String.fromCharCode(65 + form.options.length)
  form.options.push({ key, text: '' })
}
function removeOption(index) {
  if (form.options.length <= 2) return
  form.options.splice(index, 1)
  form.options.forEach((item, idx) => { item.key = String.fromCharCode(65 + idx) })
}
function syncFormBankType() {
  if (form.bankType === 'leetcode') {
    form.questionType = '编程题'
    if (!form.codingLanguage) form.codingLanguage = 'python'
    if (!form.codingFunctionName) form.codingFunctionName = 'solution'
    if (!form.codingTemplate) form.codingTemplate = buildDefaultTemplate(form.codingFunctionName, form.codingLanguage)
  } else if (form.questionType === '编程题') {
    form.questionType = '单选'
  }
}
function resetCodingTemplateForLanguage() {
  const functionName = extractFunctionName(form.codingTemplate, form.codingLanguage) || form.codingFunctionName || 'solution'
  form.codingFunctionName = functionName
  form.codingTemplate = buildDefaultTemplate(functionName, form.codingLanguage)
}
function syncAiBankType() {
  if (aiForm.bankType === 'leetcode') aiForm.questionType = '编程题'
  else if (aiForm.questionType === '编程题') aiForm.questionType = '单选'
}
function handleAiDocumentChange(event) {
  modalError.value = ''
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    aiForm.documentName = file.name
    aiForm.documentText = String(reader.result || '').slice(0, 20000)
  }
  reader.onerror = () => { modalError.value = '文档读取失败，请换成 txt、md 或可读取的文本文件' }
  reader.readAsText(file, 'utf-8')
}
function clearAiDocument() {
  aiForm.documentName = ''
  aiForm.documentText = ''
}

defineExpose({ openCreate, openEdit })
</script>
