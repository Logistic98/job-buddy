<template>
  <section class="settings-shell">
    <aside class="settings-nav glass-card">
      <header>
        <h2>平台设置</h2>
        <p>集中管理平台运行参数、数据规则、长期记忆和服务状态。</p>
      </header>
      <button
        v-for="item in menu"
        :key="item.key"
        :class="['settings-tab', { active: activeTab === item.key }]"
        @click="activeTab = item.key"
      >
        <span class="tab-letter">{{ item.letter }}</span>
        <span><strong>{{ item.label }}</strong><small>{{ item.desc }}</small></span>
      </button>
    </aside>

    <main class="settings-content glass-card">
      <div class="settings-content-head">
        <div>
          <p class="eyebrow">{{ currentMenu?.en }}</p>
          <h1>{{ currentMenu?.label }}</h1>
          <p>{{ currentMenu?.desc }}</p>
        </div>
        <div class="settings-actions">
          <span v-if="savedAt" class="save-hint">已保存 {{ savedAt }}</span>
          <button class="secondary-btn" :disabled="loading" @click="load">重新加载</button>
          <button class="primary-btn" :disabled="saving || loading" @click="save">{{ saving ? '保存中' : '保存设置' }}</button>
        </div>
      </div>

      <p v-if="error" class="error settings-error">{{ error }}</p>
      <div v-if="loading" class="empty-state"><strong>正在加载设置</strong><p>请稍候。</p></div>

      <template v-else-if="settings">
        <section v-if="activeTab === 'workspace'" class="settings-section">
          <div class="setting-card">
            <div><h3>业务运行参数</h3><p>这些配置会立即写入后端内存配置，并影响岗位推荐、简历上传和 Runtime 解析路径。</p></div>
            <div class="form-grid">
              <label><span>工作空间名称</span><input v-model="settings.workspace.name" /></label>
              <label><span>默认用户 ID</span><input v-model="settings.workspace.defaultUserId" /></label>
              <label><span>最大推荐岗位数（1-30）</span><input v-model.number="settings.workspace.maxJobsPerRecommend" type="number" min="1" max="30" step="1" @blur="normalizeLimits" /></label>
              <label><span>简历大小上限（MB，最多20）</span><input :value="resumeSizeMb(settings.workspace.maxResumeBytes)" type="number" min="1" max="20" step="1" placeholder="5" @input="setResumeSizeMb($event.target.value)" @blur="normalizeLimits" /></label>
              <label class="wide"><span>简历 Runtime 工作目录</span><input v-model="settings.workspace.resumeRuntimeWorkspace" placeholder="例如 .run/runtime-workspace" /></label>
            </div>
          </div>
          <div class="setting-card">
            <div><h3>Boss 直聘登录状态</h3><p>这里负责查看登录状态、发起扫码和取消二维码会话。</p></div>
            <div class="auth-settings-card">
              <div class="auth-icon">AUTH</div>
              <div class="auth-status-main">
                <div class="auth-title-row">
                  <strong>Boss 直聘</strong>
                  <span :class="['auth-status-badge', bossStatusLevel]">{{ bossStatusLabel }}</span>
                </div>
                <p>{{ bossStatusText }}</p>
                <small v-if="bossStatus?.updatedAt">更新时间：{{ formatTime(bossStatus.updatedAt) }}</small>
                <small v-if="bossStatus?.error" class="error">{{ bossStatus.error?.message || bossStatus.error }}</small>
              </div>
              <div class="auth-actions">
                <button class="secondary-btn" :disabled="authLoading" @click="refreshBossStatus">刷新状态</button>
                <button class="primary-btn" :disabled="authLoading" @click="showLogin = true">扫码登录</button>
              </div>
            </div>
          </div>
        </section>

        <section v-else-if="activeTab === 'blacklist'" class="settings-section">
          <div class="setting-card">
            <div><h3>公司屏蔽</h3><p>命中屏蔽公司或关键词的岗位会在推荐和打分前直接屏蔽。系统已内置一批常见外包/驻场供应商，可手动增删。</p></div>
            <div class="form-grid">
              <label><span>启用公司屏蔽</span><select v-model="settings.blacklist.enabled"><option :value="true">启用</option><option :value="false">关闭</option></select></label>
              <label><span>匹配方式</span><select v-model="settings.blacklist.matchMode"><option value="contains">包含匹配</option></select></label>
            </div>
          </div>
          <div class="setting-card">
            <div class="memory-card-head"><div><h3>公司屏蔽维护</h3><p>公司名称和关键词都会参与匹配，例如品牌名、公司名、岗位描述里出现外包、驻场等词会被屏蔽。</p></div></div>
            <div class="memory-editor">
              <select v-model="blacklistForm.type"><option value="company">公司</option><option value="keyword">关键词</option></select>
              <input v-model.trim="blacklistForm.name" placeholder="例如：某公司 / 外包 / 驻场" @keyup.enter="addBlacklistItem" />
              <button class="primary-btn" :disabled="!blacklistForm.name" @click="addBlacklistItem">新增屏蔽项</button>
            </div>
            <div class="source-list memory-list">
              <article v-for="item in settings.blacklist.items" :key="item.id || item.name" class="source-item memory-item">
                <div><strong>{{ item.name }}</strong><p>{{ item.reason || (item.type === 'keyword' ? '关键词屏蔽' : '公司屏蔽') }}</p><small>{{ item.source === 'system' ? '系统初始化' : '手动维护' }} · {{ item.type === 'keyword' ? '关键词' : '公司' }}</small></div>
                <div class="source-badges"><span :class="['source-state', item.enabled ? 'enabled' : 'disabled']">{{ item.enabled ? '启用' : '关闭' }}</span><button class="secondary-btn" @click="item.enabled = !item.enabled">{{ item.enabled ? '停用' : '启用' }}</button><button v-if="item.source !== 'system'" class="danger-text" @click="removeBlacklistItem(item)">删除</button></div>
              </article>
            </div>
          </div>
        </section>

        <section v-else-if="activeTab === 'memory'" class="settings-section">
          <div class="setting-card">
            <div><h3>记忆策略</h3><p>长期记忆会优先存入本地设置文件，即使 agent-memory 服务不可用，也能在工作台对话中生效。</p></div>
            <div class="form-grid">
              <label><span>启用记忆</span><select v-model="settings.memory.enabled"><option :value="true">启用</option><option :value="false">关闭</option></select></label>
              <label><span>自动保存稳定偏好</span><select v-model="settings.memory.autoSaveChat"><option :value="true">启用</option><option :value="false">关闭</option></select></label>
              <label><span>按需使用相关记忆</span><select v-model="settings.memory.autoUseMemory"><option :value="true">启用</option><option :value="false">关闭</option></select></label>
              <label><span>最大记忆条数</span><input v-model.number="settings.memory.maxItems" type="number" min="20" max="1000" step="10" /></label>
            </div>
          </div>
          <div class="setting-card">
            <div class="memory-card-head"><div><h3>记忆管理</h3><p>可以手动新增、删除或清空记忆。建议把稳定偏好、求职约束、面试复盘沉淀为记忆。</p></div><button class="danger-btn" :disabled="!memories.length" @click="clearAllMemories">清空记忆</button></div>
            <div class="memory-editor">
              <select v-model="memoryForm.type"><option value="preference">偏好</option><option value="constraint">约束</option><option value="interview">面试复盘</option><option value="conversation">对话</option></select>
              <input v-model.trim="memoryForm.content" placeholder="例如：优先看上海，大模型应用开发，薪资 40-50k，排除外包驻场" @keyup.enter="createMemory" />
              <button class="primary-btn" :disabled="!memoryForm.content" @click="createMemory">新增记忆</button>
            </div>
            <div class="source-list memory-list">
              <article v-for="item in memories" :key="item.id" class="source-item memory-item">
                <div><strong>{{ memoryTypeText(item.type) }}</strong><p>{{ item.content }}</p><small>{{ item.source || 'manual' }} · {{ formatTime(item.updatedAt || item.createdAt) }}</small></div>
                <div class="source-badges"><span :class="['source-state', item.enabled ? 'enabled' : 'disabled']">{{ item.enabled ? '启用' : '关闭' }}</span><button class="danger-text" @click="removeMemory(item.id)">删除</button></div>
              </article>
              <div v-if="!memories.length" class="empty-state"><strong>暂无记忆</strong><p>新增一条求职偏好后，工作台会自动读取并使用。</p></div>
            </div>
          </div>
        </section>

        <section v-else-if="activeTab === 'services'" class="settings-section">
          <div class="setting-card">
            <div><h3>服务地址</h3><p>配置各后端服务的实际访问地址。保存后，健康检查会立即使用新地址。</p></div>
            <div class="form-grid">
              <label><span>Intent URL</span><input v-model="settings.services.intentUrl" placeholder="http://localhost:8020" /></label>
              <label><span>Runtime URL</span><input v-model="settings.services.runtimeUrl" placeholder="http://localhost:8010" /></label>
              <label><span>Memory URL</span><input v-model="settings.services.memoryUrl" placeholder="http://localhost:8030" /></label>
              <label><span>Tool URL</span><input v-model="settings.services.toolUrl" placeholder="http://localhost:8040" /></label>
              <label><span>Eval URL</span><input v-model="settings.services.evalUrl" placeholder="http://localhost:8050" /></label>
              <label><span>连接超时（秒）</span><input :value="durationSeconds(settings.services.connectTimeout)" type="number" min="1" step="1" placeholder="2" @input="setServiceDuration('connectTimeout', $event.target.value)" /></label>
              <label><span>读取超时（秒）</span><input :value="durationSeconds(settings.services.readTimeout)" type="number" min="1" step="1" placeholder="75" @input="setServiceDuration('readTimeout', $event.target.value)" /></label>
            </div>
          </div>
          <div class="setting-card service-health-card">
            <div class="service-health-head">
              <div><h3>服务健康状态</h3><p>通过健康检查接口动态探测服务状态，点击服务可查看最近一段时间的监测记录。</p></div>
              <button class="secondary-btn" :disabled="loading" @click="refreshServiceHealth">刷新状态</button>
            </div>
            <div class="source-list service-health-list">
              <article
                v-for="source in serviceRows"
                :key="source.id"
                :class="['source-item', 'service-health-item', { expanded: expandedServiceId === source.id }]"
              >
                <button class="service-health-summary" type="button" @click="toggleServiceHistory(source.id)">
                  <div class="service-health-main"><strong>{{ source.name }}</strong><p>{{ source.healthUrl || source.url || '未配置' }}</p></div>
                  <div class="service-health-status">
                    <span :class="['health-state', source.status]">{{ statusText(source.status) }}</span>
                    <span class="service-uptime">{{ source.uptimeText }}</span>
                  </div>
                </button>
                <div class="service-uptime-bars" :aria-label="`${source.name} 最近监测记录`">
                  <span
                    v-for="(point, index) in source.history"
                    :key="`${source.id}-${point.checkedAt || index}-${index}`"
                    :class="['uptime-bar', point.status]"
                    :title="historyPointTitle(point)"
                  ></span>
                </div>
                <div class="service-health-axis">
                  <span>{{ source.historyStartText }}</span>
                  <span>{{ source.history.length ? `${source.uptimeText} 可用率` : '暂无监测记录' }}</span>
                  <span>现在</span>
                </div>
                <div v-if="expandedServiceId === source.id" class="service-history-detail">
                  <div class="service-history-meta">
                    <span>监测次数：{{ source.history.length }}</span>
                    <span>最近检查：{{ formatTime(source.checkedAt) || '暂无' }}</span>
                    <span>状态：{{ source.message || statusText(source.status) }}</span>
                  </div>
                  <div class="service-history-list">
                    <div v-for="(point, index) in source.history.slice().reverse()" :key="`${source.id}-detail-${point.checkedAt || index}`" class="service-history-row">
                      <span :class="['history-dot', point.status]"></span>
                      <strong>{{ statusText(point.status) }}</strong>
                      <span>{{ formatFullTime(point.checkedAt) }}</span>
                      <em>{{ point.message || '-' }}</em>
                    </div>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </section>
