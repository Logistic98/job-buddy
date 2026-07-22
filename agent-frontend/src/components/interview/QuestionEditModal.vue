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
      <button type="button" class="close" :disabled="busy" aria-label="关闭题目维护弹窗" @click="close">×</button>
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
          :disabled="busy"
          :class="{ active: modalMode === 'manual' }"
          @click="setModalMode('manual')"
        >
          手动录入
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="modalMode === 'ai'"
          :disabled="busy"
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
          :disabled="busy"
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
                ><input
                  v-model="form.title"
                  maxlength="200"
                  placeholder="例如：Agent 工具调用与失败恢复（最多 200 字）"
                /><small class="field-hint">标题用于列表检索和练习展示，建议保持短句且明确知识点。</small></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">题库</span
                ><select v-model="form.bankType" @change="syncFormBankType">
                  <option value="" disabled>请选择题库</option>
                  <option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">分类</span
                ><input v-model="form.category" maxlength="64" placeholder="例如：Agent 工程（最多 64 字）"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">难度</span
                ><select v-model="form.difficulty">
                  <option value="" disabled>请选择难度</option>
                  <option>简单</option>
                  <option>中等</option>
                  <option>困难</option>
                </select></label
              >
              <label v-if="form.bankType !== 'leetcode'" class="practice-field"
                ><span class="practice-field-label">题型</span
                ><select v-model="form.questionType">
                  <option value="" disabled>请选择题型</option>
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
                    placeholder="输入一个标签后按回车，例如 Agent"
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
                ><select v-model="form.codingLanguage">
                  <option value="" disabled>请选择语言</option>
                  <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">参数个数</span
                ><input
                  v-model.number="form.codingParameterCount"
                  type="number"
                  min="1"
                  max="10"
                  step="1"
                  placeholder="请输入 1-10 的整数"
                /><small class="field-hint">用于按 LeetCode 方式展示和传递测试参数。</small></label
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
                ><input
                  v-model="aiForm.topic"
                  maxlength="200"
                  placeholder="例如：Agent 工程、RAG 与模型评测（最多 200 字）"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">题库</span
                ><select v-model="aiForm.bankType" @change="syncAiBankType">
                  <option value="" disabled>请选择题库</option>
                  <option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">分类</span
                ><input v-model="aiForm.category" maxlength="64" placeholder="例如：Agent 工程（最多 64 字）"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label">难度</span
                ><select v-model="aiForm.difficulty">
                  <option value="" disabled>请选择难度</option>
                  <option>简单</option>
                  <option>中等</option>
                  <option>困难</option>
                </select></label
              >
              <label v-if="aiForm.bankType !== 'leetcode'" class="practice-field"
                ><span class="practice-field-label">题型</span
                ><select v-model="aiForm.questionType">
                  <option value="" disabled>请选择题型</option>
                  <option v-for="item in aiQuestionTypes" :key="item" :value="item">{{ item }}</option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label">数量</span
                ><input
                  v-model.number="aiForm.count"
                  type="number"
                  min="1"
                  max="20"
                  step="1"
                  placeholder="请输入 1-20 的整数"
              /></label>
            </div>
          </div>

          <div v-else class="practice-section">
            <span class="practice-field-label">参考资料</span>
            <div class="doc-upload-field">
              <label class="doc-upload-box">
                <input
                  type="file"
                  accept=".pdf,.doc,.docx,.txt,.md,.markdown,.json,.csv,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                  :disabled="busy"
                  @change="handleAiDocumentChange"
                />
                <b>{{ documentReading ? '正在读取' : '选择文档' }}</b>
                <small>{{
                  documentReading
                    ? '正在提取文档文本，请稍候'
                    : aiForm.documentName || '支持 PDF / DOC / DOCX / TXT / MD / JSON / CSV，上传后自动填入下方资料区'
                }}</small>
              </label>
              <button
                v-if="aiForm.documentName"
                type="button"
                class="doc-clear-btn"
                :disabled="busy"
                @click="clearAiDocument"
              >
                清除文档
              </button>
            </div>
            <small v-if="documentNotice" class="field-hint">{{ documentNotice }}</small>
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
                maxlength="2000"
                placeholder="例如：偏工程实践，包含性能优化、排障、系统设计（最多 2000 字）"
              />
            </label>
          </div>
        </div>
      </div>
      <div class="modal-actions practice-modal-actions question-wizard-actions">
        <div class="question-wizard-action-buttons">
          <button type="button" class="secondary-btn" :disabled="busy" @click="close">取消</button>
          <button v-if="currentStep > 0" type="button" class="secondary-btn" :disabled="busy" @click="previousStep">
            上一步
          </button>
          <button v-if="!isLastStep" type="button" class="primary-btn" :disabled="busy" @click="nextStep">
            下一步
          </button>
          <button v-else type="button" class="primary-btn" :disabled="busy" @click="submitModal">
            {{ documentReading ? '读取文档中' : saving ? '处理中' : modalSubmitText }}
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
import { createQuestion, extractInterviewDocument, generateQuestions, updateQuestion } from '../../api/interview'
import { validateFile } from '../../utils/formValidation'
import {
  buildDefaultTemplate,
  codingLanguageOptions,
  codingMeta,
  defaultOptions,
  formatCodingTests,
  isChoiceType,
  normalizeCodingLanguage,
  optionItems,
  questionStem,
  tagLabels,
} from '../../utils/interviewBank'
import PracticeMarkdown from './PracticeMarkdown.vue'
import { buildQuestionPayload, validateAiForm, validateQuestionStep } from '../../utils/interviewForm'

