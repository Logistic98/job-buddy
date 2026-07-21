<template>
  <div v-if="visible" class="modal-mask question-maintain-mask" @click.self="close">
    <div
      class="modal-card interview-modal-card practice-create-modal maintain-modal question-maintain-modal"
      :class="{
        'question-maintain-modal--compact': currentStep === 0,
        'question-maintain-modal--with-tabs': !isEditing,
      }"
      role="dialog"
      aria-modal="true"
      aria-labelledby="question-maintain-title"
    >
      <button type="button" class="close" :disabled="saving" aria-label="关闭题目维护弹窗" @click="close">×</button>
      <header class="practice-modal-head">
        <h2 id="question-maintain-title">{{ modalTitle }}</h2>
        <p>
          {{
            isEditing
              ? '分步修改当前题目的内容、分类和答案。'
              : '分步维护算法题和问答题，也可上传资料后由 AI 辅助生成。'
          }}
        </p>
      </header>
      <div v-if="!isEditing" class="interview-modal-tabs" role="tablist" aria-label="题目录入方式">
        <button
          type="button"
          role="tab"
          :aria-selected="modalMode === 'manual'"
          :disabled="saving"
          :class="{ active: modalMode === 'manual' }"
          @click="setModalMode('manual')"
        >
          手动录入
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="modalMode === 'ai'"
          :disabled="saving"
          :class="{ active: modalMode === 'ai' }"
          @click="setModalMode('ai')"
        >
          AI 生成
        </button>
      </div>

      <nav class="question-wizard-steps" aria-label="题目维护步骤">
        <button
          v-for="(step, index) in wizardSteps"
          :key="step.key"
          type="button"
          :class="{ active: currentStep === index, done: currentStep > index }"
          :disabled="saving"
          :aria-current="currentStep === index ? 'step' : undefined"
          @click="goToStep(index)"
        >
          <b>{{ index + 1 }}</b>
          <span
            ><strong>{{ step.label }}</strong
            ><small>{{ step.description }}</small></span
          >
        </button>
      </nav>

      <div ref="scrollContainer" class="question-maintain-scroll">
        <p v-if="modalError" class="error settings-error question-wizard-error" role="alert">{{ modalError }}</p>
        <div v-if="modalMode === 'manual'" class="practice-form question-wizard-panel">
          <div v-if="currentStep === 0" class="practice-section">
            <span class="practice-field-label">基本信息</span>
            <div class="maintain-field-grid">
              <label class="practice-field wide"
                ><span class="practice-field-label">标题</span
                ><input v-model="form.title" placeholder="例如：Java HashMap 扩容机制" /><small class="field-hint"
                  >标题用于列表检索和练习展示，建议保持短句且明确知识点。</small
                ></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">题库</span
                ><select v-model="form.bankType" @change="syncFormBankType">
                  <option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">分类</span
                ><input v-model="form.category" placeholder="Java / Spring / MySQL / 数组"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">难度</span
                ><select v-model="form.difficulty">
                  <option>简单</option>
                  <option>中等</option>
                  <option>困难</option>
                </select></label
              >
              <label v-if="form.bankType !== 'leetcode'" class="practice-field"
                ><span class="practice-field-label">题型</span
                ><select v-model="form.questionType">
                  <option v-for="item in formQuestionTypes" :key="item" :value="item">{{ item }}</option>
                </select></label
              >
              <div class="practice-field question-tag-field">
                <span class="practice-field-label">标签</span>
                <div v-if="formTags.length" class="question-tag-list" aria-label="当前题目标签">
                  <span v-for="tag in formTags" :key="tag"
                    >{{ tag
                    }}<button type="button" :aria-label="`移除标签 ${tag}`" @click="removeFormTag(tag)">×</button></span
                  >
                </div>
                <div class="question-tag-input-row">
                  <input
                    v-model.trim="tagDraft"
                    placeholder="输入一个标签后按回车，例如 Java"
                    @keydown="handleTagKeydown"
                  />
                  <button type="button" class="secondary-btn" :disabled="!tagDraft.trim()" @click="addFormTag">
                    添加标签
                  </button>
                </div>
                <small v-if="tagError" class="field-hint question-tag-error" role="alert">{{ tagError }}</small>
                <small v-else class="field-hint">请逐个添加标签，点击标签右侧 × 可移除。</small>
              </div>
            </div>
          </div>

          <div v-else-if="currentStep === 1" class="practice-section">
            <span class="practice-field-label">{{ isChoiceForm ? '题干与选项' : '题目内容' }}</span>
            <div class="practice-field markdown-editor-field">
              <div class="markdown-editor-head">
                <span class="practice-field-label">{{ isChoiceForm ? '题干' : '内容' }}</span>
                <div class="markdown-editor-tabs" role="tablist" aria-label="题目内容编辑模式">
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="contentEditorMode === 'edit'"
                    :class="{ active: contentEditorMode === 'edit' }"
                    @click="contentEditorMode = 'edit'"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="contentEditorMode === 'preview'"
                    :class="{ active: contentEditorMode === 'preview' }"
                    @click="contentEditorMode = 'preview'"
                  >
                    预览
                  </button>
                </div>
              </div>
              <label
                v-if="contentEditorMode === 'edit'"
                class="markdown-editor-pane markdown-source-pane"
                for="question-content-markdown"
                ><span>Markdown 源码</span
                ><textarea
                  id="question-content-markdown"
                  v-model="form.content"
                  class="question-content-textarea"
                  :class="{ 'question-content-textarea--standalone': form.bankType !== 'leetcode' && !isChoiceForm }"
                  :placeholder="isChoiceForm ? '请输入选择题题干，支持 Markdown' : '请输入笔试题内容，支持 Markdown'"
                />
              </label>
              <section v-else class="markdown-editor-pane markdown-preview-pane" aria-label="题目内容 Markdown 预览">
                <span>渲染预览</span>
                <div class="markdown-preview-content">
                  <PracticeMarkdown
                    :content="form.content"
                    custom-id="question-content-preview"
                    empty-text="输入 Markdown 后可在这里查看题目效果"
                  />
                </div>
              </section>
            </div>
            <div v-if="isChoiceForm" class="choice-option-editor">
              <div class="choice-option-head">
                <span>选项（支持 Markdown）</span
                ><button type="button" class="secondary-btn" @click="addOption">新增选项</button>
              </div>
              <label v-for="(option, index) in form.options" :key="option.key" class="choice-option-row">
                <b>{{ option.key }}</b>
                <input v-model="option.text" :placeholder="`选项 ${option.key}`" />
                <button
                  type="button"
                  class="danger-text"
                  :disabled="form.options.length <= 2"
                  @click="removeOption(index)"
                >
                  删除
                </button>
              </label>
            </div>
            <div v-else-if="form.bankType === 'leetcode'" class="coding-meta-editor">
              <label class="practice-field"
                ><span class="practice-field-label">默认语言</span
                ><select v-model="form.codingLanguage" @change="resetCodingTemplateForLanguage">
                  <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">参数个数</span
                ><input v-model.number="form.codingParameterCount" type="number" min="1" max="10" /><small
                  class="field-hint"
                  >用于按 LeetCode 方式展示和传递测试参数。</small
                ></label
              >
              <label class="practice-field wide"
                ><span class="practice-field-label">初始代码模板</span
                ><textarea
                  v-model="form.codingTemplate"
                  class="question-code-template-textarea"
                  :placeholder="buildDefaultTemplate('solution', form.codingLanguage)"
                />
              </label>
            </div>
          </div>

          <div v-else class="practice-section">
            <label v-if="isChoiceForm" class="practice-field"
              ><span class="practice-field-label">正确答案</span
              ><input v-model="form.answer" :placeholder="form.questionType === '多选' ? '例如：A,C' : '例如：A'"
            /></label>
            <div v-else>
              <div v-if="form.bankType === 'leetcode'" class="coding-meta-editor">
                <label class="practice-field wide"
                  ><span class="practice-field-label">测试用例 JSON</span
                  ><textarea
                    v-model="form.codingTestsText"
                    class="question-tests-textarea"
                    placeholder='[{"name":"示例","args":[[2,7],9],"expected":[0,1],"sample":true}]'
                  /><small class="field-hint"
                    >每条用例需包含 name、args、expected；sample=true 会作为练习中的样例运行。</small
                  ></label
                >
              </div>
              <div class="practice-field markdown-editor-field markdown-answer-editor">
                <div class="markdown-editor-head">
                  <span class="practice-field-label">{{
                    form.bankType === 'leetcode' ? '参考答案 / 判分说明' : '参考答案 / 判分关键词'
                  }}</span>
                  <div class="markdown-editor-tabs" role="tablist" aria-label="参考答案编辑模式">
                    <button
                      type="button"
                      role="tab"
                      :aria-selected="answerEditorMode === 'edit'"
                      :class="{ active: answerEditorMode === 'edit' }"
                      @click="answerEditorMode = 'edit'"
                    >
                      编辑
                    </button>
                    <button
                      type="button"
                      role="tab"
                      :aria-selected="answerEditorMode === 'preview'"
                      :class="{ active: answerEditorMode === 'preview' }"
                      @click="answerEditorMode = 'preview'"
                    >
                      预览
                    </button>
                  </div>
                </div>
                <label
                  v-if="answerEditorMode === 'edit'"
                  class="markdown-editor-pane markdown-source-pane"
                  for="question-answer-markdown"
                  ><span>Markdown 源码</span
                  ><textarea
                    id="question-answer-markdown"
                    v-model="form.answer"
                    class="question-answer-textarea"
                    :class="{ 'question-answer-textarea--standalone': form.bankType !== 'leetcode' }"
                    :placeholder="
                      form.bankType === 'leetcode'
                        ? '支持 Markdown，以测试用例通过情况作为主要评分依据'
                        : '支持 Markdown；练习提交时会用参考答案做简单自动判分'
                    "
                  />
                </label>
                <section v-else class="markdown-editor-pane markdown-preview-pane" aria-label="参考答案 Markdown 预览">
                  <span>渲染预览</span>
                  <div class="markdown-preview-content">
                    <PracticeMarkdown
                      :content="form.answer"
                      custom-id="question-answer-preview"
                      empty-text="输入 Markdown 后可在这里查看答案效果"
                    />
                  </div>
                </section>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="practice-form ai-generate-panel question-wizard-panel">
          <div v-if="currentStep === 0" class="practice-section">
            <span class="practice-field-label">生成设置</span>
            <div class="maintain-field-grid">
              <label class="practice-field wide"
                ><span class="practice-field-label">方向 / 主题</span
                ><input v-model="aiForm.topic" placeholder="例如：Java 后端、Agent 工程"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">题库</span
                ><select v-model="aiForm.bankType" @change="syncAiBankType">
                  <option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">分类</span><input v-model="aiForm.category" placeholder="Java"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">难度</span
                ><select v-model="aiForm.difficulty">
                  <option>简单</option>
                  <option>中等</option>
                  <option>困难</option>
                </select></label
              >
              <label v-if="aiForm.bankType !== 'leetcode'" class="practice-field"
                ><span class="practice-field-label">题型</span
                ><select v-model="aiForm.questionType">
                  <option v-for="item in aiQuestionTypes" :key="item" :value="item">{{ item }}</option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">数量</span
                ><input v-model.number="aiForm.count" type="number" min="1" max="20"
              /></label>
            </div>
          </div>

          <div v-else class="practice-section">
            <span class="practice-field-label">参考资料</span>
            <div class="doc-upload-field">
              <label class="doc-upload-box">
                <input type="file" accept=".txt,.md,.markdown,.json,.csv" @change="handleAiDocumentChange" />
                <b>选择文档</b>
                <small>{{ aiForm.documentName || '支持 TXT / MD / JSON / CSV，上传后自动填入下方资料区' }}</small>
              </label>
              <button v-if="aiForm.documentName" type="button" class="doc-clear-btn" @click="clearAiDocument">
                清除文档
              </button>
            </div>
            <label class="practice-field"
              ><span class="practice-field-label">文档内容 / 补充资料</span
              ><textarea
                v-model="aiForm.documentText"
                class="question-document-textarea"
                placeholder="可上传文档自动填入，也可以粘贴岗位 JD、技术文档、知识点材料"
              />
            </label>
            <label class="practice-field"
              ><span class="practice-field-label">补充要求</span
              ><textarea
                v-model="aiForm.requirements"
                class="question-requirements-textarea"
                placeholder="例如：偏工程实践，包含性能优化、排障、系统设计"
              />
            </label>
          </div>
        </div>
      </div>
      <div class="modal-actions practice-modal-actions question-wizard-actions">
        <div class="question-wizard-action-buttons">
          <button type="button" class="secondary-btn" :disabled="saving" @click="close">取消</button>
          <button v-if="currentStep > 0" type="button" class="secondary-btn" :disabled="saving" @click="previousStep">
            上一步
          </button>
          <button v-if="!isLastStep" type="button" class="primary-btn" :disabled="saving" @click="nextStep">
            下一步
          </button>
          <button v-else type="button" class="primary-btn" :disabled="saving" @click="submitModal">
            {{ saving ? '处理中' : modalSubmitText }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// Create/edit modal for interview questions with manual entry and AI generation tabs.
// Owns the two form states and question CRUD/generate API calls; emits `saved` with the raw
// saved row (or null after AI generation) so the parent can refresh the bank list.
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { createQuestion, generateQuestions, updateQuestion } from '../../api/interview'
import {
  buildDefaultTemplate,
  codingLanguageOptions,
  codingMeta,
  defaultOptions,
  extractFunctionName,
  formatCodingTests,
  isChoiceType,
  normalizeCodingLanguage,
  optionItems,
  questionStem,
  tagLabels,
} from '../../utils/interviewBank'
import PracticeMarkdown from './PracticeMarkdown.vue'
import {
  buildQuestionPayload,
  validateAiForm,
  validateQuestionForm,
  validateQuestionStep,
} from '../../utils/interviewForm'

defineProps({
  bankTypeOptions: { type: Array, default: () => [] },
})

const emit = defineEmits(['saved'])

const visible = ref(false)
const modalMode = ref('manual')
const editingId = ref('')
const saving = ref(false)
const modalError = ref('')
const currentStep = ref(0)
const contentEditorMode = ref('edit')
const answerEditorMode = ref('edit')
const scrollContainer = ref(null)
const tagDraft = ref('')
const tagError = ref('')
const form = reactive({
  title: '',
  bankType: 'qa',
  category: 'Java',
  difficulty: '中等',
  questionType: '单选',
  tags: [],
  tagsText: '',
  content: '',
  answer: '',
  options: defaultOptions(),
  codingLanguage: 'python',
  codingFunctionName: '',
  codingSignature: '',
  codingTemplate: '',
  codingParameterCount: 1,
  codingTestsText: '',
})
const aiForm = reactive({
  topic: 'Java 后端',
  bankType: 'qa',
  category: 'Java',
  difficulty: '中等',
  questionType: '单选',
  count: 5,
  requirements: '',
  documentName: '',
  documentText: '',
})

const isEditing = computed(() => Boolean(editingId.value))
const modalTitle = computed(() => (isEditing.value ? '编辑题目' : '新增题目'))
const modalSubmitText = computed(() =>
  modalMode.value === 'manual' ? (isEditing.value ? '保存修改' : '保存题目') : '生成并入库',
)
const isChoiceForm = computed(() => isChoiceType(form.questionType))
const formTags = computed(() => tagLabels({ tags: form.tags }))
const formQuestionTypes = computed(() => (form.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答']))
const aiQuestionTypes = computed(() => (aiForm.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答']))
const manualSteps = [
  { key: 'basic', label: '基本信息', description: '标题与分类' },
  { key: 'content', label: '题目内容', description: '题干与作答条件' },
  { key: 'answer', label: '答案与判题', description: '答案与评分依据' },
]
const aiSteps = [
  { key: 'settings', label: '生成设置', description: '主题与题目范围' },
  { key: 'materials', label: '参考资料', description: '文档与补充要求' },
]
const wizardSteps = computed(() => (modalMode.value === 'manual' ? manualSteps : aiSteps))
const isLastStep = computed(() => currentStep.value === wizardSteps.value.length - 1)

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
  resetWizard()
  resetForm()
  resetTagEditor()
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
  resetWizard()
  resetTagEditor()
  const meta = codingMeta(item)
  Object.assign(form, {
    title: item.title || '',
    bankType: item.bankType || 'qa',
    category: item.category || 'Java',
    difficulty: item.difficulty || '中等',
    questionType: item.questionType || (item.bankType === 'leetcode' ? '编程题' : '单选'),
    tags: tagLabels(item),
    tagsText: '',
    content: questionStem(item),
    answer: item.answer || '',
    options: optionItems(item).length ? optionItems(item) : defaultOptions(),
    codingLanguage: normalizeCodingLanguage(meta.language || 'python'),
    codingFunctionName: meta.functionName || '',
    codingSignature: meta.signature || '',
    codingTemplate: meta.template || '',
    codingParameterCount: Number(meta.parameterCount || 1),
    codingTestsText: formatCodingTests(meta.tests),
  })
  syncFormBankType()
  if (form.bankType === 'leetcode' && !form.codingTemplate)
    form.codingTemplate = buildDefaultTemplate(form.codingFunctionName || 'solution', form.codingLanguage)
  visible.value = true
}
function close() {
  if (saving.value) return
  visible.value = false
}
function resetForm() {
  Object.assign(form, {
    title: '',
    bankType: 'qa',
    category: 'Java',
    difficulty: '中等',
    questionType: '单选',
    tags: [],
    tagsText: '',
    content: '',
    answer: '',
    options: defaultOptions(),
    codingLanguage: 'python',
    codingFunctionName: '',
    codingSignature: '',
    codingTemplate: '',
    codingParameterCount: 1,
    codingTestsText: '',
  })
}
function resetTagEditor() {
  tagDraft.value = ''
  tagError.value = ''
}
function addFormTag() {
  const normalized = tagDraft.value.trim()
  tagError.value = ''
  if (!normalized) return
  if (/[,，、;；\n\r\t]/.test(normalized)) {
    tagError.value = '请一次添加一个标签。'
    return
  }
  if (!formTags.value.some((tag) => tag.toLowerCase() === normalized.toLowerCase())) {
    form.tags = [...formTags.value, normalized]
  }
  tagDraft.value = ''
}
function removeFormTag(tag) {
  form.tags = formTags.value.filter((item) => item !== tag)
  tagError.value = ''
}
function handleTagKeydown(event) {
  if (event.key === 'Enter') {
    event.preventDefault()
    addFormTag()
  }
}
function resetWizard() {
  currentStep.value = 0
  contentEditorMode.value = 'edit'
  answerEditorMode.value = 'edit'
  scrollToStepTop()
}
function setModalMode(mode) {
  if (saving.value || modalMode.value === mode) return
  modalMode.value = mode
  modalError.value = ''
  resetWizard()
}
function scrollToStepTop() {
  nextTick(() => scrollContainer.value?.scrollTo?.({ top: 0, behavior: 'auto' }))
}
function goToStep(index) {
  if (saving.value || index < 0 || index >= wizardSteps.value.length) return
  modalError.value = ''
  try {
    if (modalMode.value === 'manual' && index > currentStep.value) {
      for (let step = currentStep.value; step < index; step += 1) validateQuestionStep(form, step)
    }
    currentStep.value = index
  } catch (err) {
    modalError.value = err.message || '请检查当前步骤填写内容'
  }
  scrollToStepTop()
}
function previousStep() {
  goToStep(currentStep.value - 1)
}
function nextStep() {
  goToStep(Math.min(currentStep.value + 1, wizardSteps.value.length - 1))
}
function showSubmitError(err, mode) {
  modalError.value = err.message || (mode === 'manual' ? '保存失败' : 'AI 生成失败')
  scrollToStepTop()
}
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
  } catch (err) {
    showSubmitError(err, 'manual')
  } finally {
    saving.value = false
  }
}
async function submitAiGenerate() {
  saving.value = true
  try {
    validateAiForm(aiForm)
    await generateQuestions(aiForm)
    visible.value = false
    emit('saved', null)
  } catch (err) {
    showSubmitError(err, 'ai')
  } finally {
    saving.value = false
  }
}
function addOption() {
  const key = String.fromCharCode(65 + form.options.length)
  form.options.push({ key, text: '' })
}
function removeOption(index) {
  if (form.options.length <= 2) return
  form.options.splice(index, 1)
  form.options.forEach((item, idx) => {
    item.key = String.fromCharCode(65 + idx)
  })
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
  const functionName =
    extractFunctionName(form.codingTemplate, form.codingLanguage) || form.codingFunctionName || 'solution'
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
  reader.onerror = () => {
    modalError.value = '文档读取失败，请换成 txt、md 或可读取的文本文件'
  }
  reader.readAsText(file, 'utf-8')
}
function clearAiDocument() {
  aiForm.documentName = ''
  aiForm.documentText = ''
}

defineExpose({ openCreate, openEdit })
</script>
