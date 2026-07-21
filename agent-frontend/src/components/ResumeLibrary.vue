<template>
  <section class="system-page resume-page resume-analysis-page">
    <header class="page-header analysis-compact-header">
      <div class="analysis-header-copy">
        <div class="analysis-header-title">
          <p class="eyebrow">Resume Analysis</p>
          <h1>简历分析</h1>
        </div>
        <p class="analysis-header-description">
          左侧查看原始文件，右侧分析优势、风险、内容质量、经历价值与面试深挖点。
        </p>
      </div>
      <div class="analysis-header-actions">
        <button type="button" class="analysis-select-btn" @click="openResumePicker">
          <span>简历选择</span>
          <strong>{{ currentAnalysisName }}</strong>
        </button>
        <button
          class="primary-btn analysis-start-btn"
          :disabled="!currentAnalysisResume?.resumeId || currentAnalysisPending"
          @click="runAnalysis"
        >
          {{ currentAnalysisPending ? '分析中' : '开始分析' }}
        </button>
        <p v-if="resume.error" class="error analysis-error">{{ resume.error }}</p>
      </div>
    </header>

    <div class="resume-analysis-workbench">
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
            <iframe
              v-if="showPdfFrame"
              :key="currentAnalysisResume?.resumeId"
              class="resume-doc-frame"
              :class="{ ready: !pdfLoading }"
              :src="embeddedPreviewUrl(currentAnalysisResume)"
              title="PDF 简历预览"
              loading="eager"
              @load="pdfLoading = false"
            ></iframe>
          </div>
          <div v-else class="word-preview-box">
            <strong>暂不支持预览</strong>
            <p>当前文件格式暂不支持在线预览，请下载后查看。</p>
            <a
              class="primary-link"
              :href="downloadUrl(currentAnalysisResume)"
              :download="currentAnalysisResume?.originalName"
              >下载文件</a
            >
          </div>
        </section>

        <section class="resume-analysis-pane glass-card">
          <template v-if="currentAnalysisResume">
            <div v-if="currentAnalysisPending" class="resume-analysis-task-status" role="status">
              <span class="favorite-analysis-loading-mark" aria-hidden="true"></span>
              <strong>后台分析中</strong>
              <span>{{ currentAnalysisStage || '正在准备分析上下文' }}</span>
              <small>可离开页面</small>
            </div>
            <p class="eyebrow analysis-report-kicker">LLM Analysis Report</p>
            <ResumeAnalysisSummary :analysis="analysis" />
            <section class="analysis-card primary-analysis">
              <h3>总体判断</h3>
              <p>{{ analysis.summary || '暂无大模型分析结果，请点击“开始分析”，或检查 Runtime 模型服务。' }}</p>
            </section>
            <section v-if="scoreBreakdownRows.length" class="analysis-card score-breakdown-card">
              <header>
                <div>
                  <h3>评分依据</h3>
                  <span>六个维度按固定权重汇总</span>
                </div>
                <button
                  type="button"
                  class="score-breakdown-toggle"
                  :aria-expanded="scoreBreakdownExpanded"
                  @click="scoreBreakdownExpanded = !scoreBreakdownExpanded"
                >
                  {{ scoreBreakdownExpanded ? '收起详情' : '展开详情' }}
                </button>
              </header>
              <div v-if="scoreBreakdownExpanded" class="score-breakdown-grid">
                <article
                  v-for="row in scoreBreakdownRows"
                  :key="row.key"
                  :class="['score-breakdown-item', `is-${row.tone}`]"
                  :style="{ '--score-progress': `${row.score}%` }"
                >
                  <div class="score-dimension-head">
                    <div>
                      <strong>{{ row.label }}</strong
                      ><span>权重 {{ row.weight }}%</span>
                    </div>
                    <div class="score-dimension-value">
                      <b>{{ row.score }}</b
                      ><small>{{ row.level }}</small>
                    </div>
                  </div>
                  <div class="score-dimension-track" aria-hidden="true"><span></span></div>
                  <p>
                    <strong>评分证据</strong><span>{{ row.evidence }}</span>
                  </p>
                </article>
              </div>
            </section>
            <AnalysisList title="优势" :items="analysis.advantages" />
            <AnalysisList title="劣势" :items="analysis.disadvantages" />
            <AnalysisList title="简历问题" :items="analysis.problems" />
            <AnalysisList title="内容完整性与说服力" :items="analysis.content_quality" empty-text="暂无内容质量分析" />
            <AnalysisList title="经历含金量" :items="analysis.experience_value" empty-text="暂无经历价值分析" />
            <AnalysisList title="面试可能深挖点" :items="analysis.interview_deep_dive_points" />
            <AnalysisList title="行动建议" :items="analysis.action_items" />
          </template>
          <div v-else class="analysis-report-empty">
            <p class="eyebrow">LLM Analysis Report</p>
            <h2>等待简历接入</h2>
            <p>选择或上传简历后，这里会展示综合评分、优势、风险、内容质量、经历价值、面试深挖点和行动建议。</p>
            <ResumeAnalysisSummary />
          </div>
        </section>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showResumePicker" class="modal-mask analysis-resume-picker-mask" @click.self="closeResumePicker">
        <div
          class="modal-card resume-modal-card resume-picker-modal analysis-resume-picker-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="analysis-resume-picker-title"
        >
          <header class="analysis-resume-picker-header">
            <button
              type="button"
              class="close analysis-resume-picker-close"
              aria-label="关闭简历选择弹框"
              @click="closeResumePicker"
            >
              ×
            </button>
            <div class="resume-picker-head">
              <div>
                <p class="eyebrow">Resume Analysis Context</p>
                <h2 id="analysis-resume-picker-title">选择分析简历</h2>
                <p>选择后，工作台推荐、简历分析和问答都会使用这份简历上下文。</p>
              </div>
              <div class="resume-picker-head-actions">
                <label class="primary-btn upload-resume-btn">
                  <input type="file" accept=".pdf,application/pdf" @change="uploadFromPicker" />
                  {{ resume.uploading ? '上传中' : '上传简历' }}
                </label>
              </div>
            </div>
            <label class="resume-picker-search analysis-resume-picker-search">
              <span>检索简历</span>
              <input v-model.trim="resumeKeyword" placeholder="按文件名、摘要、技能搜索" />
            </label>
          </header>

          <div class="analysis-resume-picker-scroll">
            <div v-if="resume.loading" class="empty-state compact">
              <strong>正在加载简历</strong>
              <p>请稍候。</p>
            </div>
            <div v-else-if="!analysisResumes.length" class="empty-state compact">
              <strong>暂无可分析简历</strong>
              <p>请上传 PDF 简历。</p>
            </div>
            <div v-else-if="!filteredAnalysisResumes.length" class="empty-state compact">
              <strong>没有匹配的简历</strong>
              <p>请更换关键词再搜索。</p>
            </div>
            <div v-else class="resume-picker-list analysis-resume-picker-list">
              <article
                v-for="item in filteredAnalysisResumes"
                :key="item.resumeId"
                :class="[
                  'resume-picker-item',
                  'analysis-resume-picker-item',
                  { active: resume.current?.resumeId === item.resumeId },
                ]"
              >
                <div class="resume-picker-thumb">
                  <img :src="thumbnailUrl(item)" :alt="`${resumeTitle(item)}缩略图`" loading="lazy" decoding="async" />
                </div>
                <div class="resume-picker-main analysis-resume-picker-main">
                  <strong :title="resumeTitle(item)">{{ resumeTitle(item) }}</strong>
                  <p :class="{ loading: isPickerDetailLoading(item) }">{{ resumeSummary(item) }}</p>
                  <div v-if="skills(item).length" class="skill-list compact">
                    <span v-for="s in skills(item).slice(0, 8)" :key="s">{{ s }}</span>
                  </div>
                </div>
                <div class="resume-picker-actions analysis-resume-picker-actions">
                  <span v-if="resume.current?.resumeId === item.resumeId" class="state-badge ok">当前使用</span>
                  <button class="primary-btn" @click="selectResumeForAnalysis(item)">
                    {{ resume.current?.resumeId === item.resumeId ? '继续使用' : '选择这份' }}
                  </button>
                </div>
              </article>
            </div>
          </div>
        </div>
      </div>

      <div v-if="previewItem" class="modal-mask">
        <div class="pdf-preview-modal">
          <button class="close" @click="closePreview">×</button>
          <div class="pdf-preview-head">
            <div>
              <p class="eyebrow">PDF Preview</p>
              <h2>{{ previewItem.originalName }}</h2>
            </div>
            <a class="primary-link" :href="downloadUrl(previewItem)" :download="previewItem.originalName">下载 PDF</a>
          </div>
          <iframe :src="previewUrl(previewItem)" title="PDF 预览"></iframe>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { getResume, resumeDownloadUrl, resumePreviewUrl, resumeThumbnailUrl } from '../api/resume'
