<template>
  <section class="job-panel">
    <header>
      <div>
        <h2>{{ panelTitle }}</h2>
        <p>{{ panelSubtitle }}</p>
      </div>
      <span>{{ filteredRows.length }} / {{ sourceRows.length }} 个</span>
    </header>

    <div class="job-filter-bar">
      <label class="job-search">
        <span>检索</span>
        <input v-model.trim="keyword" :placeholder="searchPlaceholder" />
      </label>
      <button v-if="keyword" type="button" class="ghost-mini-btn" @click="keyword = ''">清空</button>
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
          <span v-if="isFavoritesMode && favoriteTime(row.item)" class="favorite-time-badge">收藏于 {{ favoriteTime(row.item) }}</span>
        </p>
        <div class="job-info-grid">
          <span v-if="row.item.jobDegree || row.item.education">学历：{{ row.item.jobDegree || row.item.education }}</span>
          <span v-if="industryText(row.item)">行业：{{ industryText(row.item) }}</span>
          <span v-if="scaleText(row.item)">规模：{{ scaleText(row.item) }}</span>
          <span v-if="stageText(row.item)">阶段：{{ stageText(row.item) }}</span>
          <span v-if="bossText(row.item)">Boss：{{ bossText(row.item) }}</span>
        </div>
        <p v-if="descriptionText(row.item)" class="job-summary">{{ descriptionText(row.item) }}</p>
        <div class="job-jd-bar">
          <button type="button" class="ghost-mini-btn job-jd-btn" :disabled="job.isLoadingDetail(row.item)" @click.stop="toggleJobDescription(row.item)">
            {{ jobDescriptionButtonText(row.item) }}
          </button>
          <span v-if="job.detailError(row.item)" class="job-jd-error">{{ job.detailError(row.item) }}</span>
        </div>
        <div v-if="isJobDescriptionOpen(row.item) && fullDescription(row.item)" class="job-jd-full">{{ fullDescription(row.item) }}</div>
        <div v-if="welfare(row.item).length" class="job-welfare"><span v-for="item in welfare(row.item)" :key="item">{{ item }}</span></div>
        <div v-if="researchOf(row.item, row.sourceIndex).links?.length" class="job-actions">
          <a v-for="link in researchOf(row.item, row.sourceIndex).links || []" :key="link.name" class="job-research-link" :href="link.url" target="_blank" rel="noreferrer">{{ link.name }}</a>
        </div>
        <div class="tags"><span v-for="tag in tags(row.item)" :key="tag">{{ tag }}</span></div>
      </div>
      <b class="job-card-salary">{{ salaryText(row.item) }}</b>
      <div v-if="matchOf(row.item, row.sourceIndex)" class="match-box">
        <b>{{ matchOf(row.item, row.sourceIndex).score }} 分</b>
        <span>{{ matchOf(row.item, row.sourceIndex).recommendation }}</span>
        <p>{{ matchOf(row.item, row.sourceIndex).reasoning }}</p>
        <div v-if="matchOf(row.item, row.sourceIndex).hits?.length" class="analysis-list"><strong>命中点</strong><em v-for="x in matchOf(row.item, row.sourceIndex).hits" :key="x">{{ x }}</em></div>
        <div v-if="matchOf(row.item, row.sourceIndex).gaps?.length" class="analysis-list"><strong>差距</strong><em v-for="x in matchOf(row.item, row.sourceIndex).gaps" :key="x">{{ x }}</em></div>
      </div>
      <div v-if="researchOf(row.item, row.sourceIndex).summary" :class="['risk-box', researchOf(row.item, row.sourceIndex).riskLevel]">
        <b>企业核验：{{ riskText(researchOf(row.item, row.sourceIndex).riskLevel) }}</b>
        <p>{{ researchOf(row.item, row.sourceIndex).summary }}</p>
      </div>
      <div class="job-card-actions">
        <a v-if="originalUrl(row.item)" class="job-origin-link" :href="originalUrl(row.item)" target="_blank" rel="noreferrer" title="打开 Boss 直聘岗位详情页" @click.stop>
          <span class="origin-dot">B</span>
          <span class="origin-text">Boss 原岗位</span>
          <span class="origin-arrow">↗</span>
        </a>
        <button v-if="isFavoritesMode" type="button" class="favorite-job-btn" :disabled="job.isAnalyzingFavorite(row.item)" @click.stop="openAnalysisModal(row.item)">
          {{ job.isAnalyzingFavorite(row.item) ? '分析中' : (favoriteAnalysis(row.item) ? '查看分析' : '分析岗位') }}
        </button>
        <button type="button" class="favorite-job-btn" :class="{ active: job.isFavorite(row.item) }" @click.stop="job.toggleFavorite(row.item)">
          {{ job.isFavorite(row.item) ? '已收藏' : '收藏岗位' }}
        </button>
        <button v-if="isFavoritesMode" type="button" class="remove-job-btn" @click.stop="job.removeFavorite(row.item)">移出收藏</button>
      </div>
    </article>

    <div v-if="analysisModalVisible" class="modal-mask favorite-analysis-modal-mask" @click.self="closeAnalysisModal">
      <div class="modal-card favorite-analysis-modal-card">
        <button class="close" type="button" @click="closeAnalysisModal">×</button>
        <div class="favorite-analysis-modal-head">
          <p class="eyebrow">Favorite Job Analysis</p>
          <h2>{{ modalJobTitle }}</h2>
          <span>{{ modalCompanyText }} · {{ modalLocationText }}</span>
        </div>
        <div v-if="analysisModalPending" class="favorite-analysis-loading">
          <strong>正在分析岗位匹配度</strong>
          <p>请稍候，系统正在结合当前简历生成岗位分析结果。</p>
        </div>
        <div v-else-if="analysisModalError" class="favorite-analysis-error-box">
          <strong>岗位分析失败</strong>
          <p>{{ analysisModalError }}</p>
        </div>
        <div v-else-if="modalAnalysis" class="favorite-analysis-report">
          <div class="favorite-analysis-score-row">
            <b>{{ modalAnalysis.score || '已生成' }}{{ modalAnalysis.score ? ' 分' : '' }}</b>
            <span v-if="analysisTime(modalJob)">分析于 {{ analysisTime(modalJob) }}</span>
          </div>
          <p class="favorite-analysis-summary">{{ modalAnalysis.recommendation || modalAnalysis.reasoning || '已保存本岗位分析结果。' }}</p>
          <div v-if="modalAnalysis.hits?.length" class="analysis-list favorite-analysis-modal-list"><strong>优势</strong><em v-for="x in modalAnalysis.hits" :key="x">{{ x }}</em></div>
          <div v-if="modalAnalysis.gaps?.length" class="analysis-list favorite-analysis-modal-list"><strong>差距</strong><em v-for="x in modalAnalysis.gaps" :key="x">{{ x }}</em></div>
        </div>
        <div v-else class="favorite-analysis-empty-box">
          <strong>暂无岗位分析结果</strong>
          <p>点击下方按钮开始分析。成功或失败都会停留在此窗口中展示结果。</p>
        </div>
        <div class="modal-actions favorite-analysis-modal-actions">
          <button class="primary-btn" type="button" :disabled="analysisModalPending || !modalJob" @click="runFavoriteAnalysis(true)">{{ analysisModalPending ? '分析中' : (modalAnalysis ? '重新分析' : '开始分析') }}</button>
          <button class="secondary-btn" type="button" @click="closeAnalysisModal">关闭</button>
        </div>
      </div>
    </div>
  </section>
