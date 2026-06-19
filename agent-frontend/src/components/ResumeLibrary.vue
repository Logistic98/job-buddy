<template>
  <section class="system-page resume-page resume-analysis-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Resume Analysis</p>
        <h1>简历分析</h1>
        <p>仅支持 PDF 简历。左侧查看原始文件，右侧基于大模型分析优势、劣势、问题、面试深挖点、排版和错别字。</p>
      </div>
    </header>

    <div class="resume-analysis-workbench">
      <div class="resume-analysis-toolbar glass-card unified-analysis-toolbar clean-analysis-toolbar">
        <button type="button" class="analysis-select-btn" @click="openResumePicker">
          <span>简历选择</span>
          <strong>{{ currentAnalysisName }}</strong>
        </button>
        <button class="primary-btn analysis-start-btn" :disabled="!currentAnalysisResume?.resumeId || resume.analyzing" @click="runAnalysis">{{ resume.analyzing ? '分析中' : '开始分析' }}</button>
        <p v-if="resume.error" class="error analysis-error">{{ resume.error }}</p>
      </div>

      <div class="resume-analysis-split">
        <section class="resume-original-pane direct-preview-pane">
          <div v-if="!currentAnalysisResume" class="analysis-empty-panel">
            <div class="analysis-empty-card">
              <p class="eyebrow">Resume Source</p>
              <h2>从简历管理选择或上传简历</h2>
              <p>简历分析与简历管理共用同一份简历库。上传或选择后，左侧显示原始文件，右侧立即进入分析报告视图。</p>
              <label class="primary-btn upload-resume-btn inline-upload">
                <input type="file" accept=".pdf,application/pdf" @change="pick" />
                {{ resume.uploading ? '上传中' : '上传 PDF 简历' }}
              </label>
              <button class="secondary-btn" @click="emit('manage-resumes')">进入简历管理</button>
            </div>
          </div>
          <div v-else-if="canPreview(currentAnalysisResume)" class="resume-pdf-preview-shell">
            <img class="resume-pdf-thumb" :src="thumbnailUrl(currentAnalysisResume)" :alt="currentAnalysisResume?.originalName || 'PDF 缩略图'" loading="eager" decoding="async" />
            <iframe v-if="showPdfFrame" :key="currentAnalysisResume?.resumeId" class="resume-doc-frame" :class="{ ready: !pdfLoading }" :src="previewUrl(currentAnalysisResume)" title="PDF 简历预览" loading="eager" @load="pdfLoading = false"></iframe>
          </div>
          <div v-else class="word-preview-box"><strong>暂不支持预览</strong><p>当前文件格式暂不支持在线预览，请下载后查看。</p><a class="primary-link" :href="downloadUrl(currentAnalysisResume)" :download="currentAnalysisResume?.originalName">下载文件</a></div>
        </section>

        <section class="resume-analysis-pane glass-card">
          <template v-if="currentAnalysisResume">
            <div class="detail-top"><div><p class="eyebrow">LLM Analysis Report</p><h2>{{ parsed.name || currentAnalysisResume?.originalName }}</h2></div><span class="state-badge">{{ currentAnalysisResume?.parseStatus }}</span></div>
            <div class="resume-analysis-summary"><div><span>综合评分</span><strong>{{ analysis.overall_score ?? '-' }}</strong></div><div><span>优势点</span><strong>{{ listOf(analysis.advantages).length }}</strong></div><div><span>深挖点</span><strong>{{ listOf(analysis.interview_deep_dive_points).length }}</strong></div></div>
            <section class="analysis-card primary-analysis"><h3>总体判断</h3><p>{{ analysis.summary || '暂无大模型分析结果，请点击“开始分析”，或检查 Runtime 模型服务。' }}</p></section>
            <AnalysisList title="优势" :items="analysis.advantages" />
            <AnalysisList title="劣势 / 风险" :items="analysis.disadvantages" />
            <AnalysisList title="简历问题" :items="analysis.problems" />
            <AnalysisList title="面试可能深挖点" :items="analysis.interview_deep_dive_points" />
            <AnalysisList title="排版问题" :items="analysis.layout_issues" empty-text="暂未发现明显排版问题" />
            <AnalysisList title="错别字 / 术语 / 标点问题" :items="analysis.typo_issues" empty-text="暂未发现明显错别字问题" />
            <AnalysisList title="行动建议" :items="analysis.action_items" />
          </template>
          <div v-else class="analysis-report-empty">
            <p class="eyebrow">LLM Analysis Report</p>
            <h2>等待简历接入</h2>
            <p>选择或上传简历后，这里会展示综合评分、优势、风险、简历问题、面试深挖点和行动建议。</p>
            <div class="resume-analysis-summary"><div><span>综合评分</span><strong>-</strong></div><div><span>优势点</span><strong>0</strong></div><div><span>深挖点</span><strong>0</strong></div></div>
          </div>
        </section>
      </div>
    </div>

    <div v-if="showResumePicker" class="modal-mask">
      <div class="modal-card resume-modal-card resume-picker-modal">
        <button class="close" @click="showResumePicker = false">×</button>
        <div class="resume-picker-head">
          <div>
            <p class="eyebrow">Resume Analysis Context</p>
            <h2>选择分析简历</h2>
            <p>这里和工作台使用同一个当前简历。选择后，工作台推荐、简历分析和问答都会使用这份简历上下文。</p>
          </div>
          <div class="resume-picker-head-actions">
            <label class="primary-btn upload-resume-btn">
              <input type="file" accept=".pdf,application/pdf" @change="uploadFromPicker" />
              {{ resume.uploading ? '上传中' : '上传简历' }}
            </label>
          </div>
        </div>
        <label class="resume-picker-search">
          <span>检索简历</span>
          <input v-model.trim="resumeKeyword" placeholder="按文件名、姓名、摘要、技能搜索" />
        </label>
        <div v-if="resume.loading" class="empty-state compact"><strong>正在加载简历</strong><p>请稍候。</p></div>
        <div v-else-if="!analysisResumes.length" class="empty-state compact"><strong>暂无可分析简历</strong><p>请上传 PDF 简历。</p></div>
        <div v-else-if="!filteredAnalysisResumes.length" class="empty-state compact"><strong>没有匹配的简历</strong><p>请更换关键词再搜索。</p></div>
        <div v-else class="resume-picker-list">
          <article v-for="item in filteredAnalysisResumes" :key="item.resumeId" :class="['resume-picker-item', { active: resume.current?.resumeId === item.resumeId }]">
            <div class="resume-picker-thumb">
              <img :src="thumbnailUrl(item)" :alt="item.originalName || '简历缩略图'" loading="lazy" decoding="async" />
            </div>
            <div class="resume-picker-main">
              <strong>{{ resumeTitle(item) }}</strong>
              <span>{{ item.originalName || item.resumeId }}</span>
              <p>{{ resumeSummary(item) }}</p>
              <div class="skill-list compact"><span v-for="s in skills(item).slice(0, 8)" :key="s">{{ s }}</span></div>
            </div>
            <div class="resume-picker-actions">
              <span v-if="resume.current?.resumeId === item.resumeId" class="state-badge ok">当前使用</span>
              <button class="primary-btn" @click="selectResumeForAnalysis(item)">{{ resume.current?.resumeId === item.resumeId ? '继续使用' : '选择这份' }}</button>
            </div>
          </article>
        </div>
      </div>
    </div>

    <div v-if="previewItem" class="modal-mask"><div class="pdf-preview-modal"><button class="close" @click="closePreview">×</button><div class="pdf-preview-head"><div><p class="eyebrow">PDF Preview</p><h2>{{ previewItem.originalName }}</h2></div><a class="primary-link" :href="downloadUrl(previewItem)" :download="previewItem.originalName">下载 PDF</a></div><iframe :src="previewUrl(previewItem)" title="PDF 预览"></iframe></div></div>
  </section>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { resumeDownloadUrl, resumePreviewUrl, resumeThumbnailUrl } from '../api/resume'