import ResumeAnalysisSummary from './ResumeAnalysisSummary.vue'
import { useChatStore } from '../stores/chat'
import { useResumeStore } from '../stores/resume'
import {
  mergeResumePickerDetail,
  resumePickerSearchText,
  resumePickerSkills,
  resumePickerSummary,
  resumePickerTitle,
} from '../utils/resumePicker'
import { pdfViewerFitWidthUrl } from '../utils/resumePdf'

const AnalysisList = defineComponent({
  props: {
    title: { type: String, default: '' },
    items: { type: [Array, Object, String], default: null },
    emptyText: { type: String, default: '暂无内容' },
  },
  setup(props) {
    const rows = () => (Array.isArray(props.items) ? props.items : props.items ? [props.items] : [])
    const text = (value) =>
      typeof value === 'string'
        ? value
        : Object.entries(value || {})
            .map(([k, v]) => `${label(k)}：${Array.isArray(v) ? v.join('、') : v}`)
            .join('\n')
    return () =>
      h('section', { class: 'analysis-card' }, [
        h('h3', props.title),
        rows().length
          ? h(
              'div',
              { class: 'analysis-list analysis-report-list' },
              rows().map((item) => h('p', text(item))),
            )
          : h('p', props.emptyText),
      ])
  },
})
function label(key) {
  return (
    {
      title: '标题',
      detail: '说明',
      evidence: '依据',
      suggestion: '建议',
      type: '类型',
      topic: '主题',
      question: '问题',
      reason: '原因',
      preparation: '准备',
      text: '原文',
      context: '上下文',
    }[key] || key
  )
}

