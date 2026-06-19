<template>
  <section class="system-page journey-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Job Journey</p>
        <h1>求职进展</h1>
        <p>维护投递记录、面试进展、复盘总结和后续动作，可选择关联岗位收藏，也可以作为独立进展记录。</p>
      </div>
      <div class="history-header-actions">
        <button class="secondary-btn" :disabled="loading || analyzing" @click="openAnalysisModal()">{{ analyzing ? '分析中' : analysisButtonText }}</button>
        <button class="secondary-btn" :disabled="loading" @click="loadRecords">刷新</button>
        <button class="primary-btn" @click="openCreateModal">新增进展</button>
      </div>
    </header>

    <p v-if="error" class="error settings-error">{{ error }}</p>

    <section class="journey-list glass-card journey-full-list">
      <div class="journey-toolbar-flat">
        <div class="journey-toolbar-title"><h2>面试进展</h2><span>{{ records.length }} 条，已选 {{ selectedRecordIds.length }} 条</span></div>
        <div class="journey-filter-row">
          <label class="history-search"><span>搜索</span><input v-model.trim="filters.keyword" placeholder="公司、岗位、业务方向、面试内容" @keyup.enter="loadRecords" /></label>
          <div class="inline-controls journey-filter-actions">
            <select v-model="filters.status" @change="loadRecords"><option value="">全部状态</option><option v-for="item in statuses" :key="item" :value="item">{{ item }}</option></select>
            <select v-model="filters.result" @change="loadRecords"><option value="">全部结果</option><option v-for="item in results" :key="item" :value="item">{{ item }}</option></select>
            <button class="secondary-btn" @click="toggleSelectAll">{{ allVisibleSelected ? '取消全选' : '全选当前' }}</button>
            <button class="secondary-btn" @click="loadRecords">查询</button>
          </div>
        </div>
      </div>

      <div class="journey-table grouped-journey-table">
        <div class="journey-table-head">
          <span class="select-col"><input type="checkbox" :checked="allVisibleSelected" :disabled="!records.length" @change="toggleSelectAll" /></span>
          <span>企业 / 岗位</span><span>关联岗位</span><span>地域</span><span>业务方向</span><span>轮次</span><span>时间</span><span>结果</span><span>操作</span>
        </div>
        <template v-for="group in groupedRecords" :key="group.key">
          <button v-if="group.items.length > 1 || group.favoriteKey" type="button" class="journey-group-row" @click="toggleGroup(group.key)">
            <span>{{ collapsedGroups[group.key] ? '展开' : '折叠' }}</span>
            <strong>{{ group.title }}</strong>
            <em>{{ group.items.length }} 条进展</em>
          </button>
          <article v-for="item in visibleGroupItems(group)" :key="item.recordId" class="journey-table-row">
            <span class="select-col"><input type="checkbox" :checked="isSelected(item.recordId)" @change="toggleRecordSelection(item.recordId)" /></span>
            <div><strong>{{ item.company }}</strong><small>{{ item.positionName || '未填写岗位' }}</small></div>
            <span>{{ favoriteTitle(item.favoriteKey) || '未关联' }}</span>
            <span>{{ item.city || '-' }}</span>
            <span>{{ item.businessDirection || '-' }}</span>
            <span>{{ item.interviewRound || '-' }}</span>
            <span>{{ item.interviewTime || '-' }}</span>
            <span><b class="state-badge">{{ item.result || '待定' }}</b></span>
            <div class="row-actions">
              <button class="secondary-btn" @click="openViewModal(item)">查看</button>
              <button class="secondary-btn" @click="openEditModal(item)">编辑</button>
              <button class="danger-btn" @click="removeRecord(item.recordId)">删除</button>
            </div>
          </article>
        </template>
      </div>

      <div v-if="!records.length" class="empty-state"><strong>暂无求职记录</strong><p>点击右上角“新增进展”录入企业和面试情况。</p></div>
    </section>

    <div v-if="analysisModalVisible" class="modal-mask journey-analysis-modal-mask" @click.self="closeAnalysisModal">
      <div class="journey-analysis-modal glass-card">
        <button class="close" @click="closeAnalysisModal">×</button>
        <div class="analysis-head">
          <div>
            <p class="eyebrow">AI Advice</p>
            <h2>求职进展分析</h2>
            <span>本次分析范围：{{ analysisScopeText }}</span>
          </div>
        </div>
        <div v-if="analyzing" class="favorite-analysis-loading"><strong>正在分析求职进展</strong><p>系统正在根据所选记录统计漏斗、风险和下一步建议。</p></div>
        <div v-else-if="analysis" class="journey-analysis-card journey-analysis-inside">
          <p class="analysis-summary">{{ analysis.summary }}</p>
          <div class="analysis-metrics">
            <div><strong>{{ analysis.metrics?.score || '-' }}</strong><span>健康度</span></div>
            <div><strong>{{ analysis.metrics?.active || 0 }}</strong><span>推进中</span></div>
            <div><strong>{{ analysis.metrics?.passed || 0 }}</strong><span>通过</span></div>
            <div><strong>{{ analysis.metrics?.pending || 0 }}</strong><span>待反馈</span></div>
          </div>
          <div class="analysis-grid">
            <div class="analysis-block"><h3>优势信号</h3><ul><li v-for="item in analysis.strengths" :key="item">{{ item }}</li></ul></div>
            <div class="analysis-block"><h3>风险提醒</h3><ul><li v-for="item in analysis.risks" :key="item">{{ item }}</li></ul></div>
            <div class="analysis-block"><h3>下一步建议</h3><ul><li v-for="item in analysis.nextActions" :key="item">{{ item }}</li></ul></div>
            <div class="analysis-block"><h3>准备节奏</h3><ul><li v-for="item in analysis.preparationPlan" :key="item">{{ item }}</li></ul></div>
          </div>
          <div class="followup-box">
            <span>跟进话术</span>
            <p>{{ analysis.followUpMessage }}</p>
            <button class="secondary-btn" @click="copyFollowUp">复制</button>
          </div>
        </div>
        <div class="detail-actions modal-actions-right">
          <button class="secondary-btn" @click="closeAnalysisModal">关闭</button>
          <button class="primary-btn" :disabled="analyzing" @click="runAnalysis()">{{ analyzing ? '分析中' : '重新分析' }}</button>
        </div>
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
          <button class="danger-btn" :disabled="deletingRecord" @click="confirmDeleteRecord">{{ deletingRecord ? '删除中' : '确认删除' }}</button>
        </div>
      </div>
    </div>

    <div v-if="modalVisible" class="modal-mask">
      <div class="journey-modal glass-card">
        <button class="close" @click="closeModal">×</button>
        <div class="journey-modal-head">
          <div><h2>{{ modalMode === 'view' ? '查看求职进展' : (editing.recordId ? '编辑求职进展' : '新增求职进展') }}</h2><span>{{ editing.recordId ? '已保存记录' : '新记录' }}</span></div>
        </div>
        <datalist id="journey-company-options"><option>字节跳动</option><option>腾讯</option><option>阿里巴巴</option><option>美团</option><option>小红书</option><option>米哈游</option><option>蚂蚁集团</option><option>百度</option></datalist>
        <div v-if="modalMode === 'view'" class="journey-view modal-form-scroll">
          <div v-for="item in viewRows" :key="item.label" class="journey-view-item"><span>{{ item.label }}</span><p>{{ item.value || '-' }}</p></div>
        </div>
        <div v-else class="form-grid compact-form modal-form-scroll">
          <label><span>企业</span><input v-model="form.company" list="journey-company-options" placeholder="例如：字节跳动 / 腾讯 / 阿里巴巴" /></label>
          <label><span>地域</span><input v-model="form.city" placeholder="上海" /></label>
          <label><span>性质</span><select v-model="form.companyNature"><option>互联网大厂</option><option>私企</option><option>量化金融</option><option>AI创业公司</option><option>外企</option><option>国企</option></select></label>
          <label><span>规模</span><select v-model="form.companyScale"><option>大型</option><option>中型</option><option>小型</option><option>不限</option></select></label>
          <label><span>岗位</span><input v-model="form.positionName" placeholder="AI Agent 开发工程师" /></label>
          <label><span>薪资</span><input v-model="form.salaryRange" placeholder="40-65k" /></label>
          <label class="wide"><span>关联岗位收藏（可选）</span><select v-model="form.favoriteKey"><option value="">不关联岗位收藏</option><option v-for="item in favoriteOptions" :key="item.key" :value="item.key">{{ item.title }}</option></select></label>
          <label><span>业务方向</span><input v-model="form.businessDirection" placeholder="大模型应用 / Agent 平台 / 搜广推 / 云原生" /></label>
          <label><span>类型 / 轮次</span><select v-model="form.interviewRound"><option>投递</option><option>HR 初筛</option><option>笔试</option><option>一面</option><option>二面</option><option>三面</option><option>终面</option><option>Offer 沟通</option></select></label>
          <label><span>面试时间</span><input v-model="form.interviewTime" type="datetime-local" /></label>
          <label><span>形式</span><select v-model="form.interviewFormat"><option>线上</option><option>线下</option><option>电话</option><option>笔试</option><option>线上 / 线下</option></select></label>
          <label><span>结果</span><select v-model="form.result"><option>跟进中</option><option>通过</option><option>未通过</option><option>待反馈</option><option>已放弃</option></select></label>
          <label><span>状态</span><select v-model="form.status"><option>投递中</option><option>笔试</option><option>面试进展</option><option>Offer</option><option>结束</option></select></label>
          <label class="wide"><span>面试内容</span><textarea v-model="form.interviewContent" rows="5" placeholder="面试/笔试内容、题型、过程" /></label>
          <label class="wide"><span>反思总结</span><textarea v-model="form.reflection" rows="5" placeholder="准备情况、暴露问题、后续改进" /></label>
          <label class="wide"><span>岗位 JD</span><textarea v-model="form.jobDescription" rows="7" placeholder="岗位职责、要求、技术栈" /></label>
          <label class="wide"><span>面试流程</span><textarea v-model="form.interviewProcess" rows="5" placeholder="流程、笔试范围、后续安排" /></label>
          <label class="wide"><span>下一步动作</span><textarea v-model="form.nextAction" rows="3" placeholder="例如：补 Python/Agent 题，一个月后集中面试" /></label>
          <label><span>优先级</span><select v-model="form.priority"><option>高</option><option>中</option><option>低</option></select></label>
          <label><span>标签</span><input v-model="form.tagsText" placeholder="量化金融,Agent" /></label>
        </div>
        <div class="detail-actions modal-actions-right">
          <button class="secondary-btn" @click="closeModal">{{ modalMode === 'view' ? '关闭' : '取消' }}</button>
          <button v-if="modalMode === 'view'" class="primary-btn" @click="modalMode = 'edit'">编辑</button>
          <button v-else class="primary-btn" :disabled="savingRecord" @click="saveRecord">{{ savingRecord ? '保存中' : '保存' }}</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { analyzeJourneyProgress, createJourneyRecord, deleteJourneyRecord, listJourneyRecords, updateJourneyRecord } from '../api/journey'