</template>
<script setup>
import { computed, ref } from 'vue'
import { useJobStore } from '../stores/job'
import { useResumeStore } from '../stores/resume'
import { useChatStore } from '../stores/chat'
import { compactJobSummaryText, firstJobDescriptionText, normalizeJobDescriptionText } from '../utils/jobText'

const props = defineProps({ mode: { type: String, default: 'workspace' } })
const job = useJobStore()
const resume = useResumeStore()
const chat = useChatStore()
const keyword = ref('')
const expandedJdKeys = ref(new Set())

const isFavoritesMode = computed(() => props.mode === 'favorites')
const analysisModalVisible = ref(false)
const analysisModalPending = ref(false)
const analysisModalError = ref('')
const modalJob = ref(null)
const sourceRows = computed(() => (isFavoritesMode.value ? job.favorites : job.jobs).map((item, sourceIndex) => ({ item, sourceIndex })))
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return sourceRows.value
  return sourceRows.value.filter(row => searchableText(row.item, row.sourceIndex).includes(q))
})
const panelTitle = computed(() => isFavoritesMode.value ? '岗位收藏' : '岗位工作台')
const panelSubtitle = computed(() => isFavoritesMode.value
  ? '这里只展示你手动标记收藏的岗位，支持按公司、岗位、城市、薪资、技术栈和风险关键词检索。'
  : '真实来自 Boss 推荐池，支持原始链接、AI 匹配评分和公司风险核验入口。')