const emit = defineEmits(['manage-resumes'])
const chat = useChatStore()
const resume = useResumeStore()
const previewItem = ref(null)
const pdfLoading = ref(false)
const showPdfFrame = ref(false)
const showResumePicker = ref(false)
const resumeKeyword = ref('')
const pickerDetails = ref({})
const pickerDetailLoadingIds = ref(new Set())
const scoreBreakdownExpanded = ref(false)
let pickerLoadVersion = 0
onMounted(() => {
  resume.load().catch(() => {})
})
const analysisResumes = computed(() =>
  resume.items
    .filter((item) => isAnalysisResume(item))
    .map((item) => mergeResumePickerDetail(item, pickerDetails.value)),
)
const currentAnalysisResume = computed(() => {
  if (isAnalysisResume(resume.current)) return resume.current
  return analysisResumes.value[0] || null
})
const currentAnalysisName = computed(() =>
  currentAnalysisResume.value ? resumeTitle(currentAnalysisResume.value) : '未选择简历',
)
const filteredAnalysisResumes = computed(() => {
  const q = resumeKeyword.value.trim().toLowerCase()
  if (!q) return analysisResumes.value
  return analysisResumes.value.filter((item) => resumeSearchText(item).includes(q))
})
const parsed = computed(() => currentAnalysisResume.value?.parsed || {})
const analysis = computed(() => parsed.value.analysis || {})
const scoreBreakdownRows = computed(() => {
  const breakdown = analysis.value.score_breakdown
  if (!breakdown || typeof breakdown !== 'object' || Array.isArray(breakdown)) return []
  return Object.entries(breakdown).flatMap(([key, item]) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return []
    const score = Number(item.score)
    const weight = Number(item.weight)
    if (!Number.isFinite(score) || !Number.isFinite(weight)) return []
    const normalizedScore = Math.round(score)
    const presentation = scorePresentation(normalizedScore)
    return [
      {
        key,
        label: item.label || key,
        score: normalizedScore,
        weight: Math.round(weight),
        evidence: item.evidence || '暂无评分依据',
        ...presentation,
      },
    ]
  })
})
const currentAnalysisPending = computed(() => resume.isAnalyzing(currentAnalysisResume.value?.resumeId))
const currentAnalysisStage = computed(() => resume.analysisStage(currentAnalysisResume.value?.resumeId))