import { useJobStore } from '../stores/job'

const job = useJobStore()
const loading = ref(false)
const analyzing = ref(false)
const savingRecord = ref(false)
const deletingRecord = ref(false)
const modalVisible = ref(false)
const analysisModalVisible = ref(false)
const modalMode = ref('edit')
const error = ref('')
const records = ref([])
const analysis = ref(null)
const selectedRecordIds = ref([])
const collapsedGroups = reactive({})
const filters = reactive({ keyword: '', status: '', result: '' })
const editing = reactive({ recordId: '' })
const deleteDialog = reactive({ visible: false, recordId: '', title: '' })
const form = reactive(emptyForm())
const statuses = computed(() => Array.from(new Set(records.value.map(item => item.status).filter(Boolean))).sort())
const results = computed(() => Array.from(new Set(records.value.map(item => item.result).filter(Boolean))).sort())
const favoriteOptions = computed(() => job.favorites.map(item => ({ key: jobKey(item), title: jobTitle(item) })).filter(item => item.key))
const favoriteMap = computed(() => new Map(favoriteOptions.value.map(item => [item.key, item.title])))
const allVisibleIds = computed(() => records.value.map(item => item.recordId).filter(Boolean))
const allVisibleSelected = computed(() => allVisibleIds.value.length > 0 && allVisibleIds.value.every(id => selectedRecordIds.value.includes(id)))
const analysisButtonText = computed(() => selectedRecordIds.value.length ? `分析选中 ${selectedRecordIds.value.length} 条` : 'AI 分析全部')
const analysisScopeText = computed(() => selectedRecordIds.value.length ? `选中的 ${selectedRecordIds.value.length} 条进展` : `当前筛选下全部 ${records.value.length} 条进展`)
const groupedRecords = computed(() => {
  const groups = []
  const index = new Map()
  for (const item of records.value) {
    const favoriteKey = item.favoriteKey || ''
    const key = favoriteKey ? `favorite:${favoriteKey}` : `single:${item.recordId}`
    if (!index.has(key)) {
      const group = { key, favoriteKey, title: favoriteKey ? favoriteTitle(favoriteKey) || '已关联岗位收藏' : `${item.company || '未命名企业'} / ${item.positionName || '未填写岗位'}`, items: [] }
      index.set(key, group)
      groups.push(group)
    }
    index.get(key).items.push(item)
  }
  return groups
})
const viewRows = computed(() => [
  ['企业', form.company], ['地域', form.city], ['性质', form.companyNature], ['规模', form.companyScale],
  ['岗位', form.positionName], ['薪资', form.salaryRange], ['关联岗位收藏', favoriteTitle(form.favoriteKey) || '未关联'], ['业务方向', form.businessDirection], ['类型 / 轮次', form.interviewRound],
  ['面试时间', form.interviewTime], ['形式', form.interviewFormat], ['结果', form.result], ['状态', form.status],
  ['面试内容', form.interviewContent], ['反思总结', form.reflection], ['岗位 JD', form.jobDescription], ['面试流程', form.interviewProcess],
  ['下一步动作', form.nextAction], ['优先级', form.priority], ['标签', form.tagsText],
].map(([label, value]) => ({ label, value })))

