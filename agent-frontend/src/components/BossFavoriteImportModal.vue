<template>
  <div v-if="visible" class="modal-mask boss-favorite-import-mask" @click.self="closeModal">
    <section
      class="modal-card boss-favorite-import-modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="boss-import-title"
    >
      <button class="close" type="button" aria-label="关闭 Boss 岗位导入" :disabled="importing" @click="closeModal">
        ×
      </button>
      <header class="boss-favorite-import-head">
        <div>
          <h2 id="boss-import-title">从 BOSS 直聘导入岗位</h2>
          <p>导入仅保存岗位摘要，不会自动分析；职位描述将在你主动查看或分析时加载。</p>
        </div>
      </header>

      <BossLoginQrModal
        v-if="showLogin"
        visible
        embedded
        :data="authData"
        session-id="boss-favorite-import"
        @logged-in="handleLoggedIn"
      />

      <template v-else>
        <div class="boss-favorite-import-toolbar">
          <span>已选择 {{ selectedKeys.size }} 个</span>
          <div>
            <button
              type="button"
              class="ghost-mini-btn"
              :disabled="loading || importing || !selectableRows.length"
              @click="selectCurrentPage"
            >
              全选本页
            </button>
            <button
              type="button"
              class="ghost-mini-btn"
              :disabled="importing || !selectedKeys.size"
              @click="clearSelection"
            >
              清空选择
            </button>
          </div>
        </div>

        <div v-if="loading && !rows.length" class="boss-favorite-import-state">正在加载岗位…</div>
        <div v-else-if="error && !rows.length" class="boss-favorite-import-state">
          <strong>加载失败</strong>
          <span>{{ error }}</span>
        </div>
        <div v-else-if="!rows.length" class="boss-favorite-import-state">
          <strong>本页暂无可导入岗位</strong>
          <span>已收藏或重复岗位已自动隐藏。</span>
        </div>
        <div v-else class="boss-favorite-import-list">
          <label
            v-for="row in rows"
            :key="jobKey(row)"
            :class="['boss-favorite-import-row', { selected: isSelected(row) }]"
          >
            <input
              type="checkbox"
              :checked="isSelected(row)"
              :disabled="importing"
              :aria-label="`选择 ${jobTitle(row)}`"
              @change="toggleSelection(row)"
            />
            <span class="boss-favorite-import-main">
              <strong>{{ jobTitle(row) }}</strong>
              <span>{{ company(row) }} · {{ location(row) }} · {{ experience(row) }}</span>
              <small>{{ labels(row).join(' · ') || 'Boss 收藏岗位摘要' }}</small>
            </span>
            <b>{{ salary(row) }}</b>
          </label>
        </div>

        <p v-if="error && rows.length" class="error boss-favorite-import-message" role="alert">{{ error }}</p>
        <p v-if="notice" class="boss-favorite-import-message success" role="status">{{ notice }}</p>

        <footer class="boss-favorite-import-actions">
          <button type="button" class="secondary-btn" :disabled="loading || importing" @click="reloadCurrentPage">
            {{ loading ? '加载中' : '刷新' }}
          </button>
          <div class="boss-favorite-pagination" aria-label="BOSS 岗位分页">
            <button
              type="button"
              class="secondary-btn"
              :disabled="loading || importing || page <= 1"
              @click="loadPreviousPage"
            >
              上一页
            </button>
            <span>第 {{ page }} / {{ totalPages }} 页</span>
            <button
              type="button"
              class="secondary-btn"
              :disabled="loading || importing || !hasMore || page >= totalPages"
              @click="loadNextPage"
            >
              下一页
            </button>
          </div>
          <button type="button" class="primary-btn" :disabled="importing || !selectedKeys.size" @click="confirmImport">
            {{ importing ? '导入中' : `导入所选 ${selectedKeys.size} 个` }}
          </button>
        </footer>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { importBossFavoriteJobs, listBossFavoriteJobs } from '../api/jobs'
import { useJobStore } from '../stores/job'
import BossLoginQrModal from './BossLoginQrModal.vue'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['close', 'imported'])
const job = useJobStore()
const rows = ref([])
const page = ref(1)
const totalPages = ref(1)
const hasMore = ref(false)
const selectedKeys = ref(new Set())
const selectedJobs = ref(new Map())
const loading = ref(false)
const importing = ref(false)
const error = ref('')
const notice = ref('')
const showLogin = ref(false)
const authData = ref(null)
let requestSequence = 0

const selectableRows = computed(() => rows.value)

watch(
  () => props.visible,
  (visible) => {
    requestSequence += 1
    if (!visible) return
    resetState()
    initializeImport()
  },
  { immediate: true },
)

function resetState() {
  rows.value = []
  page.value = 1
  totalPages.value = 1
  hasMore.value = false
  selectedKeys.value = new Set()
  selectedJobs.value = new Map()
  loading.value = false
  importing.value = false
  error.value = ''
  notice.value = ''
  showLogin.value = false
  authData.value = null
}

function initializeImport() {
  return loadPage(1)
}