</template>
    </main>

    <BossLoginQrModal
      :visible="showLogin"
      :session-id="chat.sessionId"
      @close="showLogin = false"
      @logged-in="handleLoggedIn"
    />
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getBossLoginStatus } from '../api/boss'
import { addMemory, clearMemories, deleteMemory, getSettings, listMemories, saveSettings } from '../api/settings'
import { useChatStore } from '../stores/chat'
import BossLoginQrModal from './BossLoginQrModal.vue'

const menu = [
  { key: 'workspace', letter: 'RP', label: '运行参数', en: 'Runtime Parameters', desc: '用户、岗位数量、简历限制' },
  { key: 'blacklist', letter: 'CB', label: '公司屏蔽', en: 'Company Blocking', desc: '屏蔽外包、驻场和指定公司' },
  { key: 'memory', letter: 'MM', label: '记忆管理', en: 'Memory', desc: '长期记忆新增、删除、清空' },
  { key: 'services', letter: 'SM', label: '服务监控', en: 'Service Monitor', desc: '服务地址、健康状态与监测记录' }
]

const chat = useChatStore()
const activeTab = ref('workspace')
const settings = ref(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const savedAt = ref('')
const showLogin = ref(false)
const authLoading = ref(false)
const bossStatus = ref(null)
const memories = ref([])
const expandedServiceId = ref('')
const healthHistory = ref(readHealthHistory())
let serviceHealthTimer = null
const memoryForm = ref({ type: 'preference', content: '' })
const blacklistForm = ref({ type: 'company', name: '' })
const currentMenu = computed(() => menu.find(item => item.key === activeTab.value))
const serviceRows = computed(() => Object.values(settings.value?.serviceStatuses || {}).map(source => enrichServiceRow(source)))
const bossLoggedIn = computed(() => {
  const status = bossStatus.value || {}
  const data = status.data || {}
  return !!(status.ok || status.authenticated || status.status === 'logged_in' || data.authenticated || data.search_authenticated || data.status === 'logged_in')
})
const bossStatusLevel = computed(() => {
  if (authLoading.value) return 'checking'
  if (!bossStatus.value) return 'unknown'
  if (bossLoggedIn.value) return 'success'
  if (bossStatus.value.status === 'error' || bossStatus.value.error) return 'error'
  if (bossStatus.value.status === 'expired') return 'warning'
  if (bossStatus.value.status === 'qr_ready' || bossStatus.value.status === 'scanned' || bossStatus.value.status === 'confirmed') return 'pending'
  if (bossStatus.value.authRequired || bossStatus.value.status === 'auth_required') return 'warning'
  return 'unknown'
})
const bossStatusLabel = computed(() => {
  const labels = {
    success: '已登录',
    warning: '需登录',
    error: '异常',
    pending: '扫码中',
    checking: '检查中',
    unknown: '未知',
  }
  return labels[bossStatusLevel.value] || '未知'
})
const bossStatusText = computed(() => {
  if (authLoading.value) return '正在检查登录状态'
  if (!bossStatus.value) return '尚未检查登录状态'
  if (bossLoggedIn.value) return '已登录，可以读取 Boss 直聘岗位数据。'
  if (bossStatus.value.authRequired) return bossStatus.value.message || '需要扫码登录。'
  if (bossStatus.value.status) return `当前状态：${displayAuthStatus(bossStatus.value.status)}`
  return '未登录或登录状态不可用。'
})

onMounted(async () => {
  await load()
  refreshBossStatus().catch(() => {})
  startServiceHealthPolling()
})

onUnmounted(() => {
  if (serviceHealthTimer) window.clearInterval(serviceHealthTimer)
})

watch(activeTab, value => {
  if (value === 'services') refreshServiceHealth().catch(() => {})
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await getSettings()
    ensureSettingsShape()
    recordServiceStatuses(settings.value?.serviceStatuses)
    memories.value = await listMemories().catch(() => settings.value?.memory?.items || [])
    savedAt.value = formatTime(settings.value?.updatedAt)
  } catch (err) {
    error.value = err.message || '设置加载失败'
  } finally {
    loading.value = false
  }
}

function ensureSettingsShape() {
  if (!settings.value.memory) settings.value.memory = { enabled: true, autoSaveChat: false, autoUseMemory: true, maxItems: 200, items: [] }
  if (!Array.isArray(settings.value.memory.items)) settings.value.memory.items = []
  if (!settings.value.blacklist) settings.value.blacklist = { enabled: true, matchMode: 'contains', items: [] }
  if (!Array.isArray(settings.value.blacklist.items)) settings.value.blacklist.items = []
}

async function save() {
  if (!settings.value) return
  ensureSettingsShape()
  normalizeLimits()
  saving.value = true
  error.value = ''
  try {
    settings.value = await saveSettings(settings.value)
    savedAt.value = formatTime(settings.value?.updatedAt)
  } catch (err) {
    error.value = err.message || '设置保存失败'
  } finally {
    saving.value = false
  }
}

async function refreshServiceHealth() {
  if (!settings.value) return
  try {
    const latest = await getSettings()
    settings.value = latest
    ensureSettingsShape()
    recordServiceStatuses(latest?.serviceStatuses)
    savedAt.value = formatTime(latest?.updatedAt)
  } catch (err) {
    error.value = err.message || '服务健康状态刷新失败'
  }
}

function startServiceHealthPolling() {
  if (serviceHealthTimer) window.clearInterval(serviceHealthTimer)
  serviceHealthTimer = window.setInterval(() => {
    if (activeTab.value === 'services' && !loading.value && !saving.value) refreshServiceHealth().catch(() => {})
  }, 10000)
}

async function refreshBossStatus() {
  authLoading.value = true
  try {
    bossStatus.value = await getBossLoginStatus(chat.sessionId)
  } catch (err) {
    bossStatus.value = { ok: false, status: 'error', error: err.message || '登录状态检查失败' }
  } finally {
    authLoading.value = false
  }
}

function displayAuthStatus(status) {
  if (status === 'logged_in') return '已登录'
  if (status === 'auth_required') return '未登录'
  if (status === 'expired') return '二维码已过期'
  if (status === 'error') return '检查失败'
  return status || '未知'
}

function handleLoggedIn() {
  chat.authRequired = null
  showLogin.value = false
  refreshBossStatus().catch(() => {})
}

async function createMemory() {
  if (!memoryForm.value.content) return
  const item = await addMemory({ ...memoryForm.value, source: 'manual', enabled: true })
  const key = memoryKey(item)
  memories.value = [item, ...memories.value.filter(row => memoryKey(row) !== key)]
  memoryForm.value.content = ''
}

function memoryKey(item) {
  const type = String(item?.type || 'preference').toLowerCase().replace(/[\s　]+/g, '')
  const content = String(item?.content || '').toLowerCase().replace(/[\s　]+/g, '').replace(/，/g, ',').replace(/。/g, '.')
  return `${type}|${content}`
}

async function removeMemory(memoryId) {
  await deleteMemory(memoryId)
  memories.value = memories.value.filter(item => item.id !== memoryId)
}

async function clearAllMemories() {
  if (!window.confirm('确认清空长期记忆？')) return
  await clearMemories()
  memories.value = []
}

function memoryTypeText(type) {
  return { preference: '偏好', constraint: '约束', interview: '面试复盘', conversation: '对话' }[type] || type || '记忆'
}

function addBlacklistItem() {
  if (!blacklistForm.value.name || !settings.value?.blacklist?.items) return
  settings.value.blacklist.items.unshift({
    id: `blk_manual_${Date.now()}`,
    name: blacklistForm.value.name,
    type: blacklistForm.value.type,
    enabled: true,
    source: 'manual',
    reason: blacklistForm.value.type === 'keyword' ? '手动关键词屏蔽' : '手动公司屏蔽',
  })
  blacklistForm.value.name = ''
}

function removeBlacklistItem(item) {
  settings.value.blacklist.items = settings.value.blacklist.items.filter(row => row !== item)
}

function normalizeLimits() {
  if (!settings.value?.workspace) return
  const workspace = settings.value.workspace
  workspace.maxJobsPerRecommend = clampNumber(workspace.maxJobsPerRecommend, 1, 30, 15)
  workspace.maxResumeBytes = clampNumber(workspace.maxResumeBytes, 1048576, 20971520, 5242880)
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(max, Math.max(min, Math.round(number)))
}

function resumeSizeMb(bytes) {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value <= 0) return ''
  return String(Math.round(value / 1048576))
}