defineProps({
  bankTypeOptions: { type: Array, default: () => [] },
})

const emit = defineEmits(['saved'])

const visible = ref(false)
const modalMode = ref('manual')
const editingId = ref('')
const saving = ref(false)
const documentReading = ref(false)
const documentNotice = ref('')
const modalError = ref('')
const currentStep = ref(0)
const contentEditorMode = ref('edit')
const answerEditorMode = ref('edit')
const scrollContainer = ref(null)
const tagDraft = ref('')
const tagError = ref('')
const emptyQuestionForm = () => ({
  title: '',
  bankType: '',
  category: '',
  difficulty: '',
  questionType: '',
  tags: [],
  tagsText: '',
  content: '',
  answer: '',
  options: defaultOptions(),
  codingLanguage: '',
  codingFunctionName: '',
  codingSignature: '',
  codingTemplate: '',
  codingParameterCount: '',
  codingTestsText: '',
})
const emptyAiForm = () => ({
  topic: '',
  bankType: '',
  category: '',
  difficulty: '',
  questionType: '',
  count: '',
  requirements: '',
  documentName: '',
  documentText: '',
})
const form = reactive(emptyQuestionForm())
const aiForm = reactive(emptyAiForm())

const busy = computed(() => saving.value || documentReading.value)
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
  if (visible.value && !busy.value) close()
}

function openCreate() {
  editingId.value = ''
  modalMode.value = 'manual'
  modalError.value = ''
  resetWizard()
  resetForm()
  Object.assign(aiForm, emptyAiForm())
  resetTagEditor()
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
    bankType: item.bankType || '',
    category: item.category || '',
    difficulty: item.difficulty || '',
    questionType: item.questionType || '',
    tags: tagLabels(item),
    tagsText: '',
    content: questionStem(item),
    answer: item.answer || '',
    options: optionItems(item).length ? optionItems(item) : defaultOptions(),
    codingLanguage: meta.language ? normalizeCodingLanguage(meta.language) : '',
    codingFunctionName: meta.functionName || '',
    codingSignature: meta.signature || '',
    codingTemplate: meta.template || '',
    codingParameterCount: meta.parameterCount == null ? '' : Number(meta.parameterCount),
    codingTestsText: formatCodingTests(meta.tests),
  })
  visible.value = true
}
function close() {
  if (busy.value) return
  visible.value = false
}
function resetForm() {
  Object.assign(form, emptyQuestionForm())
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
  if (busy.value || modalMode.value === mode) return
  modalMode.value = mode
  modalError.value = ''
  resetWizard()
}
function scrollToStepTop() {
  nextTick(() => scrollContainer.value?.scrollTo?.({ top: 0, behavior: 'auto' }))
}
function goToStep(index) {
  if (busy.value || index < 0 || index >= wizardSteps.value.length) return
  modalError.value = ''
  currentStep.value = index
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
function validateManualFormAndFocusError() {
  for (let step = 0; step < manualSteps.length; step += 1) {
    try {
      validateQuestionStep(form, step)
    } catch (err) {
      currentStep.value = step
      throw err
    }
  }
}
async function saveQuestion() {
  saving.value = true
  try {
    validateManualFormAndFocusError()
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
  if (form.bankType === 'leetcode') form.questionType = '编程题'
  else if (form.questionType === '编程题') form.questionType = ''
}
function syncAiBankType() {
  if (aiForm.bankType === 'leetcode') aiForm.questionType = '编程题'
  else if (aiForm.questionType === '编程题') aiForm.questionType = ''
}
async function handleAiDocumentChange(event) {
  modalError.value = ''
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || busy.value) return
  documentReading.value = true
  try {
    validateFile(file, '参考资料', {
      extensions: ['txt', 'md', 'markdown', 'json', 'csv', 'pdf', 'doc', 'docx'],
      maxBytes: 20 * 1024 * 1024,
    })
    const result = await extractInterviewDocument(file)
    aiForm.documentName = result?.fileName || file.name
    aiForm.documentText = String(result?.text || '')
    documentNotice.value = result?.truncated ? `原文共 ${result.characterCount} 个字符，已提取前 20000 个字符。` : ''
  } catch (err) {
    modalError.value = err.message || '参考资料读取失败，请确认文档格式和内容完整'
    scrollToStepTop()
  } finally {
    documentReading.value = false
  }
}
function clearAiDocument() {
  if (busy.value) return
  aiForm.documentName = ''
  aiForm.documentText = ''
  documentNotice.value = ''
}

defineExpose({ openCreate, openEdit })
</script>