function scorePresentation(score) {
  if (score >= 90) return { level: '卓越', tone: 'excellent' }
  if (score >= 85) return { level: '优秀', tone: 'strong' }
  if (score >= 75) return { level: '良好', tone: 'good' }
  if (score >= 65) return { level: '合格', tone: 'basic' }
  return { level: '待提升', tone: 'weak' }
}
function pick(e) {
  const file = e.target.files?.[0]
  if (file) resume.upload(file, chat.sessionId)
  e.target.value = ''
}
async function openResumePicker() {
  resumeKeyword.value = ''
  showResumePicker.value = true
  await resume.load().catch(() => {})
  hydratePickerResumes(analysisResumes.value)
}
function closeResumePicker() {
  showResumePicker.value = false
  pickerLoadVersion += 1
  pickerDetailLoadingIds.value = new Set()
}
async function uploadFromPicker(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  await resume.upload(file, chat.sessionId).catch(() => {})
  hydratePickerResumes(analysisResumes.value)
}
function selectResumeForAnalysis(item) {
  resume.select(item)
  closeResumePicker()
}
function isAnalysisResume(item) {
  return String(item?.suffix || '').toLowerCase() === 'pdf'
}
function resumeTitle(item) {
  return resumePickerTitle(item)
}
function resumeSummary(item) {
  return resumePickerSummary(item, isPickerDetailLoading(item))
}
function skills(item) {
  return resumePickerSkills(item)
}
function resumeSearchText(item) {
  return resumePickerSearchText(item)
}
function isPickerDetailLoading(item) {
  return pickerDetailLoadingIds.value.has(item?.resumeId)
}
async function hydratePickerResumes(items) {
  const queue = items.filter((item) => item?.resumeId && !pickerDetails.value[item.resumeId])
  if (!queue.length) return
  const version = ++pickerLoadVersion
  pickerDetailLoadingIds.value = new Set(queue.map((item) => item.resumeId))
  let index = 0
  async function worker() {
    while (index < queue.length && version === pickerLoadVersion) {
      const item = queue[index++]
      try {
        const detail = await getResume(item.resumeId)
        if (version === pickerLoadVersion) pickerDetails.value = { ...pickerDetails.value, [item.resumeId]: detail }
      } catch (_) {
        // Keep the lightweight list item and use the structured summary fallback.
      } finally {
        if (version === pickerLoadVersion) {
          const next = new Set(pickerDetailLoadingIds.value)
          next.delete(item.resumeId)
          pickerDetailLoadingIds.value = next
        }
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, queue.length) }, () => worker()))
}
function canPreview(item) {
  return (item?.suffix || '').toLowerCase() === 'pdf'
}
function previewUrl(item) {
  return item?.resumeId ? resumePreviewUrl(item.resumeId) : '#'
}
function embeddedPreviewUrl(item) {
  return item?.resumeId ? pdfViewerFitWidthUrl(resumePreviewUrl(item.resumeId)) : '#'
}
function thumbnailUrl(item) {
  return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : '#'
}
function downloadUrl(item) {
  return item?.resumeId ? resumeDownloadUrl(item.resumeId) : '#'
}
function closePreview() {
  previewItem.value = null
}
async function runAnalysis() {
  if (!currentAnalysisResume.value?.resumeId) return
  scoreBreakdownExpanded.value = false
  await resume.analyze(currentAnalysisResume.value.resumeId, chat.sessionId).catch(() => {})
}
watch(
  () => currentAnalysisResume.value?.resumeId,
  async () => {
    scoreBreakdownExpanded.value = false
    const canShow = canPreview(currentAnalysisResume.value)
    pdfLoading.value = canShow
    showPdfFrame.value = false
    if (!canShow) return
    await nextTick()
    window.requestAnimationFrame(() => {
      showPdfFrame.value = true
    })
  },
  { immediate: true },
)
</script>