async function loadPage(targetPage, forceRefresh = false) {
  if (loading.value || importing.value) return
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = (await listBossFavoriteJobs(targetPage, forceRefresh)) || {}
    if (sequence !== requestSequence || !props.visible) return
    const incoming = Array.isArray(result.jobs) ? result.jobs : []
    rows.value = incoming.filter((row) => !row.alreadyImported)
    page.value = Number(result.page) || targetPage
    const reportedTotalPages = Number(result.totalPages)
    totalPages.value =
      Number.isFinite(reportedTotalPages) && reportedTotalPages > 0
        ? Math.max(page.value, Math.trunc(reportedTotalPages))
        : page.value + (result.hasMore === true ? 1 : 0)
    hasMore.value = result.hasMore === true && page.value < totalPages.value
  } catch (requestError) {
    if (sequence !== requestSequence || !props.visible) return
    if (requestError?.authRequired) {
      authData.value = requestError.authData || { authRequired: true, status: 'auth_required' }
      showLogin.value = true
    }
    error.value = requestError?.message || '读取 Boss 收藏岗位失败'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function reloadCurrentPage() {
  return loadPage(page.value, true)
}

function loadPreviousPage() {
  if (page.value <= 1) return
  return loadPage(page.value - 1)
}

function loadNextPage() {
  if (!hasMore.value || page.value >= totalPages.value) return
  return loadPage(page.value + 1)
}

function isSelected(row) {
  return selectedKeys.value.has(jobKey(row))
}

function toggleSelection(row) {
  const key = jobKey(row)
  if (!key) return
  const nextKeys = new Set(selectedKeys.value)
  const nextJobs = new Map(selectedJobs.value)
  if (nextKeys.has(key)) {
    nextKeys.delete(key)
    nextJobs.delete(key)
    error.value = ''
  } else {
    nextKeys.add(key)
    nextJobs.set(key, row)
    error.value = ''
  }
  selectedKeys.value = nextKeys
  selectedJobs.value = nextJobs
}

function selectCurrentPage() {
  const nextKeys = new Set(selectedKeys.value)
  const nextJobs = new Map(selectedJobs.value)
  for (const row of selectableRows.value) {
    const key = jobKey(row)
    if (!key) continue
    nextKeys.add(key)
    nextJobs.set(key, row)
  }
  selectedKeys.value = nextKeys
  selectedJobs.value = nextJobs
  error.value = ''
}

function clearSelection() {
  selectedKeys.value = new Set()
  selectedJobs.value = new Map()
  error.value = ''
}

async function confirmImport() {
  if (importing.value || !selectedKeys.value.size) return
  importing.value = true
  error.value = ''
  notice.value = ''
  try {
    const snapshots = Array.from(selectedKeys.value)
      .map((key) => selectedJobs.value.get(key))
      .filter(Boolean)
    const result = (await importBossFavoriteJobs(snapshots)) || {}
    if (Array.isArray(result.favorites)) job.favorites = result.favorites
    const completedKeys = new Set(
      (result.items || [])
        .filter((item) => ['imported', 'already_imported'].includes(item.status))
        .map((item) => String(item.jobKey || '')),
    )
    rows.value = rows.value.filter((row) => !completedKeys.has(jobKey(row)))
    selectedKeys.value = new Set(Array.from(selectedKeys.value).filter((key) => !completedKeys.has(key)))
    selectedJobs.value = new Map(Array.from(selectedJobs.value.entries()).filter(([key]) => !completedKeys.has(key)))
    notice.value = importSummary(result)
    if (result.authRequired) {
      authData.value = result.authData || { authRequired: true, status: 'auth_required' }
      showLogin.value = true
    }
    if (result.stopped && result.stoppedReason) error.value = result.stoppedReason
    emit('imported', result)
  } catch (requestError) {
    if (requestError?.authRequired) {
      authData.value = requestError.authData || { authRequired: true, status: 'auth_required' }
      showLogin.value = true
    }
    error.value = requestError?.message || '导入 Boss 收藏岗位失败'
  } finally {
    importing.value = false
  }
}

function importSummary(result) {
  const imported = Number(result.importedCount) || 0
  const existing = Number(result.existingCount) || 0
  const unprocessed = Number(result.unprocessedCount) || 0
  const parts = [`成功导入 ${imported} 个`]
  if (existing) parts.push(`${existing} 个已存在`)
  if (unprocessed) parts.push(`${unprocessed} 个为保护账号未处理`)
  return parts.join('，')
}

function handleLoggedIn() {
  showLogin.value = false
  authData.value = null
  error.value = ''
  notice.value = 'Boss 登录成功，正在加载可选岗位。'
  loadPage(1)
}

function closeModal() {
  if (importing.value) return
  requestSequence += 1
  emit('close')
}

function jobKey(row) {
  return String(row?.favoriteKey || row?.encryptJobId || row?.securityId || row?.jobId || row?.id || '')
}
function jobTitle(row) {
  return row?.jobName || row?.title || '未命名岗位'
}
function company(row) {
  return row?.brandName || row?.companyName || row?.company || '未命名公司'
}
function location(row) {
  return [row?.cityName || row?.city, row?.areaDistrict || row?.district].filter(Boolean).join('·') || '地点未标注'
}
function experience(row) {
  return row?.jobExperience || row?.experience || '经验不限'
}
function salary(row) {
  return row?.salaryDesc || row?.salary || '薪资面议'
}
function labels(row) {
  const values = row?.jobLabels || row?.skills || []
  return (Array.isArray(values) ? values : String(values || '').split(/[,，、]/)).filter(Boolean).slice(0, 4)
}
</script>