const emptyTitle = computed(() => isFavoritesMode.value ? '暂无收藏岗位' : '暂无岗位数据')
const emptyText = computed(() => isFavoritesMode.value
  ? '请先在聊天推荐结果或岗位工作台中收藏岗位。'
  : '请先登录 Boss，再描述岗位条件。')
const searchPlaceholder = computed(() => isFavoritesMode.value ? '搜索已收藏岗位、公司、城市、薪资、标签' : '搜索当前岗位、公司、城市、薪资、标签')
const modalAnalysis = computed(() => favoriteAnalysis(modalJob.value))
const modalJobTitle = computed(() => title(modalJob.value || {}))
const modalCompanyText = computed(() => company(modalJob.value || {}))
const modalLocationText = computed(() => locationText(modalJob.value || {}))

function title(item) { return item.jobName || item.job_name || item.title || '未知岗位' }
function company(item) { return item.brandName || item.companyName || item.company || '未知公司' }
function asArray(value) { return Array.isArray(value) ? value : (value ? [value] : []) }
function tags(item) {
  return [...asArray(item.skills), ...asArray(item.skillList), ...asArray(item.jobLabels), item.brandIndustry, item.industry]
    .map(x => String(x || '').trim())
    .filter(x => x && !/^\d{4,}$/.test(x))
    .slice(0, 10)
}
function originalUrl(item) {
  const url = item.originalUrl || item.jobUrl || item.url || item.href || item.link || item.detailUrl || ''
  if (url && String(url).includes('/job_detail/')) return url
  return bossDetailUrl(item)
}
function bossDetailUrl(item) {
  const pathId = firstUsableJobPathId(item)
  if (!pathId) return ''
  const params = new URLSearchParams()
  const securityId = item.securityId || item.security_id || ''
  const lid = item.lid || item.listId || ''
  if (securityId) params.set('securityId', securityId)
  if (lid) params.set('lid', lid)
  const query = params.toString()
  return `https://www.zhipin.com/job_detail/${encodeURIComponent(pathId)}.html${query ? `?${query}` : ''}`
}
function firstUsableJobPathId(item) {
  for (const key of ['encryptJobId', 'encrypt_job_id', 'jobId', 'job_id', 'id']) {
    const value = String(item[key] || '').trim()
    if (value && !/^\d{4,}$/.test(value)) return value
  }
  return ''
}
function locationText(item) { return item.cityName || item.city || item.location || item.areaDistrict || '城市未标注' }
function experienceText(item) { return item.jobExperience || item.experience || '经验不限' }
function industryText(item) { return item.companyIndustry || item.brandIndustry || item.industry || item.industryName || '' }
function scaleText(item) { return item.companyScale || item.brandScaleName || item.scaleName || item.brandScale || '' }
function stageText(item) { return item.companyStage || item.brandStageName || item.financeStage || item.stageName || '' }
function bossText(item) { return [item.bossName, item.bossTitle || item.bossPosition].filter(Boolean).join(' · ') }
function salaryText(item) {
  const value = item.salaryDesc || item.salary_desc || item.salary || item.salaryText || item.salaryName || item.salaryRange || item.jobSalary || item.pay || item.wage || item.compensation || ''
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
  if (isJobDescriptionOpen(item)) return '收起职位描述'
  return hasLoadedJd(item) ? '查看职位描述' : '加载职位描述'
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
  return job.jobs.find(row => favoriteKey(row) === key) || job.favorites.find(row => favoriteKey(row) === key) || item
}
function welfare(item) { return asArray(item.welfareList || item.welfare || item.benefits).map(String).filter(Boolean).slice(0, 8) }
function favoriteTime(item) {
  const value = item?.favoritedAt || item?.favoriteAt || item?.createdAt || item?.updatedAt
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function jobId(item, idx) { return String(item.favoriteKey || item.securityId || item.id || item.jobId || item.encryptJobId || `job_${idx}`) }
function favoriteKey(item) { return String(item?.favoriteKey || item?.securityId || item?.id || item?.jobId || item?.encryptJobId || `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`) }
// 匹配与企业核验结果按 id/公司预建索引：模板对每张卡片会多次调用 matchOf/researchOf，
// 若每次都对结果数组做线性 find，单次列表渲染的复杂度是 O(卡片数 * 结果数 * 调用次数)。
// 这里把数组一次性收敛成 Map，命中缓存后查找降为 O(1)，且仅在 job.match 变化时重建，渲染语义不变。
const matchById = computed(() => {
  const map = new Map()
  for (const m of (job.match?.matches || [])) {
    const key = String(m?.id)
    if (key && !map.has(key)) map.set(key, m)
  }
  return map
})
const researchByJobId = computed(() => {
  const map = new Map()
  for (const row of (job.match?.companyResearch || [])) {
    const key = String(row?.jobId)
    if (key && !map.has(key)) map.set(key, row)
  }
  return map
})
const researchByCompany = computed(() => {
  const map = new Map()
  for (const row of (job.match?.companyResearch || [])) {
    if (row?.company != null && !map.has(row.company)) map.set(row.company, row)
  }
  return map
})
function matchOf(item, idx) {
  const matches = job.match?.matches || []
  const id = jobId(item, idx)
  return matchById.value.get(id) || matches[idx] || (item.matchScore ? {
    score: item.matchScore,
    recommendation: item.matchRecommendation,
    reasoning: item.matchReasoning,
    hits: item.hits,
    gaps: item.gaps,
  } : null)
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
  const value = item?.analyzedAt || item?.analysis?.analyzedAt
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function closeAnalysisModal() {
  if (analysisModalPending.value) return
  analysisModalVisible.value = false
  analysisModalError.value = ''
  modalJob.value = null
}
function latestFavorite(item) {
  const key = favoriteKey(item)
  return job.favorites.find(row => favoriteKey(row) === key) || item
}
async function openAnalysisModal(item) {
  modalJob.value = latestFavorite(item)
  analysisModalError.value = ''
  analysisModalVisible.value = true
  if (!favoriteAnalysis(modalJob.value)) await runFavoriteAnalysis(false)
}
async function runFavoriteAnalysis(force = false) {
  if (!modalJob.value || analysisModalPending.value) return
  if (!force && favoriteAnalysis(modalJob.value)) return
  analysisModalPending.value = true
  analysisModalError.value = ''
  try {
    const updated = await job.analyzeFavorite(modalJob.value, resume.current?.resumeId || '')
    modalJob.value = latestFavorite(updated || modalJob.value)
  } catch (error) {
    modalJob.value = latestFavorite(modalJob.value)
    if (error?.authRequired) {
      // 登录态失效：弹出扫码登录而非只展示通用错误，用户登录后可重新发起分析。
      chat.authRequired = error.authData || { message: error.message }
      analysisModalError.value = 'Boss 直聘登录态已失效，请扫码登录后重试分析。'
    } else {
      analysisModalError.value = error?.message || '岗位分析失败'
    }
  } finally {
    analysisModalPending.value = false
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
    title(item), company(item), locationText(item), salaryText(item),
    experienceText(item), item.jobDegree, item.education, industryText(item), scaleText(item), stageText(item), bossText(item), descriptionText(item), ...welfare(item),
    ...tags(item), match.score, match.recommendation, match.reasoning, ...(match.hits || []), ...(match.gaps || []),
    research.riskLevel, research.summary,
  ].filter(Boolean).join(' ').toLowerCase()
}
</script>
