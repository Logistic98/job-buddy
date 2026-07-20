<template>
  <section :class="['job-panel', { 'favorite-jobs-panel': isFavoritesMode }]">
    <header :class="{ 'page-header favorite-jobs-header': isFavoritesMode }">
      <div>
        <p v-if="isFavoritesMode" class="eyebrow">Favorite Jobs</p>
        <h1 v-if="isFavoritesMode">{{ panelTitle }}</h1>
        <h2 v-else>{{ panelTitle }}</h2>
        <p>{{ panelSubtitle }}</p>
      </div>
      <div v-if="isFavoritesMode" class="favorite-jobs-header-actions">
        <button type="button" class="boss-favorite-import-entry" @click="openBossImport">
          <span class="boss-favorite-import-logo">B</span>
          <span>从 BOSS 直聘导入</span>
        </button>
        <div
          class="favorite-jobs-count"
          :aria-label="`当前展示 ${filteredRows.length} 个，共收藏 ${sourceRows.length} 个岗位`"
        >
          <strong>{{ filteredRows.length }}</strong>
          <span>共 {{ sourceRows.length }} 个收藏</span>
        </div>
      </div>
      <span v-else>{{ filteredRows.length }} / {{ sourceRows.length }} 个</span>
    </header>

    <div class="job-filter-bar" role="search">
      <div class="job-search">
        <svg class="job-search-icon" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6.5" />
          <path d="m16 16 4 4" />
        </svg>
        <input v-model.trim="keyword" type="search" :aria-label="searchPlaceholder" :placeholder="searchPlaceholder" />
      </div>
      <button v-if="keyword" type="button" class="ghost-mini-btn job-filter-clear" @click="keyword = ''">清空</button>
    </div>

    <div v-if="!sourceRows.length" class="empty">
      <strong>{{ emptyTitle }}</strong>
      <p>{{ emptyText }}</p>
    </div>
    <div v-else-if="!filteredRows.length" class="empty">
      <strong>没有匹配的岗位</strong>
      <p>请换一个公司、岗位、城市、薪资、技术栈或风险关键词再检索。</p>
    </div>

    <article v-for="row in filteredRows" :key="jobId(row.item, row.sourceIndex)" class="job-card">
      <div class="job-card-main">
        <div class="job-title">
          <strong>{{ title(row.item) }}</strong>
        </div>
        <p class="company">
          {{ company(row.item) }} · {{ locationText(row.item) }} · {{ experienceText(row.item) }}
          <span v-if="isFavoritesMode && favoriteTime(row.item)" class="favorite-time-badge"
            >收藏于 {{ favoriteTime(row.item) }}</span
          >
        </p>
        <div class="job-info-grid">
          <span v-if="row.item.jobDegree || row.item.education"
            >学历：{{ row.item.jobDegree || row.item.education }}</span
          >
          <span v-if="industryText(row.item)">行业：{{ industryText(row.item) }}</span>
          <span v-if="scaleText(row.item)">规模：{{ scaleText(row.item) }}</span>
          <span v-if="stageText(row.item)">阶段：{{ stageText(row.item) }}</span>
          <span v-if="bossText(row.item)">Boss：{{ bossText(row.item) }}</span>
        </div>
        <p v-if="descriptionText(row.item)" class="job-summary">{{ descriptionText(row.item) }}</p>
        <div v-if="isJobDescriptionOpen(row.item) && fullDescription(row.item)" class="job-jd-full">
          {{ fullDescription(row.item) }}
        </div>
        <p v-if="job.detailError(row.item)" class="job-jd-error">{{ job.detailError(row.item) }}</p>
        <div v-if="welfare(row.item).length" class="job-welfare">
          <span v-for="item in welfare(row.item)" :key="item">{{ item }}</span>
        </div>
        <div v-if="researchOf(row.item, row.sourceIndex).links?.length" class="job-actions">
          <a
            v-for="link in researchOf(row.item, row.sourceIndex).links || []"
            :key="link.name"
            class="job-research-link"
            :href="link.url"
            target="_blank"
            rel="noreferrer"
            >{{ link.name }}</a
          >
        </div>
        <div class="tags">
          <span v-for="tag in tags(row.item)" :key="tag">{{ tag }}</span>
        </div>
      </div>
      <b class="job-card-salary">{{ salaryText(row.item) }}</b>
      <div v-if="matchOf(row.item, row.sourceIndex)" class="match-box">
        <b>{{ matchOf(row.item, row.sourceIndex).score }} 分</b>
        <span>{{ matchOf(row.item, row.sourceIndex).recommendation }}</span>
        <p>{{ matchOf(row.item, row.sourceIndex).reasoning }}</p>
        <div v-if="matchOf(row.item, row.sourceIndex).hits?.length" class="analysis-list">
          <strong>命中点</strong><em v-for="x in matchOf(row.item, row.sourceIndex).hits" :key="x">{{ x }}</em>
        </div>
        <div v-if="matchOf(row.item, row.sourceIndex).gaps?.length" class="analysis-list">
          <strong>差距</strong><em v-for="x in matchOf(row.item, row.sourceIndex).gaps" :key="x">{{ x }}</em>
        </div>
      </div>
      <div
        v-if="researchOf(row.item, row.sourceIndex).summary"
        :class="['risk-box', researchOf(row.item, row.sourceIndex).riskLevel]"
      >
        <b>企业核验：{{ riskText(researchOf(row.item, row.sourceIndex).riskLevel) }}</b>
        <p>{{ researchOf(row.item, row.sourceIndex).summary }}</p>
      </div>
      <div class="job-card-actions">
        <button
          type="button"
          class="favorite-job-btn job-jd-btn"
          :disabled="job.isLoadingDetail(row.item)"
          @click.stop="toggleJobDescription(row.item)"
        >
          {{ jobDescriptionButtonText(row.item) }}
        </button>
        <a
          v-if="originalUrl(row.item)"
          class="job-origin-link"
          :href="originalUrl(row.item)"
          target="_blank"
          rel="noreferrer"
          title="打开 Boss 直聘岗位详情页"
          @click.stop
        >
          <span class="origin-dot">B</span>
          <span class="origin-text">Boss 原岗位</span>
          <span class="origin-arrow">↗</span>
        </a>
        <button v-if="isFavoritesMode" type="button" class="favorite-job-btn" @click.stop="openAnalysisModal(row.item)">
          {{ job.isAnalyzingFavorite(row.item) ? '分析中' : favoriteAnalysis(row.item) ? '查看分析' : '分析岗位' }}
        </button>
        <button
          v-if="!isFavoritesMode"
          type="button"
          class="favorite-job-btn"
          :class="{ active: job.isFavorite(row.item) }"
          @click.stop="toggleFavorite(row.item)"
        >
          {{ job.isFavorite(row.item) ? '已收藏' : '收藏岗位' }}
        </button>
        <button
          v-if="isFavoritesMode"
          type="button"
          class="remove-job-btn"
          :disabled="job.isRemovingFavorite(row.item)"
          @click.stop="openRemoveDialog(row.item)"
        >
          {{ job.isRemovingFavorite(row.item) ? '移除中' : '移出收藏' }}
        </button>
      </div>
    </article>

    <div v-if="removeDialog.visible" class="modal-mask history-delete-mask" @click.self="closeRemoveDialog">
      <div class="history-delete-modal" role="dialog" aria-modal="true" aria-labelledby="favorite-remove-title">
        <button
          class="close"
          type="button"
          aria-label="关闭"
          :disabled="removeDialog.pending"
          @click="closeRemoveDialog"
        >
          ×
        </button>
        <p class="eyebrow">移出收藏</p>
        <h2 id="favorite-remove-title">确认移出“{{ title(removeDialog.item || {}) }}”？</h2>
        <p>{{ company(removeDialog.item || {}) }} · {{ locationText(removeDialog.item || {}) }}</p>
        <p>移出后，该岗位及已保存的岗位分析将不再出现在收藏列表中。</p>
        <p v-if="removeDialog.error" class="error settings-error" role="alert">{{ removeDialog.error }}</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" type="button" :disabled="removeDialog.pending" @click="closeRemoveDialog">
            取消
          </button>
          <button class="danger-btn" type="button" :disabled="removeDialog.pending" @click="confirmRemoveFavorite">
            {{ removeDialog.pending ? '移除中' : '确认移出' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="analysisModalVisible" class="modal-mask favorite-analysis-modal-mask" @click.self="closeAnalysisModal">
      <div
        class="modal-card favorite-analysis-modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="favorite-analysis-title"
      >
        <button
          class="close favorite-analysis-close"
          type="button"
          aria-label="关闭岗位分析"
          @click="closeAnalysisModal"
        >
          ×
        </button>
        <div class="favorite-analysis-modal-head">
          <div class="favorite-analysis-title-block">
            <p class="eyebrow">岗位匹配分析</p>
            <h2 id="favorite-analysis-title">{{ modalJobTitle }}</h2>
            <p class="favorite-analysis-job-meta">
              {{ modalCompanyText }}<span>{{ modalLocationText }}</span
              ><span>{{ salaryText(modalJob || {}) }}</span
              ><span>{{ experienceText(modalJob || {}) }}</span>
            </p>
          </div>
          <div class="favorite-analysis-context-card">
            <span>分析简历</span>
            <strong :title="modalResumeName">{{ modalResumeName }}</strong>
            <small v-if="analysisTime(modalJob)">更新时间 {{ analysisTime(modalJob) }}</small>
          </div>
        </div>

        <div class="favorite-analysis-modal-body">
          <div v-if="analysisModalPending" class="favorite-analysis-loading">
            <span class="favorite-analysis-loading-mark" aria-hidden="true"></span>
            <div>
              <strong>正在生成岗位决策报告</strong>
              <p>系统正在核对岗位要求、简历证据、关键缺口和面试准备方向。</p>
            </div>
          </div>
          <div v-if="modalAnalysisError && !modalAnalysis" class="favorite-analysis-error-box">
            <strong>岗位分析失败</strong>
            <p>{{ modalAnalysisError }}</p>
          </div>
          <div v-else-if="modalAnalysis" class="favorite-analysis-report">
            <nav class="favorite-analysis-tabs" aria-label="岗位分析报告分组">
              <button
                type="button"
                :class="{ active: analysisReportTab === 'overview' }"
                @click="analysisReportTab = 'overview'"
              >
                <strong>决策概览</strong><span>结论、能力与差距</span>
              </button>
              <button
                type="button"
                :class="{ active: analysisReportTab === 'evidence' }"
                :disabled="!modalHasEvidenceGroup"
                @click="analysisReportTab = 'evidence'"
              >
                <strong>证据与风险</strong><span>评分依据与风险项</span>
              </button>
              <button
                type="button"
                :class="{ active: analysisReportTab === 'actions' }"
                :disabled="!modalHasActionGroup"
                @click="analysisReportTab = 'actions'"
              >
                <strong>行动方案</strong><span>简历补强与面试准备</span>
              </button>
            </nav>

            <div v-if="analysisReportTab === 'overview'" class="favorite-analysis-group">
              <section class="favorite-analysis-overview" aria-label="投递决策总览">
                <div
                  class="favorite-analysis-score"
                  :style="modalScoreStyle"
                  role="img"
                  :aria-label="`岗位匹配分 ${modalScore} 分`"
                >
                  <div>
                    <strong>{{ modalScore }}</strong
                    ><span>匹配分</span>
                  </div>
                </div>
                <div class="favorite-analysis-verdict">
                  <div class="favorite-analysis-verdict-line">
                    <span :class="['favorite-analysis-recommendation', modalRecommendationTone]">{{
                      modalRecommendation
                    }}</span>
                    <span class="favorite-analysis-confidence">{{ modalConfidenceLabel }}</span>
                  </div>
                  <h3>{{ modalDecisionTitle }}</h3>
                  <p>{{ modalAnalysis.reasoning || '当前分析已生成，可结合下方证据、缺口与行动建议决定是否投递。' }}</p>
                </div>
              </section>

              <section v-if="modalDimensionRows.length" class="favorite-analysis-section favorite-analysis-dimensions">
                <div class="favorite-analysis-section-head">
                  <div>
                    <span>匹配拆解</span>
                    <h3>五维能力评估</h3>
                  </div>
                  <p>分数来自岗位要求与简历证据的逐项对照</p>
                </div>
                <div class="favorite-analysis-dimension-list">
                  <article
                    v-for="dimension in modalDimensionRows"
                    :key="dimension.key"
                    class="favorite-analysis-dimension-row"
                  >
                    <div class="favorite-analysis-dimension-title">
                      <strong>{{ dimension.label }}</strong
                      ><b>{{ dimension.score }}<small>/100</small></b>
                    </div>
                    <div
                      class="favorite-analysis-progress"
                      role="progressbar"
                      :aria-label="dimension.label"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      :aria-valuenow="dimension.score"
                    >
                      <span :style="{ width: `${dimension.score}%` }"></span>
                    </div>
                    <p v-if="dimension.evidence">{{ dimension.evidence }}</p>
                    <small v-if="dimension.gap"><b>待补强</b>{{ dimension.gap }}</small>
                  </article>
                </div>
              </section>

              <div v-if="modalHits.length || modalGaps.length" class="favorite-analysis-two-column">
                <section
                  v-if="modalHits.length"
                  class="favorite-analysis-section favorite-analysis-list-card strengths"
                >
                  <div class="favorite-analysis-section-head">
                    <div>
                      <span>匹配项</span>
                      <h3>值得强调的优势</h3>
                    </div>
                  </div>
                  <ol>
                    <li v-for="item in modalHits" :key="item">
                      <span>{{ item }}</span>
                    </li>
                  </ol>
                </section>
                <section v-if="modalGaps.length" class="favorite-analysis-section favorite-analysis-list-card gaps">
                  <div class="favorite-analysis-section-head">
                    <div>
                      <span>关键缺口</span>
                      <h3>可能影响录用的差距</h3>
                    </div>
                  </div>
                  <ol>
                    <li v-for="item in modalGaps" :key="item">
                      <span>{{ item }}</span>
                    </li>
                  </ol>
                </section>
              </div>
            </div>

            <div v-else-if="analysisReportTab === 'evidence'" class="favorite-analysis-group">
              <section v-if="modalEvidenceRows.length" class="favorite-analysis-section favorite-analysis-evidence">
                <div class="favorite-analysis-section-head">
                  <div>
                    <span>评分依据</span>
                    <h3>岗位要求与简历证据</h3>
                  </div>
                  <p>用于判断分数是否可信，也便于准备面试表达</p>
                </div>
                <div class="favorite-analysis-evidence-head">
                  <span>岗位要求</span><span>简历证据</span><span>分析判断</span>
                </div>
                <article
                  v-for="(item, index) in modalEvidenceRows"
                  :key="`${index}-${item.requirement}`"
                  class="favorite-analysis-evidence-row"
                >
                  <p>{{ item.requirement }}</p>
                  <p>{{ item.resume }}</p>
                  <p>{{ item.assessment }}</p>
                </article>
              </section>
              <section v-if="modalRisks.length" class="favorite-analysis-section favorite-analysis-risk-card">
                <div class="favorite-analysis-section-head">
                  <div>
                    <span>风险检查</span>
                    <h3>投递与定级风险</h3>
                  </div>
                </div>
                <ul>
                  <li v-for="item in modalRisks" :key="item">{{ item }}</li>
                </ul>
              </section>
              <details v-if="modalLimitations.length" class="favorite-analysis-limitations">
                <summary>查看分析限制与证据不足</summary>
                <ul>
                  <li v-for="item in modalLimitations" :key="item">{{ item }}</li>
                </ul>
              </details>
            </div>

            <div v-else class="favorite-analysis-group favorite-analysis-actions-group">
              <div class="favorite-analysis-two-column favorite-analysis-actions-grid">
                <section
                  v-if="modalImprovementActions.length"
                  class="favorite-analysis-section favorite-analysis-action-card"
                >
                  <div class="favorite-analysis-section-head">
                    <div>
                      <span>投递前</span>
                      <h3>简历与证据补强</h3>
                    </div>
                  </div>
                  <ul>
                    <li v-for="item in modalImprovementActions" :key="item">{{ item }}</li>
                  </ul>
                </section>
                <section
                  v-if="modalInterviewFocus.length"
                  class="favorite-analysis-section favorite-analysis-action-card interview"
                >
                  <div class="favorite-analysis-section-head">
                    <div>
                      <span>面试前</span>
                      <h3>重点准备方向</h3>
                    </div>
                  </div>
                  <ul>
                    <li v-for="item in modalInterviewFocus" :key="item">{{ item }}</li>
                  </ul>
                </section>
              </div>
            </div>
          </div>
          <div v-else-if="!analysisModalPending" class="favorite-analysis-empty-box">
            <strong>暂无岗位分析结果</strong>
            <p>点击“开始分析”，系统将结合当前简历生成投递建议、能力拆解、证据对照和准备计划。</p>
          </div>
        </div>

        <footer class="modal-actions favorite-analysis-modal-actions">
          <span v-if="modalAnalysis && analysisTime(modalJob)"
            >本报告基于 {{ analysisTime(modalJob) }} 的岗位与简历信息</span
          >
          <div>
            <button class="secondary-btn" type="button" @click="closeAnalysisModal">关闭</button
            ><button
              class="primary-btn"
              type="button"
              :disabled="analysisModalPending || !modalJob"
              @click="runFavoriteAnalysis(true)"
            >
              {{ analysisModalPending ? '分析中' : modalAnalysis ? '重新分析' : '开始分析' }}
            </button>
          </div>
        </footer>
      </div>
    </div>

    <BossFavoriteImportModal :visible="bossImportVisible" @close="closeBossImport" />
  </section>
</template>
<script setup>
import { computed, reactive, ref } from 'vue'
import BossFavoriteImportModal from './BossFavoriteImportModal.vue'
import { useJobStore } from '../stores/job'
import { useResumeStore } from '../stores/resume'
import { useChatStore } from '../stores/chat'
import { compactJobSummaryText, firstJobDescriptionText, normalizeJobDescriptionText } from '../utils/jobText'
import { bossDetailUrl } from '../utils/zhipinUrl'

const props = defineProps({ mode: { type: String, default: 'workspace' } })
const job = useJobStore()
const resume = useResumeStore()
const chat = useChatStore()
const keyword = ref('')
const expandedJdKeys = ref(new Set())
const bossImportVisible = ref(false)

const isFavoritesMode = computed(() => props.mode === 'favorites')

function openBossImport() {
  bossImportVisible.value = true
}

function closeBossImport() {
  bossImportVisible.value = false
}

const analysisModalVisible = ref(false)
const analysisModalRequestError = ref('')
const analysisReportTab = ref('overview')
const modalJob = ref(null)
const removeDialog = reactive({ visible: false, pending: false, item: null, error: '' })
const sourceRows = computed(() =>
  (isFavoritesMode.value ? job.favorites : job.jobs).map((item, sourceIndex) => ({ item, sourceIndex })),
)
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return sourceRows.value
  return sourceRows.value.filter((row) => searchableText(row.item, row.sourceIndex).includes(q))
})
const panelTitle = computed(() => (isFavoritesMode.value ? '岗位收藏' : '岗位工作台'))
const panelSubtitle = computed(() =>
  isFavoritesMode.value
    ? '这里只展示你手动标记收藏的岗位，支持按公司、岗位、城市、薪资、技术栈和风险关键词检索。'
    : '真实来自 Boss 推荐池，支持原始链接、AI 匹配评分和公司风险核验入口。',
)
const emptyTitle = computed(() => (isFavoritesMode.value ? '暂无收藏岗位' : '暂无岗位数据'))
const emptyText = computed(() =>
  isFavoritesMode.value ? '请先在聊天推荐结果或岗位工作台中收藏岗位。' : '请先登录 Boss，再描述岗位条件。',
)
const searchPlaceholder = computed(() =>
  isFavoritesMode.value ? '搜索已收藏岗位、公司、城市、薪资、标签' : '搜索当前岗位、公司、城市、薪资、标签',
)
const analysisModalPending = computed(() => !!modalJob.value && job.isAnalyzingFavorite(modalJob.value))
const modalAnalysis = computed(() => favoriteAnalysis(latestFavorite(modalJob.value)))
const modalAnalysisError = computed(() => analysisModalRequestError.value || job.favoriteAnalysisError(modalJob.value))
const modalJobTitle = computed(() => title(modalJob.value || {}))
const modalCompanyText = computed(() => company(modalJob.value || {}))
const modalLocationText = computed(() => locationText(modalJob.value || {}))
const modalResumeName = computed(
  () =>
    latestFavorite(modalJob.value)?.analysis?.resumeName ||
    resume.current?.originalName ||
    resume.current?.fileName ||
    '当前简历',
)
const modalScore = computed(() => normalizedScore(modalAnalysis.value?.score))
const modalScoreStyle = computed(() => ({ '--analysis-score-angle': `${modalScore.value * 3.6}deg` }))
const modalRecommendation = computed(() => String(modalAnalysis.value?.recommendation || '证据不足'))
const modalRecommendationTone = computed(() => recommendationTone(modalRecommendation.value))
const modalConfidenceLabel = computed(() =>
  confidenceLabel(modalAnalysis.value?.score_confidence || modalAnalysis.value?.confidence),
)
const modalDecisionTitle = computed(() => decisionTitle(modalRecommendation.value))
const modalDimensionRows = computed(() => analysisDimensions(modalAnalysis.value?.dimensions))
const modalHits = computed(() => textList(modalAnalysis.value?.hits))
const modalGaps = computed(() => textList(modalAnalysis.value?.gaps))
const modalRisks = computed(() => textList(modalAnalysis.value?.risks))
const modalImprovementActions = computed(() => textList(modalAnalysis.value?.improvement_actions))
const modalInterviewFocus = computed(() => textList(modalAnalysis.value?.interview_focus))
const modalLimitations = computed(() => textList(modalAnalysis.value?.limitations))
const modalEvidenceRows = computed(() => analysisEvidence(modalAnalysis.value?.evidence))
const modalHasEvidenceGroup = computed(
  () => modalEvidenceRows.value.length > 0 || modalRisks.value.length > 0 || modalLimitations.value.length > 0,
)
const modalHasActionGroup = computed(
  () => modalImprovementActions.value.length > 0 || modalInterviewFocus.value.length > 0,
)