function setResumeSizeMb(value) {
  if (!settings.value?.workspace) return
  const mb = clampNumber(value, 1, 20, 5)
  settings.value.workspace.maxResumeBytes = mb * 1048576
}

function durationSeconds(value) {
  if (value == null || value === '') return ''
  const text = String(value).trim().toUpperCase()
  const isoMatch = text.match(/^PT(?:(\d+)M)?(?:(\d+)S)?$/)
  if (isoMatch) return String((Number(isoMatch[1] || 0) * 60) + Number(isoMatch[2] || 0))
  const secondMatch = text.match(/^(\d+)(?:S|秒)?$/)
  if (secondMatch) return secondMatch[1]
  return String(value)
}

function setServiceDuration(key, value) {
  if (!settings.value?.services) return
  const seconds = clampNumber(value, 1, 3600, 1)
  settings.value.services[key] = `${seconds}s`
}

function toggleServiceHistory(serviceId) {
  expandedServiceId.value = expandedServiceId.value === serviceId ? '' : serviceId
}

function enrichServiceRow(source) {
  const row = source || {}
  const history = healthHistory.value[row.id] || []
  const successCount = history.filter(point => point.status === 'running').length
  const uptime = history.length ? Math.round((successCount / history.length) * 10000) / 100 : 0
  return {
    ...row,
    history,
    uptimeText: history.length ? `${uptime.toFixed(2)}%` : '暂无数据',
    historyStartText: history.length ? formatTime(history[0].checkedAt) : '开始监测',
  }
}