import { useChatStore } from '../stores/chat'
import { useResumeStore } from '../stores/resume'

const AnalysisList = defineComponent({
  props: { title: { type: String, default: '' }, items: { type: [Array, Object, String], default: null }, emptyText: { type: String, default: '暂无内容' } },
  setup(props) {
    const rows = () => Array.isArray(props.items) ? props.items : (props.items ? [props.items] : [])
    const text = value => typeof value === 'string' ? value : Object.entries(value || {}).map(([k, v]) => `${label(k)}：${Array.isArray(v) ? v.join('、') : v}`).join('\n')
    return () => h('section', { class: 'analysis-card' }, [h('h3', props.title), rows().length ? h('div', { class: 'analysis-list' }, rows().map(item => h('p', text(item)))) : h('p', props.emptyText)])
  },
})
function label(key) { return ({ title: '标题', detail: '说明', evidence: '依据', suggestion: '建议', type: '类型', topic: '主题', question: '问题', reason: '原因', preparation: '准备', text: '原文', context: '上下文' }[key] || key) }

const emit = defineEmits(['manage-resumes'])
const chat = useChatStore()
const resume = useResumeStore()
const previewItem = ref(null)
const pdfLoading = ref(false)
const showPdfFrame = ref(false)
const showResumePicker = ref(false)
const resumeKeyword = ref('')
onMounted(() => { resume.load().catch(() => {}) })
const analysisResumes = computed(() => resume.items.filter(item => String(item.suffix || '').toLowerCase() === 'pdf'))
const currentAnalysisResume = computed(() => {
  if (isAnalysisResume(resume.current)) return resume.current
  return analysisResumes.value[0] || null
})
const currentAnalysisName = computed(() => currentAnalysisResume.value ? resumeTitle(currentAnalysisResume.value) : '未选择简历')
const filteredAnalysisResumes = computed(() => {
  const q = resumeKeyword.value.trim().toLowerCase()
  if (!q) return analysisResumes.value
  return analysisResumes.value.filter(item => resumeSearchText(item).includes(q))
})
const parsed = computed(() => currentAnalysisResume.value?.parsed || {})
const analysis = computed(() => parsed.value.analysis || {})