<style>
.resume-analysis-page .analysis-report-kicker {
  margin: 2px 0 0;
}
.resume-analysis-page .resume-analysis-summary {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 16px 0;
}
.resume-analysis-page .analysis-summary-metric {
  --metric-accent: #3157ff;
  --metric-surface: #f3f6ff;
  --metric-border: #dce5ff;
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 14px 15px;
  border: 1px solid var(--metric-border);
  border-radius: 15px;
  background: linear-gradient(145deg, #fff 0%, var(--metric-surface) 100%);
  box-shadow: 0 8px 22px rgba(31, 45, 86, 0.045);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}
.resume-analysis-page .analysis-summary-metric:hover {
  border-color: var(--metric-accent);
  box-shadow: 0 12px 28px rgba(31, 45, 86, 0.09);
  transform: translateY(-1px);
}
.resume-analysis-page .analysis-summary-metric.is-advantage {
  --metric-accent: #079455;
  --metric-surface: #f0fdf6;
  --metric-border: #c9f0d9;
}
.resume-analysis-page .analysis-summary-metric.is-disadvantage {
  --metric-accent: #e04f5f;
  --metric-surface: #fff5f6;
  --metric-border: #ffd7dc;
}
.resume-analysis-page .analysis-summary-metric.is-deep-dive {
  --metric-accent: #7a5af8;
  --metric-surface: #f6f3ff;
  --metric-border: #e2dcff;
}
.resume-analysis-page .analysis-summary-icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  margin: 0;
  border-radius: 12px;
  background: var(--metric-accent);
  color: #fff;
  box-shadow: 0 7px 16px color-mix(in srgb, var(--metric-accent) 24%, transparent);
}
.resume-analysis-page .analysis-summary-icon svg {
  width: 20px;
  height: 20px;
  stroke: currentColor;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.resume-analysis-page .analysis-summary-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  margin: 0;
}
.resume-analysis-page .analysis-summary-copy small {
  color: #667085;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
}
.resume-analysis-page .analysis-summary-copy strong {
  color: #172033;
  font-size: 24px;
  font-weight: 850;
  line-height: 1.05;
  letter-spacing: -0.02em;
}
@media (max-width: 1360px) {
  .resume-analysis-page .resume-analysis-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .resume-analysis-page .resume-analysis-summary {
    grid-template-columns: 1fr;
  }
}
.score-breakdown-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.score-breakdown-card > header > div {
  min-width: 0;
}
.score-breakdown-card > header h3 {
  margin: 0 0 3px;
}
.score-breakdown-card > header span {
  color: #667085;
  font-size: 12px;
}
.score-breakdown-toggle {
  flex: 0 0 auto;
  border: 1px solid #d8e0f0;
  border-radius: 10px;
  padding: 7px 11px;
  background: #fff;
  color: #3157ff;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}
.score-breakdown-toggle:hover {
  border-color: #3157ff;
  background: #f5f7ff;
}
.score-breakdown-toggle:focus-visible {
  outline: 3px solid rgba(49, 87, 255, 0.18);
  outline-offset: 2px;
}
.score-breakdown-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.score-breakdown-item {
  --score-accent: #3157ff;
  --score-soft: #f1f4ff;
  min-width: 0;
  padding: 15px;
  border: 1px solid color-mix(in srgb, var(--score-accent) 20%, #e4e9f5);
  border-radius: 16px;
  background: linear-gradient(145deg, #fff 0%, var(--score-soft) 100%);
  box-shadow: 0 8px 22px rgba(31, 45, 86, 0.05);
}
.score-breakdown-item.is-excellent {
  --score-accent: #7a5af8;
  --score-soft: #f5f2ff;
}
.score-breakdown-item.is-strong {
  --score-accent: #079455;
  --score-soft: #effcf5;
}
.score-breakdown-item.is-good {
  --score-accent: #3157ff;
  --score-soft: #f1f4ff;
}
.score-breakdown-item.is-basic {
  --score-accent: #d97706;
  --score-soft: #fff8eb;
}
.score-breakdown-item.is-weak {
  --score-accent: #e04f5f;
  --score-soft: #fff3f5;
}
.score-dimension-head {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.score-dimension-head > div:first-child {
  min-width: 0;
}
.score-dimension-head > div:first-child strong {
  display: block;
  color: #172033;
  font-size: 14px;
}
.score-dimension-head > div:first-child span {
  display: inline-block;
  margin-top: 4px;
  color: #667085;
  font-size: 11px;
}
.score-dimension-value {
  display: flex;
  flex: 0 0 auto;
  align-items: baseline;
  gap: 6px;
}
.score-dimension-value b {
  color: var(--score-accent);
  font-size: 25px;
  line-height: 1;
}
.score-dimension-value small {
  border-radius: 999px;
  padding: 3px 7px;
  background: color-mix(in srgb, var(--score-accent) 12%, #fff);
  color: var(--score-accent);
  font-size: 10px;
  font-weight: 800;
}
.score-dimension-track {
  overflow: hidden;
  height: 6px;
  margin: 11px 0 12px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.18);
}
.score-dimension-track span {
  display: block;
  width: var(--score-progress);
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, color-mix(in srgb, var(--score-accent) 68%, #fff), var(--score-accent));
}
.score-breakdown-item p {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.65;
}
.score-breakdown-item p strong {
  display: block;
  margin-bottom: 3px;
  color: #475467;
  font-size: 11px;
}
.score-breakdown-item p span {
  color: #667085;
}
@media (max-width: 720px) {
  .score-breakdown-grid {
    grid-template-columns: 1fr;
  }
}
.resume-analysis-task-status {
  display: flex;
  min-height: 0;
  align-items: center;
  gap: 9px;
  margin-bottom: 10px;
  border: 1px solid #dfe6ff;
  border-radius: 11px;
  background: #f7f9ff;
  padding: 8px 12px;
  color: #475467;
  font-size: 12px;
  line-height: 1.4;
}
.resume-analysis-task-status .favorite-analysis-loading-mark {
  width: 18px;
  height: 18px;
  border-width: 2px;
}
.resume-analysis-task-status strong {
  flex: none;
  color: #3157ff;
  font-size: 12px;
}
.resume-analysis-task-status > span:not(.favorite-analysis-loading-mark) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resume-analysis-task-status small {
  flex: none;
  margin-left: auto;
  color: #98a2b3;
}
.resume-analysis-page .analysis-report-list {
  width: 100%;
  align-items: stretch;
}
.resume-analysis-page .analysis-report-list > p {
  width: 100%;
  box-sizing: border-box;
  overflow-wrap: anywhere;
  text-align: left;
}
body > .analysis-resume-picker-mask {
  padding: 20px !important;
  overflow: hidden !important;
}
body > .analysis-resume-picker-mask .analysis-resume-picker-modal {
  display: flex;
  width: min(920px, calc(100vw - 40px));
  height: min(720px, calc(100vh - 40px));
  max-width: calc(100vw - 40px) !important;
  max-height: calc(100vh - 40px) !important;
  flex-direction: column;
  padding: 0;
  overflow: hidden !important;
  border-radius: 22px;
}
.analysis-resume-picker-header {
  position: relative;
  z-index: 2;
  flex: none;
  padding: 26px 64px 18px 28px;
  border-bottom: 1px solid #e8edf6;
  background: #fff;
}
.analysis-resume-picker-close {
  top: 18px !important;
  right: 16px !important;
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  padding: 0;
  border-radius: 10px;
  color: #344054;
  line-height: 1;
  cursor: pointer;
}
.analysis-resume-picker-close:hover {
  background: #f2f4f7;
  color: #172033;
}
.analysis-resume-picker-header .resume-picker-head {
  align-items: flex-start;
  gap: 24px;
  margin: 0;
  padding: 0 !important;
}
.analysis-resume-picker-header .resume-picker-head > div:first-child {
  min-width: 0;
  flex: 1;
}
.analysis-resume-picker-header .resume-picker-head h2 {
  margin: 5px 0 6px;
  color: #172033;
  font-size: 23px;
}
.analysis-resume-picker-header .resume-picker-head p:last-child {
  max-width: 650px;
  margin: 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.65;
}
.analysis-resume-picker-header .resume-picker-head-actions {
  padding-top: 2px;
}
.analysis-resume-picker-search {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 16px;
}
.analysis-resume-picker-search > span {
  color: #475467;
  font-size: 12px;
  font-weight: 800;
}
.analysis-resume-picker-search input {
  width: 100%;
  height: 40px;
  box-sizing: border-box;
  border: 1px solid #d7dfec;
  border-radius: 11px;
  padding: 0 12px;
  outline: none;
  color: #172033;
  background: #fff;
}
.analysis-resume-picker-search input:focus {
  border-color: #3157ff;
  box-shadow: 0 0 0 3px rgba(49, 87, 255, 0.1);
}
.analysis-resume-picker-scroll {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding: 16px 18px 22px 28px;
  background: #fbfcff;
}
.analysis-resume-picker-scroll > .empty-state {
  margin: 0;
}
.analysis-resume-picker-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.analysis-resume-picker-item {
  display: grid !important;
  grid-template-columns: 88px minmax(0, 1fr) 128px;
  align-items: center !important;
  gap: 14px;
  min-width: 0;
  margin: 0 !important;
  padding: 12px !important;
  border-color: #e2e8f2 !important;
  border-radius: 15px !important;
  background: #fff !important;
  box-shadow: 0 5px 16px rgba(31, 45, 86, 0.035);
}
.analysis-resume-picker-item:hover {
  border-color: #c7d2fe !important;
  box-shadow: 0 9px 24px rgba(31, 45, 86, 0.075);
}
.analysis-resume-picker-item.active {
  border-color: #6f8aff !important;
  background: #f7f9ff !important;
  box-shadow:
    0 0 0 1px rgba(49, 87, 255, 0.06),
    0 9px 24px rgba(49, 87, 255, 0.08);
}
.analysis-resume-picker-item .resume-picker-thumb {
  width: 88px;
  min-height: 0;
  align-self: center;
  aspect-ratio: 210 / 297;
  border-radius: 9px;
  box-shadow: 0 3px 10px rgba(31, 45, 86, 0.09);
}
.analysis-resume-picker-main {
  min-width: 0;
  align-self: center !important;
}
.analysis-resume-picker-main > strong {
  display: block;
  overflow: hidden;
  color: #172033;
  font-size: 14px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analysis-resume-picker-main > p {
  display: -webkit-box;
  overflow: hidden;
  margin: 7px 0 9px;
  color: #667085;
  font-size: 12px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.analysis-resume-picker-main > p.loading {
  color: #98a2b3;
}
.analysis-resume-picker-main .skill-list {
  display: flex;
  overflow: hidden;
  flex-wrap: nowrap;
  gap: 5px;
}
.analysis-resume-picker-main .skill-list span {
  overflow: hidden;
  flex: 0 1 auto;
  max-width: 110px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analysis-resume-picker-actions {
  display: flex !important;
  width: 128px;
  align-self: center !important;
  align-items: stretch !important;
  flex-direction: column !important;
  gap: 8px;
  margin: 0 !important;
}
.analysis-resume-picker-actions .state-badge {
  align-self: flex-end;
}
.analysis-resume-picker-actions .primary-btn {
  width: 100%;
  min-width: 0;
  padding: 9px 10px;
}
@media (max-width: 720px) {
  body > .analysis-resume-picker-mask {
    padding: 12px !important;
  }
  body > .analysis-resume-picker-mask .analysis-resume-picker-modal {
    width: calc(100vw - 24px);
    height: calc(100vh - 24px);
    max-width: calc(100vw - 24px) !important;
    max-height: calc(100vh - 24px) !important;
  }
  .analysis-resume-picker-header {
    padding: 22px 52px 16px 20px;
  }
  .analysis-resume-picker-header .resume-picker-head {
    flex-direction: column;
    gap: 12px;
  }
  .analysis-resume-picker-scroll {
    padding: 12px 10px 18px 16px;
  }
  .analysis-resume-picker-item {
    grid-template-columns: 70px minmax(0, 1fr);
    gap: 11px;
  }
  .analysis-resume-picker-item .resume-picker-thumb {
    width: 70px;
  }
  .analysis-resume-picker-actions {
    grid-column: 2;
    width: 100%;
    align-items: center !important;
    flex-direction: row !important;
    justify-content: flex-end;
  }
  .analysis-resume-picker-actions .primary-btn {
    width: auto;
    min-width: 92px;
  }
}
</style>