function recordServiceStatuses(statuses) {
  if (!statuses) return
  const next = { ...healthHistory.value }
  Object.values(statuses).forEach(source => {
    if (!source?.id) return
    const point = {
      status: source.status || 'unknown',
      checkedAt: source.checkedAt || new Date().toISOString(),
      message: source.message || '',
    }
    const previous = next[source.id] || []
    const last = previous[previous.length - 1]
    if (last && last.checkedAt === point.checkedAt && last.status === point.status) return
    next[source.id] = [...previous, point].slice(-60)
  })
  healthHistory.value = next
  window.localStorage.setItem('job-buddy.serviceHealthHistory', JSON.stringify(next))
}

function readHealthHistory() {
  try {
    const raw = window.localStorage.getItem('job-buddy.serviceHealthHistory')
    const data = raw ? JSON.parse(raw) : {}
    return data && typeof data === 'object' ? data : {}
  } catch (_err) {
    return {}
  }
}

function historyPointTitle(point) {
  return `${formatFullTime(point.checkedAt)} · ${statusText(point.status)}${point.message ? ` · ${point.message}` : ''}`
}

function statusText(status) {
  const text = {
    running: '运行成功',
    down: '未运行',
    not_configured: '未配置',
    unknown: '未知',
  }
  return text[status] || status || '未知'
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''
}

function formatFullTime(value) {
  return value ? new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : ''
}
</script>