function pick(e) { const file = e.target.files?.[0]; if (file) resume.upload(file, chat.sessionId); e.target.value = '' }
function openResumePicker() { resumeKeyword.value = ''; showResumePicker.value = true; resume.load().catch(() => {}) }
async function uploadFromPicker(e) { const file = e.target.files?.[0]; e.target.value = ''; if (file) await resume.upload(file, chat.sessionId).catch(() => {}) }
function selectResumeForAnalysis(item) { resume.select(item); showResumePicker.value = false }
function isAnalysisResume(item) { return String(item?.suffix || '').toLowerCase() === 'pdf' }
function resumeTitle(item) { return item?.parsed?.name || item?.parsed?.basic_info?.name || item?.parsed?.basicInfo?.name || item?.originalName || '未命名简历' }
function resumeSummary(item) { return item?.parsed?.summary || item?.parsed?.personal_advantage || item?.parsed?.personalAdvantage || '暂无摘要' }
function skills(item) { const raw = item?.parsed?.skills || item?.parsed?.skill_tags || item?.parsed?.skillTags || []; return Array.isArray(raw) ? raw : String(raw).split(/[,，、\s]+/).filter(Boolean) }
function resumeSearchText(item) { return [resumeTitle(item), item?.originalName, item?.suffix, resumeSummary(item), ...skills(item), item?.parsed?.expected_titles, item?.parsed?.job_intentions].filter(Boolean).join(' ').toLowerCase() }
function canPreview(item) { return (item?.suffix || '').toLowerCase() === 'pdf' }
function previewUrl(item) { return item?.resumeId ? resumePreviewUrl(item.resumeId) : '#' }
function thumbnailUrl(item) { return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : '#' }
function downloadUrl(item) { return item?.resumeId ? resumeDownloadUrl(item.resumeId) : '#' }
function closePreview() { previewItem.value = null }
async function runAnalysis() { if (currentAnalysisResume.value?.resumeId) await resume.analyze(currentAnalysisResume.value.resumeId, chat.sessionId).catch(() => {}) }
function listOf(value) { return Array.isArray(value) ? value : (value ? [value] : []) }
watch(() => currentAnalysisResume.value?.resumeId, async () => {
  const canShow = canPreview(currentAnalysisResume.value)
  pdfLoading.value = canShow
  showPdfFrame.value = false
  if (!canShow) return
  await nextTick()
  window.requestAnimationFrame(() => { showPdfFrame.value = true })
}, { immediate: true })
</script>