function title(item) {
  return item.jobName || item.job_name || item.title || '未知岗位'
}
function company(item) {
  return item.brandName || item.companyName || item.company || '未知公司'
}
function asArray(value) {
  return Array.isArray(value) ? value : value ? [value] : []
}
function normalizedScore(value) {
  const score = Number(value)
  return Number.isFinite(score) ? Math.max(0, Math.min(100, Math.round(score))) : 0
}
function textList(value) {
  return asArray(value)
    .map((item) => {
      if (item && typeof item === 'object') return item.title || item.detail || item.content || item.text || ''
      return String(item || '')
    })
    .map((item) => String(item).trim())
    .filter(Boolean)
}
function recommendationTone(value) {
  if (value === '推荐') return 'recommended'
  if (value === '可尝试') return 'try'
  if (value === '谨慎') return 'caution'
  if (value === '不建议') return 'rejected'
  return 'insufficient'
}
function confidenceLabel(value) {
  if (value === 'high') return '高置信度'
  if (value === 'medium') return '中等置信度'
  if (value === 'low') return '低置信度'
  return '置信度待确认'
}
function decisionTitle(value) {
  if (value === '推荐') return '建议优先投递，重点放大匹配证据'
  if (value === '可尝试') return '具备投递价值，先补齐关键证据'
  if (value === '谨慎') return '可以尝试，但需要先处理主要风险'
  if (value === '不建议') return '当前不建议优先投入求职精力'
  return '现有证据不足，建议补充信息后再判断'
}
function analysisDimensions(value) {
  if (!value || typeof value !== 'object') return []
  const labels = {
    technical_skill: '技术栈匹配',
    seniority: '经验与级别',
    project_relevance: '项目相关性',
    domain_fit: '业务领域契合',
    constraints: '地点与约束',
  }
  return Object.entries(labels)
    .map(([key, label]) => {
      const row = value[key]
      if (!row || typeof row !== 'object') return null
      const evidence = String(row.evidence || '').trim()
      const gap = String(row.gap || '').trim()
      if (row.score == null && !evidence && !gap) return null
      return { key, label, score: normalizedScore(row.score), evidence, gap }
    })
    .filter(Boolean)
}
function analysisEvidence(value) {
  return asArray(value)
    .map((item) => {
      if (!item || typeof item !== 'object') return null
      const requirement = String(item.job_requirement || item.requirement || '').trim()
      const resumeEvidence = String(item.resume_evidence || item.resume || '').trim()
      const assessment = String(item.assessment || item.conclusion || '').trim()
      if (!requirement && !resumeEvidence && !assessment) return null
      return {
        requirement: requirement || '岗位要求未明确',
        resume: resumeEvidence || '简历中暂无直接证据',
        assessment: assessment || '需要进一步核实',
      }
    })
    .filter(Boolean)
}
function tags(item) {
  return [
    ...asArray(item.skills),
    ...asArray(item.skillList),
    ...asArray(item.jobLabels),
    item.brandIndustry,
    item.industry,
  ]
    .map((x) => String(x || '').trim())
    .filter((x) => x && !/^\d{4,}$/.test(x))
    .slice(0, 10)
}
function originalUrl(item) {
  const url = item.originalUrl || item.jobUrl || item.url || item.href || item.link || item.detailUrl || ''
  if (url && String(url).includes('/job_detail/')) return url
  return bossDetailUrl(item)
}
function locationText(item) {
  return item.cityName || item.city || item.location || item.areaDistrict || '城市未标注'
}
function experienceText(item) {
  return item.jobExperience || item.experience || '经验不限'
}
function industryText(item) {
  return item.companyIndustry || item.brandIndustry || item.industry || item.industryName || ''
}
function scaleText(item) {
  return item.companyScale || item.brandScaleName || item.scaleName || item.brandScale || ''
}
function stageText(item) {
  return item.companyStage || item.brandStageName || item.financeStage || item.stageName || ''
}
function bossText(item) {
  return [item.bossName, item.bossTitle || item.bossPosition].filter(Boolean).join(' · ')
}
function salaryText(item) {
  const value =
    item.salaryDesc ||
    item.salary_desc ||
    item.salary ||
    item.salaryText ||
    item.salaryName ||
    item.salaryRange ||
    item.jobSalary ||
    item.pay ||
    item.wage ||
    item.compensation ||
    ''
  return String(value || '').trim() || '薪资未标注'
}
function descriptionText(item) {
  return compactJobSummaryText(firstJobDescriptionText(item), 180)
}
function fullDescription(item) {
  return normalizeJobDescriptionText(firstJobDescriptionText(item))
}
function hasLoadedJd(item) {
  return !!fullDescription(item)
}
function isJobDescriptionOpen(item) {
  return expandedJdKeys.value.has(favoriteKey(item))
}
function jobDescriptionButtonText(item) {
  if (job.isLoadingDetail(item)) return '加载中'
  if (isJobDescriptionOpen(item)) return '收起描述'
  return '职位描述'
}
async function toggleFavorite(item) {
  try {
    await job.toggleFavorite(item)
  } catch (error) {
    if (error?.authRequired) {
      chat.authRequired = error.authData || { message: error.message }
    }
  }
}
function openRemoveDialog(item) {
  if (!item || job.isRemovingFavorite(item)) return
  Object.assign(removeDialog, { visible: true, pending: false, item, error: '' })
}
function closeRemoveDialog() {
  if (removeDialog.pending) return
  Object.assign(removeDialog, { visible: false, pending: false, item: null, error: '' })
}
async function confirmRemoveFavorite() {
  if (!removeDialog.item || removeDialog.pending) return
  removeDialog.pending = true
  removeDialog.error = ''
  try {
    await job.removeFavorite(removeDialog.item)
    Object.assign(removeDialog, { visible: false, pending: false, item: null, error: '' })
  } catch (error) {
    removeDialog.error = error?.message || '移出岗位收藏失败'
    removeDialog.pending = false
  }
}
async function toggleJobDescription(item) {
  const key = favoriteKey(item)
  if (isJobDescriptionOpen(item)) {
    expandedJdKeys.value.delete(key)
    expandedJdKeys.value = new Set(expandedJdKeys.value)
    return
  }
  if (!hasLoadedJd(item)) {
    try {
      await job.loadJobDetail(item, originalUrl(item))
    } catch (error) {
      if (error?.authRequired) {
        chat.authRequired = error.authData || { message: error.message }
        return
      }
      return
    }
    if (!hasLoadedJd(latestJob(item))) return
  }
  expandedJdKeys.value.add(key)
  expandedJdKeys.value = new Set(expandedJdKeys.value)
}
function latestJob(item) {
  const key = favoriteKey(item)
  return (
    job.jobs.find((row) => favoriteKey(row) === key) || job.favorites.find((row) => favoriteKey(row) === key) || item
  )
}
function welfare(item) {
  return asArray(item.welfareList || item.welfare || item.benefits)
    .map(String)
    .filter(Boolean)
    .slice(0, 8)
}
function favoriteTime(item) {
  const value = item?.favoritedAt || item?.favoriteAt || item?.createdAt || item?.updatedAt
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function jobId(item, idx) {
  return String(item.favoriteKey || item.securityId || item.id || item.jobId || item.encryptJobId || `job_${idx}`)
}
function favoriteKey(item) {
  return String(
    item?.favoriteKey ||
      item?.securityId ||
      item?.id ||
      item?.jobId ||
      item?.encryptJobId ||
      `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`,
  )
}
// 匹配与企业核验结果按 id/公司预建索引：模板对每张卡片会多次调用 matchOf/researchOf，
// 若每次都对结果数组做线性 find，单次列表渲染的复杂度是 O(卡片数 * 结果数 * 调用次数)。
// 这里把数组一次性收敛成 Map，命中缓存后查找降为 O(1)，且仅在 job.match 变化时重建，渲染语义不变。
const matchById = computed(() => {
  const map = new Map()
  for (const m of job.match?.matches || []) {
    const key = String(m?.id)
    if (key && !map.has(key)) map.set(key, m)
  }
  return map
})
const researchByJobId = computed(() => {
  const map = new Map()
  for (const row of job.match?.companyResearch || []) {
    const key = String(row?.jobId)
    if (key && !map.has(key)) map.set(key, row)
  }
  return map
})
const researchByCompany = computed(() => {
  const map = new Map()
  for (const row of job.match?.companyResearch || []) {
    if (row?.company != null && !map.has(row.company)) map.set(row.company, row)
  }
  return map
})
function matchOf(item, idx) {
  const matches = job.match?.matches || []
  const id = jobId(item, idx)
  return (
    matchById.value.get(id) ||
    (matches.length === sourceRows.value.length ? matches[idx] : null) ||
    (item.matchScore
      ? {
          score: item.matchScore,
          recommendation: item.matchRecommendation,
          reasoning: item.matchReasoning,
          hits: item.hits,
          gaps: item.gaps,
        }
      : null)
  )
}
function researchOf(item, idx) {
  const id = jobId(item, idx)
  return researchByJobId.value.get(id) || researchByCompany.value.get(company(item)) || item.companyResearch || {}
}
function favoriteAnalysis(item) {
  const analysis = item?.analysis
  if (!analysis || typeof analysis !== 'object') return null
  const match = analysis.match && typeof analysis.match === 'object' ? analysis.match : analysis
  return Object.keys(match).length ? match : null
}
function analysisTime(item) {
  const current = latestFavorite(item)
  const value = current?.analyzedAt || current?.analysis?.analyzedAt
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function closeAnalysisModal() {
  analysisModalVisible.value = false
  analysisModalRequestError.value = ''
  modalJob.value = null
}
function latestFavorite(item) {
  const key = favoriteKey(item)
  return job.favorites.find((row) => favoriteKey(row) === key) || item
}
async function openAnalysisModal(item) {
  modalJob.value = latestFavorite(item)
  analysisModalRequestError.value = ''
  analysisReportTab.value = 'overview'
  analysisModalVisible.value = true
  await job.restoreFavoriteAnalysis(modalJob.value).catch(() => {})
  if (!favoriteAnalysis(latestFavorite(modalJob.value)) && !analysisModalPending.value) await runFavoriteAnalysis(false)
}
async function runFavoriteAnalysis(force = false) {
  if (!modalJob.value || analysisModalPending.value) return
  if (!force && favoriteAnalysis(modalJob.value)) return
  analysisModalRequestError.value = ''
  try {
    await job.analyzeFavorite(modalJob.value, resume.current?.resumeId || '')
  } catch (error) {
    if (error?.authRequired) {
      // 登录态失效：弹出扫码登录而非只展示通用错误，用户登录后可重新发起分析。
      chat.authRequired = error.authData || { message: error.message }
      analysisModalRequestError.value = 'Boss 直聘登录态已失效，请扫码登录后重试分析。'
    } else {
      analysisModalRequestError.value = error?.message || '岗位分析失败'
    }
  }
}
function riskText(level) {
  if (level === 'high') return '高风险'
  if (level === 'medium') return '需关注'
  if (level === 'low') return '低风险'
  return '待联网核验'
}
function searchableText(item, idx) {
  const match = matchOf(item, idx) || {}
  const research = researchOf(item, idx) || {}
  return [
    title(item),
    company(item),
    locationText(item),
    salaryText(item),
    experienceText(item),
    item.jobDegree,
    item.education,
    industryText(item),
    scaleText(item),
    stageText(item),
    bossText(item),
    descriptionText(item),
    ...welfare(item),
    ...tags(item),
    match.score,
    match.recommendation,
    match.reasoning,
    ...(match.hits || []),
    ...(match.gaps || []),
    research.riskLevel,
    research.summary,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}
</script>