onMounted(async () => {
  await Promise.all([loadRecords(), job.loadFavorites()])
})

function emptyForm() {
  return { company: '', city: '上海', companyNature: '互联网大厂', companyScale: '大型', positionName: '', salaryRange: '', favoriteKey: '', businessDirection: '', interviewRound: '一面', interviewTime: '', interviewContent: '', interviewFormat: '线上', result: '跟进中', reflection: '', jobDescription: '', interviewProcess: '', nextAction: '', status: '面试进展', priority: '中', tagsText: '' }
}
function jobKey(item) {
  return String(item?.favoriteKey || item?.jobKey || item?.securityId || item?.id || item?.jobId || item?.encryptJobId || `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`)
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
  loading.value = true; error.value = ''
  try {
    records.value = await listJourneyRecords(filters)
    selectedRecordIds.value = selectedRecordIds.value.filter(id => records.value.some(item => item.recordId === id))
  } catch (err) { error.value = err.message || '求职进展加载失败' } finally { loading.value = false }
}
function openCreateModal() {
  editing.recordId = ''
  Object.assign(form, emptyForm())
  modalMode.value = 'edit'
  modalVisible.value = true
}
function openViewModal(item) {
  fillForm(item)
  modalMode.value = 'view'
  modalVisible.value = true
}
function openEditModal(item) {
  fillForm(item)
  modalMode.value = 'edit'
  modalVisible.value = true
}
function fillForm(item) {
  editing.recordId = item.recordId
  Object.assign(form, emptyForm(), item, { tagsText: (item.tags || []).map(tag => tag.label || tag).join(',') })
}
function closeModal() { modalVisible.value = false }
async function saveRecord() {
  savingRecord.value = true; error.value = ''
  const payload = { ...form, tags: form.tagsText.split(/[,，、\s]+/).filter(Boolean) }
  try {
    const saved = editing.recordId ? await updateJourneyRecord(editing.recordId, payload) : await createJourneyRecord(payload)
    editing.recordId = saved.recordId
    await loadRecords()
    modalVisible.value = false
  } catch (err) { error.value = err.message || '保存进展失败' } finally { savingRecord.value = false }
}
function removeRecord(recordId) {
  const item = records.value.find(row => row.recordId === recordId)
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
    selectedRecordIds.value = selectedRecordIds.value.filter(id => id !== deleteDialog.recordId)
    Object.assign(deleteDialog, { visible: false, recordId: '', title: '' })
    await loadRecords()
  } catch (err) {
    error.value = err.message || '删除进展失败'
  } finally {
    deletingRecord.value = false
  }
}
function isSelected(recordId) { return selectedRecordIds.value.includes(recordId) }
function toggleRecordSelection(recordId) {
  if (!recordId) return
  selectedRecordIds.value = isSelected(recordId) ? selectedRecordIds.value.filter(id => id !== recordId) : [...selectedRecordIds.value, recordId]
}
function toggleSelectAll() {
  selectedRecordIds.value = allVisibleSelected.value ? [] : [...allVisibleIds.value]
}
function toggleGroup(key) { collapsedGroups[key] = !collapsedGroups[key] }
function visibleGroupItems(group) { return collapsedGroups[group.key] ? [] : group.items }
function openAnalysisModal() {
  analysisModalVisible.value = true
  runAnalysis()
}
function closeAnalysisModal() { if (!analyzing.value) analysisModalVisible.value = false }
async function runAnalysis() {
  analyzing.value = true; error.value = ''
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
  try { await navigator.clipboard.writeText(text) } catch (_) {}
}
</script>
