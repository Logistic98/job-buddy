<template>
  <section class="system-page journey-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Job Journey</p>
        <h1>求职进展</h1>
        <p>维护投递记录、面试进展、复盘总结和后续动作，可选择关联岗位收藏，也可以作为独立进展记录。</p>
      </div>
      <div class="history-header-actions">
        <button class="secondary-btn" :disabled="loading || analyzing" @click="openAnalysisModal()">
          {{ analyzing ? '分析中' : analysisButtonText }}
        </button>
        <button class="primary-btn" @click="openCreateModal">新增进展</button>
      </div>
    </header>

    <p v-if="error" class="error settings-error">{{ error }}</p>

    <section class="journey-list glass-card journey-full-list">
      <div class="journey-toolbar-flat">
        <div class="journey-filter-row">
          <label class="history-search"
            ><span>搜索</span
            ><input
              v-model.trim="filters.keyword"
              placeholder="公司、岗位、业务方向、面试内容"
              @keyup.enter="searchRecords"
          /></label>
          <div class="inline-controls journey-filter-actions">
            <select v-model="filters.status" @change="searchRecords">
              <option value="">全部状态</option>
              <option v-for="item in statuses" :key="item" :value="item">{{ item }}</option>
            </select>
            <select v-model="filters.result" @change="searchRecords">
              <option value="">全部结果</option>
              <option v-for="item in results" :key="item" :value="item">{{ item }}</option>
            </select>
            <button class="secondary-btn" @click="toggleSelectAll">
              {{ allVisibleSelected ? '取消全选' : '全选当前' }}
            </button>
            <button class="secondary-btn" @click="searchRecords">查询</button>
          </div>
        </div>
      </div>

      <div class="journey-table grouped-journey-table">
        <div class="journey-table-head">
          <span class="select-col"
            ><input
              type="checkbox"
              :checked="allVisibleSelected"
              :disabled="!pagedRecords.length"
              @change="toggleSelectAll"
          /></span>
          <span>企业 / 岗位</span><span>地域</span><span>业务方向</span><span>轮次</span><span>时间</span
          ><span>结果</span><span>操作</span>
        </div>
        <template v-for="group in groupedRecords" :key="group.key">
          <button
            v-if="group.items.length > 1 || group.favoriteKey"
            type="button"
            class="journey-group-row"
            @click="toggleGroup(group.key)"
          >
            <span>{{ collapsedGroups[group.key] ? '展开' : '折叠' }}</span>
            <strong>{{ group.title }}</strong>
            <em>{{ group.items.length }} 条进展</em>
          </button>
          <article v-for="item in visibleGroupItems(group)" :key="item.recordId" class="journey-table-row">
            <span class="select-col"
              ><input
                type="checkbox"
                :checked="isSelected(item.recordId)"
                @change="toggleRecordSelection(item.recordId)"
            /></span>
            <div>
              <strong>{{ item.company }}</strong
              ><small>{{ item.positionName || '未填写岗位' }}</small>
            </div>
            <span>{{ item.city || '-' }}</span>
            <span>{{ item.businessDirection || '-' }}</span>
            <span>{{ item.interviewRound || '-' }}</span>
            <span>{{ formatJourneyDateTime(item.interviewTime) || '-' }}</span>
            <span
              ><b :class="['state-badge', 'journey-result-badge', journeyResultClass(item.result)]">{{
                item.result || '待定'
              }}</b></span
            >
            <div class="row-actions">
              <button class="secondary-btn" @click="openViewModal(item)">查看</button>
              <button class="secondary-btn" @click="openEditModal(item)">编辑</button>
              <button class="danger-btn" @click="removeRecord(item.recordId)">删除</button>
            </div>
          </article>
        </template>
      </div>

      <div v-if="!records.length" class="empty-state">
        <strong>暂无求职记录</strong>
        <p>点击右上角“新增进展”录入企业和面试情况。</p>
      </div>

      <div v-if="records.length" class="bank-pagination journey-pagination">
        <span
          >第 {{ currentPage }} / {{ totalPages }} 页，共 {{ records.length }} 条<span v-if="selectedRecordIds.length"
            >，已选 {{ selectedRecordIds.length }} 条</span
          ></span
        >
        <div>
          <select v-model.number="pageSize" aria-label="每页进展数量" @change="changePageSize">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
          <button class="secondary-btn" :disabled="currentPage <= 1" @click="goPage(currentPage - 1)">上一页</button>
          <button
            v-for="page in visiblePages"
            :key="page"
            :class="['page-num-btn', { active: page === currentPage }]"
            @click="goPage(page)"
          >
            {{ page }}
          </button>
          <button class="secondary-btn" :disabled="currentPage >= totalPages" @click="goPage(currentPage + 1)">
            下一页
          </button>
        </div>
      </div>
    </section>

    <div v-if="analysisModalVisible" class="modal-mask journey-analysis-modal-mask" @click.self="closeAnalysisModal">
      <div
        class="journey-analysis-modal glass-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="journey-analysis-title"
      >
        <header class="analysis-head journey-analysis-header">
          <div>
            <p class="eyebrow">AI Advice</p>
            <h2 id="journey-analysis-title">求职进展分析</h2>
            <span>本次分析范围：{{ analysisScopeText }}</span>
          </div>
          <button
            type="button"
            class="close"
            aria-label="关闭求职进展分析"
            :disabled="analyzing"
            @click="closeAnalysisModal"
          >
            ×
          </button>
        </header>
        <div :class="['journey-analysis-body', { 'is-loading': analyzing }]">
          <div v-if="analyzing" class="favorite-analysis-loading">
            <strong>正在分析求职进展</strong>
            <p>系统正在根据所选记录统计漏斗、风险和下一步建议。</p>
          </div>
          <div v-else-if="analysis" class="journey-analysis-card journey-analysis-inside">
            <p class="analysis-summary">{{ analysis.summary }}</p>
            <section class="analysis-score-overview" aria-label="求职健康度评分">
              <div class="analysis-total-score">
                <span>综合健康度</span>
                <strong>{{ analysis.metrics?.score ?? '-' }}<small>分</small></strong>
                <p>综合推进状态、结果转化、跟进和记录质量</p>
              </div>
              <div class="analysis-score-groups">
                <article v-for="item in analysis.scoreGroups || []" :key="item.key" class="analysis-score-group">
                  <div>
                    <strong>{{ item.label }}</strong
                    ><span>{{ item.score ?? 0 }} 分</span>
                  </div>
                  <div
                    class="analysis-score-track"
                    role="progressbar"
                    :aria-label="item.label"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="item.score ?? 0"
                  >
                    <span :style="{ width: `${item.score ?? 0}%` }"></span>
                  </div>
                  <p>{{ item.description }}</p>
                </article>
              </div>
            </section>
            <div class="analysis-metrics">
              <div>
                <strong>{{ analysis.metrics?.total ?? 0 }}</strong
                ><span>分析记录</span>
              </div>
              <div>
                <strong>{{ analysis.metrics?.active ?? 0 }}</strong
                ><span>推进中</span>
              </div>
              <div>
                <strong>{{ analysis.metrics?.passed ?? 0 }}</strong
                ><span>通过</span>
              </div>
              <div>
                <strong>{{ analysis.metrics?.pending ?? 0 }}</strong
                ><span>待反馈</span>
              </div>
            </div>
            <div class="analysis-grid">
              <div class="analysis-block">
                <h3>优势信号</h3>
                <ul>
                  <li v-for="item in analysis.strengths" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div class="analysis-block">
                <h3>风险提醒</h3>
                <ul>
                  <li v-for="item in analysis.risks" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div class="analysis-block">
                <h3>下一步建议</h3>
                <ul>
                  <li v-for="item in analysis.nextActions" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div class="analysis-block">
                <h3>准备节奏</h3>
                <ul>
                  <li v-for="item in analysis.preparationPlan" :key="item">{{ item }}</li>
                </ul>
              </div>
            </div>
            <div class="followup-box">
              <span>跟进话术</span>
              <p>{{ analysis.followUpMessage }}</p>
              <button class="secondary-btn" @click="copyFollowUp">复制</button>
            </div>
          </div>
        </div>
        <footer class="detail-actions modal-actions-right journey-analysis-actions">
          <button class="secondary-btn" :disabled="analyzing" @click="closeAnalysisModal">关闭</button>
          <button class="primary-btn" :disabled="analyzing" @click="runAnalysis()">
            {{ analyzing ? '分析中' : '重新分析' }}
          </button>
        </footer>
      </div>
    </div>

    <div v-if="deleteDialog.visible" class="modal-mask journey-delete-mask" @click.self="closeDeleteDialog">
      <div class="journey-delete-modal">
        <button class="close" @click="closeDeleteDialog">×</button>
        <p class="eyebrow">删除进展</p>
        <h2>删除这条求职进展？</h2>
        <p>确认删除「{{ deleteDialog.title || '未命名记录' }}」？</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="deletingRecord" @click="closeDeleteDialog">取消</button>
          <button class="danger-btn" :disabled="deletingRecord" @click="confirmDeleteRecord">
            {{ deletingRecord ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="modalVisible" class="modal-mask journey-record-modal-mask">
      <div class="journey-modal glass-card">
        <button class="close" @click="closeModal">×</button>
        <div class="journey-modal-head">
          <div>
            <h2>{{ modalMode === 'view' ? '查看求职进展' : editing.recordId ? '编辑求职进展' : '新增求职进展' }}</h2>
            <span>{{ editing.recordId ? '已保存记录' : '新记录' }}</span>
          </div>
        </div>
        <datalist id="journey-company-options">
          <option>字节跳动</option>
          <option>腾讯</option>
          <option>阿里巴巴</option>
          <option>美团</option>
          <option>小红书</option>
          <option>米哈游</option>
          <option>蚂蚁集团</option>
          <option>百度</option>
        </datalist>
        <datalist id="journey-company-nature-options">
          <option>互联网大厂</option>
          <option>私企</option>
          <option>量化金融</option>
          <option>AI 创业公司</option>
          <option>外企</option>
          <option>国企</option>
        </datalist>
        <datalist id="journey-company-scale-options">
          <option>大型</option>
          <option>中型</option>
          <option>小型</option>
          <option>不限</option>
        </datalist>
        <datalist id="journey-interview-round-options">
          <option>投递</option>
          <option>HR 初筛</option>
          <option>笔试</option>
          <option>一面</option>
          <option>二面</option>
          <option>三面</option>
          <option>终面</option>
          <option>Offer 沟通</option>
        </datalist>
        <datalist id="journey-interview-format-options">
          <option>线上</option>
          <option>线下</option>
          <option>电话</option>
          <option>笔试</option>
          <option>线上 / 线下</option>
        </datalist>
        <datalist id="journey-result-options">
          <option>跟进中</option>
          <option>通过</option>
          <option>未通过</option>
          <option>待反馈</option>
          <option>已放弃</option>
        </datalist>
        <datalist id="journey-status-options">
          <option>投递中</option>
          <option>笔试</option>
          <option>面试进展</option>
          <option>Offer</option>
          <option>结束</option>
        </datalist>
        <datalist id="journey-priority-options">
          <option>高</option>
          <option>中</option>
          <option>低</option>
        </datalist>
        <nav class="journey-modal-groups" aria-label="求职进展信息分组">
          <button
            v-for="group in modalGroups"
            :key="group.key"
            type="button"
            :class="{ active: activeModalGroup === group.key }"
            @click="activeModalGroup = group.key"
          >
            <strong>{{ group.label }}</strong>
            <span>{{ group.description }}</span>
          </button>
        </nav>
        <div v-if="modalMode === 'view'" class="journey-view journey-group-view">
          <div v-for="item in activeViewRows" :key="item.label" class="journey-view-item">
            <span>{{ item.label }}</span>
            <p>{{ item.value || '-' }}</p>
          </div>
        </div>
        <div v-else class="form-grid compact-form journey-group-form">
          <template v-if="activeModalGroup === 'company'">
            <label
              ><span>企业</span
              ><input
                v-model="form.company"
                list="journey-company-options"
                placeholder="请输入企业名称，例如：字节跳动"
            /></label>
            <label><span>地域</span><input v-model="form.city" placeholder="请输入所在地域，例如：上海" /></label>
            <label
              ><span>性质</span
              ><input
                v-model="form.companyNature"
                list="journey-company-nature-options"
                placeholder="请选择或输入企业性质"
            /></label>
            <label
              ><span>规模</span
              ><input
                v-model="form.companyScale"
                list="journey-company-scale-options"
                placeholder="请选择或输入企业规模"
            /></label>
            <label
              ><span>岗位</span
              ><input v-model="form.positionName" placeholder="请输入岗位名称，例如：Agent 与大模型应用开发工程师"
            /></label>
            <label
              ><span>薪资</span><input v-model="form.salaryRange" placeholder="请输入薪资范围，例如：40-50k"
            /></label>
            <label class="wide"
              ><span>关联岗位收藏（可选）</span
              ><select v-model="form.favoriteKey" :class="{ 'is-placeholder': !form.favoriteKey }">
                <option value="">不关联岗位收藏</option>
                <option v-for="item in favoriteOptions" :key="item.key" :value="item.key">{{ item.title }}</option>
              </select></label
            >
            <label class="wide"
              ><span>业务方向</span
              ><input v-model="form.businessDirection" placeholder="请输入业务方向，例如：大模型应用 / Agent 平台"
            /></label>
          </template>
          <template v-else-if="activeModalGroup === 'interview'">
            <label
              ><span>类型 / 轮次</span
              ><input
                v-model="form.interviewRound"
                list="journey-interview-round-options"
                placeholder="请选择或输入类型 / 轮次"
            /></label>
            <label
              ><span>面试时间</span
              ><input
                v-model="form.interviewTime"
                type="datetime-local"
                :class="{ 'is-placeholder': !form.interviewTime }"
                aria-label="请选择面试时间"
            /></label>
            <label
              ><span>形式</span
              ><input
                v-model="form.interviewFormat"
                list="journey-interview-format-options"
                placeholder="请选择或输入面试形式"
            /></label>
            <label
              ><span>结果</span
              ><input v-model="form.result" list="journey-result-options" placeholder="请选择或输入面试结果"
            /></label>
            <label
              ><span>状态</span
              ><input v-model="form.status" list="journey-status-options" placeholder="请选择或输入当前状态"
            /></label>
            <label
              ><span>优先级</span
              ><input v-model="form.priority" list="journey-priority-options" placeholder="请选择或输入优先级"
            /></label>
          </template>
          <template v-else-if="activeModalGroup === 'notes'">
            <label class="wide"
              ><span>面试内容</span
              ><textarea v-model="form.interviewContent" rows="4" placeholder="请输入面试或笔试内容、题型和过程" />
            </label>
            <label
              ><span>岗位 JD</span
              ><textarea v-model="form.jobDescription" rows="4" placeholder="请输入岗位职责、任职要求和技术栈" />
            </label>
            <label
              ><span>面试流程</span
              ><textarea v-model="form.interviewProcess" rows="4" placeholder="请输入面试流程、笔试范围和后续安排" />
            </label>
          </template>
          <template v-else>
            <label class="wide"
              ><span>反思总结</span
              ><textarea v-model="form.reflection" rows="4" placeholder="请输入准备情况、暴露问题和后续改进方向" />
            </label>
            <label class="wide"
              ><span>下一步动作</span
              ><textarea
                v-model="form.nextAction"
                rows="3"
                placeholder="请输入下一步动作，例如：补充 Agent 题库并安排复习"
              />
            </label>
            <div class="wide journey-tag-editor">
              <span class="journey-tag-field-label">标签</span>
              <div v-if="form.tags.length" class="journey-tag-list" aria-label="已添加的求职进展标签">
                <span v-for="tag in form.tags" :key="tag">
                  {{ tag }}
                  <button type="button" :aria-label="`移除标签 ${tag}`" @click="removeTag(tag)">×</button>
                </span>
              </div>
              <div class="journey-tag-input-row">
                <input
                  v-model.trim="tagDraft"
                  maxlength="64"
                  placeholder="输入一个标签，例如：重点跟进"
                  @keydown.enter.prevent="addTag"
                />
                <button type="button" :disabled="!tagDraft.trim()" @click="addTag">添加标签</button>
              </div>
              <small class="field-hint">每次输入一个标签，按回车或点击按钮添加</small>
            </div>
          </template>
        </div>
        <p
          v-if="modalMode !== 'view' && error"
          class="error settings-error form-error-alert"
          role="alert"
          aria-live="assertive"
        >
          {{ error }}
        </p>
        <div class="detail-actions modal-actions-right">
          <button v-if="modalMode === 'view'" class="primary-btn" @click="modalMode = 'edit'">编辑</button>
          <button v-else class="primary-btn" :disabled="savingRecord" @click="saveRecord">
            {{ savingRecord ? '保存中' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  analyzeJourneyProgress,
  createJourneyRecord,
  deleteJourneyRecord,
  listJourneyRecords,
  updateJourneyRecord,
} from '../api/journey'
import { useJobStore } from '../stores/job'
import { formatJourneyDateTime, toJourneyDateTimeLocalValue } from '../utils/journeyDateTime'
import { validateLength, validateTags } from '../utils/formValidation'
import { journeyResultClass } from '../utils/journeyResult'

const job = useJobStore()
const loading = ref(false)
const analyzing = ref(false)
const savingRecord = ref(false)
const deletingRecord = ref(false)
const modalVisible = ref(false)
const analysisModalVisible = ref(false)
const modalMode = ref('edit')
const activeModalGroup = ref('company')
const tagDraft = ref('')
const error = ref('')
const records = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const analysis = ref(null)
const selectedRecordIds = ref([])
const collapsedGroups = reactive({})
const filters = reactive({ keyword: '', status: '', result: '' })
const editing = reactive({ recordId: '' })
const deleteDialog = reactive({ visible: false, recordId: '', title: '' })
const form = reactive(emptyForm())
const statuses = computed(() => Array.from(new Set(records.value.map((item) => item.status).filter(Boolean))).sort())
const results = computed(() => Array.from(new Set(records.value.map((item) => item.result).filter(Boolean))).sort())
const favoriteOptions = computed(() =>
  job.favorites.map((item) => ({ key: jobKey(item), title: jobTitle(item) })).filter((item) => item.key),
)
const favoriteMap = computed(() => new Map(favoriteOptions.value.map((item) => [item.key, item.title])))
const totalPages = computed(() => Math.max(1, Math.ceil(records.value.length / pageSize.value)))
const pagedRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return records.value.slice(start, start + pageSize.value)
})
const visiblePages = computed(() => {
  const start = Math.max(1, Math.min(currentPage.value - 2, totalPages.value - 4))
  const end = Math.min(totalPages.value, start + 4)
  return Array.from({ length: end - start + 1 }, (_, index) => start + index)
})
const allVisibleIds = computed(() => pagedRecords.value.map((item) => item.recordId).filter(Boolean))
const allVisibleSelected = computed(
  () => allVisibleIds.value.length > 0 && allVisibleIds.value.every((id) => selectedRecordIds.value.includes(id)),
)
const analysisButtonText = computed(() =>
  selectedRecordIds.value.length ? `分析选中 ${selectedRecordIds.value.length} 条` : 'AI 分析全部',
)
const analysisScopeText = computed(() =>
  selectedRecordIds.value.length
    ? `选中的 ${selectedRecordIds.value.length} 条进展`
    : `当前筛选下全部 ${records.value.length} 条进展`,
)
const groupedRecords = computed(() => {
  const groups = []
  const index = new Map()
  for (const item of pagedRecords.value) {
    const favoriteKey = item.favoriteKey || ''
    const key = favoriteKey ? `favorite:${favoriteKey}` : `single:${item.recordId}`
    if (!index.has(key)) {
      const group = {
        key,
        favoriteKey,
        title: favoriteKey
          ? favoriteTitle(favoriteKey) || '已关联岗位收藏'
          : `${item.company || '未命名企业'} / ${item.positionName || '未填写岗位'}`,
        items: [],
      }
      index.set(key, group)
      groups.push(group)
    }
    index.get(key).items.push(item)
  }
  return groups
})
const modalGroups = [
  { key: 'company', label: '企业岗位', description: '企业与岗位信息' },
  { key: 'interview', label: '面试安排', description: '轮次与当前状态' },
  { key: 'notes', label: '面试记录', description: '内容、JD 与流程' },
  { key: 'followup', label: '复盘跟进', description: '总结与下一步' },
]
const viewRowsByGroup = computed(() => ({
  company: [
    ['企业', form.company],
    ['地域', form.city],
    ['性质', form.companyNature],
    ['规模', form.companyScale],
    ['岗位', form.positionName],
    ['薪资', form.salaryRange],
    ['关联岗位收藏', favoriteTitle(form.favoriteKey) || '未关联'],
    ['业务方向', form.businessDirection],
  ],
  interview: [
    ['类型 / 轮次', form.interviewRound],
    ['面试时间', formatJourneyDateTime(form.interviewTime)],
    ['形式', form.interviewFormat],
    ['结果', form.result],
    ['状态', form.status],
    ['优先级', form.priority],
  ],
  notes: [
    ['面试内容', form.interviewContent],
    ['岗位 JD', form.jobDescription],
    ['面试流程', form.interviewProcess],
  ],
  followup: [
    ['反思总结', form.reflection],
    ['下一步动作', form.nextAction],
    ['标签', form.tags.join('、')],
  ],
}))
const activeViewRows = computed(() =>
  (viewRowsByGroup.value[activeModalGroup.value] || []).map(([label, value]) => ({ label, value })),
)

onMounted(async () => {
  await Promise.all([loadRecords(), job.loadFavorites()])
})

function emptyForm() {
  return {
    company: '',
    city: '',
    companyNature: '',
    companyScale: '',
    positionName: '',
    salaryRange: '',
    favoriteKey: '',
    businessDirection: '',
    interviewRound: '',
    interviewTime: '',
    interviewContent: '',
    interviewFormat: '',
    result: '',
    reflection: '',
    jobDescription: '',
    interviewProcess: '',
    nextAction: '',
    status: '',
    priority: '',
    tags: [],
  }
}
function jobKey(item) {
  return String(
    item?.favoriteKey ||
      item?.jobKey ||
      item?.securityId ||
      item?.id ||
      item?.jobId ||
      item?.encryptJobId ||
      `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`,
  )
}
function jobTitle(item) {
  const name = item?.jobName || item?.title || item?.positionName || '未命名岗位'
  const company = item?.brandName || item?.companyName || item?.company || '未填写公司'
  return `${company} / ${name}`
}
function favoriteTitle(favoriteKey) {
  return favoriteKey ? favoriteMap.value.get(favoriteKey) || favoriteKey : ''
}
async function loadRecords() {
  loading.value = true
  error.value = ''
  try {
    records.value = await listJourneyRecords(filters)
    currentPage.value = Math.min(currentPage.value, totalPages.value)
    selectedRecordIds.value = selectedRecordIds.value.filter((id) => records.value.some((item) => item.recordId === id))
  } catch (err) {
    error.value = err.message || '求职进展加载失败'
  } finally {
    loading.value = false
  }
}
function searchRecords() {
  currentPage.value = 1
  return loadRecords()
}
function goPage(page) {
  currentPage.value = Math.max(1, Math.min(page, totalPages.value))
}
function changePageSize() {
  currentPage.value = 1
}
function openCreateModal() {
  editing.recordId = ''
  tagDraft.value = ''
  Object.assign(form, emptyForm())
  activeModalGroup.value = 'company'
  modalMode.value = 'edit'
  modalVisible.value = true
}
function openViewModal(item) {
  fillForm(item)
  activeModalGroup.value = 'company'
  modalMode.value = 'view'
  modalVisible.value = true
}
function openEditModal(item) {
  fillForm(item)
  activeModalGroup.value = 'company'
  modalMode.value = 'edit'
  modalVisible.value = true
}
function fillForm(item) {
  editing.recordId = item.recordId
  tagDraft.value = ''
  Object.assign(form, emptyForm(), item, {
    interviewTime: toJourneyDateTimeLocalValue(item.interviewTime),
    tags: normalizeTags(item.tags),
  })
  for (const key of Object.keys(emptyForm())) {
    if (form[key] == null) form[key] = ''
  }
}
function closeModal() {
  modalVisible.value = false
}
function normalizeTags(tags) {
  if (!Array.isArray(tags)) return []
  const labels = tags.map((tag) => String(tag?.label || tag || '').trim()).filter(Boolean)
  return labels.filter((tag, index) => labels.findIndex((item) => item.toLowerCase() === tag.toLowerCase()) === index)
}
function addTag() {
  const tag = tagDraft.value.trim()
  if (!tag) return
  if (!form.tags.some((item) => item.toLowerCase() === tag.toLowerCase())) form.tags.push(tag)
  tagDraft.value = ''
}
function removeTag(tag) {
  form.tags = form.tags.filter((item) => item !== tag)
}
async function saveRecord() {
  addTag()
  error.value = ''
  try {
    validateLength(form.company, '企业名称', { max: 120 })
    validateLength(form.positionName, '岗位名称', { max: 120 })
    for (const [key, label, max] of [
      ['city', '地域', 64],
      ['companyNature', '企业性质', 64],
      ['companyScale', '企业规模', 64],
      ['salaryRange', '薪资范围', 64],
      ['businessDirection', '业务方向', 200],
      ['interviewRound', '类型或轮次', 64],
      ['interviewFormat', '面试形式', 64],
      ['result', '面试结果', 64],
      ['status', '当前状态', 64],
      ['priority', '优先级', 32],
      ['interviewContent', '面试内容', 5000],
      ['jobDescription', '岗位 JD', 10000],
      ['interviewProcess', '面试流程', 5000],
      ['reflection', '反思总结', 5000],
      ['nextAction', '下一步动作', 2000],
    ])
      validateLength(form[key], label, { max })
    if (form.interviewTime && Number.isNaN(new Date(form.interviewTime).getTime()))
      throw new Error('面试时间格式不正确')
    validateTags(form.tags, '标签', { maxCount: 20, maxLength: 64 })
  } catch (err) {
    error.value = err.message
    return
  }
  savingRecord.value = true
  const payload = { ...form, tags: [...form.tags] }
  try {
    const saved = editing.recordId
      ? await updateJourneyRecord(editing.recordId, payload)
      : await createJourneyRecord(payload)
    editing.recordId = saved.recordId
    await loadRecords()
    modalVisible.value = false
  } catch (err) {
    error.value = err.message || '保存进展失败'
  } finally {
    savingRecord.value = false
  }
}
function removeRecord(recordId) {
  const item = records.value.find((row) => row.recordId === recordId)
  Object.assign(deleteDialog, {
    visible: true,
    recordId,
    title: item ? `${item.company || '未命名企业'} / ${item.positionName || '未填写岗位'}` : '',
  })
}
function closeDeleteDialog() {
  if (deletingRecord.value) return
  Object.assign(deleteDialog, { visible: false, recordId: '', title: '' })
}
async function confirmDeleteRecord() {
  if (!deleteDialog.recordId) return
  deletingRecord.value = true
  error.value = ''
  try {
    await deleteJourneyRecord(deleteDialog.recordId)
    selectedRecordIds.value = selectedRecordIds.value.filter((id) => id !== deleteDialog.recordId)
    Object.assign(deleteDialog, { visible: false, recordId: '', title: '' })
    await loadRecords()
  } catch (err) {
    error.value = err.message || '删除进展失败'
  } finally {
    deletingRecord.value = false
  }
}
function isSelected(recordId) {
  return selectedRecordIds.value.includes(recordId)
}
function toggleRecordSelection(recordId) {
  if (!recordId) return
  selectedRecordIds.value = isSelected(recordId)
    ? selectedRecordIds.value.filter((id) => id !== recordId)
    : [...selectedRecordIds.value, recordId]
}
function toggleSelectAll() {
  const selected = new Set(selectedRecordIds.value)
  for (const id of allVisibleIds.value) {
    if (allVisibleSelected.value) selected.delete(id)
    else selected.add(id)
  }
  selectedRecordIds.value = Array.from(selected)
}
function toggleGroup(key) {
  collapsedGroups[key] = !collapsedGroups[key]
}
function visibleGroupItems(group) {
  return collapsedGroups[group.key] ? [] : group.items
}
function openAnalysisModal() {
  analysisModalVisible.value = true
  runAnalysis()
}
function closeAnalysisModal() {
  if (!analyzing.value) analysisModalVisible.value = false
}
async function runAnalysis() {
  analyzing.value = true
  error.value = ''
  try {
    const payload = selectedRecordIds.value.length ? { recordIds: selectedRecordIds.value } : {}
    analysis.value = await analyzeJourneyProgress(payload)
  } catch (err) {
    error.value = err.message || '面试进展分析失败'
  } finally {
    analyzing.value = false
  }
}
async function copyFollowUp() {
  const text = analysis.value?.followUpMessage || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch (_) {}
}
</script>
