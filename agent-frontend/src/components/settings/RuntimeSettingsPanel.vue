<template>
  <div>
    <p v-if="error || restoreError" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
      {{ error || restoreError }}
    </p>
    <div v-if="loading" class="empty-state">
      <strong>正在加载设置</strong>
      <p>请稍候。</p>
    </div>
    <section v-if="workspace" class="settings-section runtime-settings-section">
      <div class="runtime-settings-grid">
        <div class="setting-card runtime-setting-card">
          <div class="runtime-setting-head">
            <span class="runtime-setting-index">JOB</span>
            <div>
              <h3>岗位推荐</h3>
              <p>控制每次返回数量和参与评分的候选规模。</p>
            </div>
          </div>
          <div class="form-grid compact-runtime-form all-fields-required">
            <label
              ><span>每批展示岗位数</span
              ><input
                aria-required="true"
                v-model.number="workspace.maxJobsPerRecommend"
                type="number"
                min="1"
                max="30"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–30，决定首屏和“换一批”的数量。</small></label
            >
            <label
              ><span>候选池倍率</span
              ><input
                aria-required="true"
                v-model.number="workspace.recommendOverfetchFactor"
                type="number"
                min="1"
                max="10"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–10，合格岗位不足时按倍率继续评估后续候选。</small></label
            >
            <label class="wide"
              ><span>最低推荐匹配度（分）</span
              ><input
                aria-required="true"
                v-model.number="workspace.minimumRecommendedMatchScore"
                type="number"
                min="0"
                max="100"
                step="5"
                @blur="normalizeLimits"
              /><small>范围 0–100，候选不足时扩大评估范围，但不会降低推荐门槛。</small></label
            >
          </div>
        </div>

        <div class="setting-card runtime-setting-card">
          <div class="runtime-setting-head">
            <span class="runtime-setting-index boss">BOSS</span>
            <div>
              <h3>Boss 检索</h3>
              <p>平衡“换一批”的覆盖范围、响应速度和风控风险。</p>
            </div>
          </div>
          <div class="form-grid compact-runtime-form all-fields-required">
            <label
              ><span>单次抓取页数</span
              ><input
                aria-required="true"
                v-model.number="workspace.bossSearchMaxPages"
                type="number"
                min="1"
                max="5"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–5，仅在候选池不足时继续抓取。</small></label
            >
            <label
              ><span>最大检索页深</span
              ><input
                aria-required="true"
                v-model.number="workspace.bossSearchMaxPageDepth"
                type="number"
                min="1"
                max="10"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–10，限制一次检索最多深入的页数。</small></label
            >
            <label
              ><span>候选缓存（分钟）</span
              ><input
                aria-required="true"
                v-model.number="workspace.bossSearchCacheTtlMinutes"
                type="number"
                min="1"
                max="1440"
                step="5"
                @blur="normalizeLimits"
              /><small>范围 1–1440，减少相同条件下的重复请求。</small></label
            >
            <label
              ><span>风控冷却（分钟）</span
              ><input
                aria-required="true"
                v-model.number="workspace.bossSearchCooldownMinutesOnRisk"
                type="number"
                min="1"
                max="1440"
                step="5"
                @blur="normalizeLimits"
              /><small>触发验证或限流后暂停请求，默认 30 分钟。</small></label
            >
          </div>
        </div>

        <div class="setting-card runtime-setting-card">
          <div class="runtime-setting-head">
            <span class="runtime-setting-index agent">AI</span>
            <div>
              <h3>Agent 执行预算</h3>
              <p>限制单次复杂任务的循环、工具调用和连续失败次数。</p>
            </div>
          </div>
          <div class="form-grid compact-runtime-form all-fields-required">
            <label
              ><span>最大执行轮数</span
              ><input
                aria-required="true"
                v-model.number="workspace.runtimeMaxTurns"
                type="number"
                min="1"
                max="20"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–20，达到上限后结束当前任务。</small></label
            >
            <label
              ><span>最大工具调用数</span
              ><input
                aria-required="true"
                v-model.number="workspace.runtimeMaxToolCalls"
                type="number"
                min="1"
                max="30"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–30，控制工具成本和执行时长。</small></label
            >
            <label class="wide"
              ><span>最大连续失败数</span
              ><input
                aria-required="true"
                v-model.number="workspace.runtimeMaxFailures"
                type="number"
                min="1"
                max="10"
                step="1"
                @blur="normalizeLimits"
              /><small>范围 1–10，连续失败达到阈值时停止执行。</small></label
            >
          </div>
        </div>

        <div class="setting-card runtime-setting-card">
          <div class="runtime-setting-head">
            <span class="runtime-setting-index resume">CV</span>
            <div>
              <h3>简历管理</h3>
              <p>控制简历上传体积和简历撰写历史保留规模。</p>
            </div>
          </div>
          <div class="form-grid compact-runtime-form all-fields-required">
            <label
              ><span>上传大小上限（MB）</span
              ><input
                aria-required="true"
                :value="resumeSizeMb(workspace.maxResumeBytes)"
                type="number"
                min="1"
                max="20"
                step="1"
                placeholder="5"
                @input="setResumeSizeMb($event.target.value)"
                @blur="normalizeLimits"
              /><small>范围 1–20 MB，超过限制的文件无法上传。</small></label
            >
            <label
              ><span>版本历史保留数</span
              ><input
                aria-required="true"
                v-model.number="workspace.resumeWriterVersionLimit"
                type="number"
                min="5"
                max="100"
                step="5"
                @blur="normalizeLimits"
              /><small>范围 5–100，超出后自动清理最早版本。</small></label
            >
          </div>
        </div>
      </div>
      <div class="setting-card boss-auth-setting-card">
        <div>
          <h3>Boss 直聘登录状态</h3>
          <p>这里负责查看登录状态、发起扫码和取消二维码会话。</p>
        </div>
        <div class="auth-settings-card">
          <div class="auth-icon">AUTH</div>
          <div class="auth-status-main">
            <div class="auth-title-row">
              <strong>Boss 直聘</strong
              ><span :class="['auth-status-badge', bossStatusLevel]">{{ bossStatusLabel }}</span>
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

    <BossLoginQrModal
      :visible="showLogin"
      :session-id="chat.sessionId"
      @close="showLogin = false"
      @logged-in="handleLoggedIn"
    />

    <div v-if="showRestoreConfirm" class="modal-mask runtime-restore-modal-mask" @click.self="closeRestoreConfirm">
      <section
        class="modal-card runtime-restore-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="runtime-restore-title"
        aria-describedby="runtime-restore-description"
      >
        <h2 id="runtime-restore-title">恢复默认参数？</h2>
        <p id="runtime-restore-description">当前运行参数将恢复为平台默认值，未保存修改会丢失。</p>
        <p v-if="restoreError" class="error runtime-restore-error">{{ restoreError }}</p>
        <div class="runtime-restore-actions">
          <button type="button" class="secondary-btn" :disabled="restoring" @click="closeRestoreConfirm">取消</button>
          <button type="button" class="danger-btn" :disabled="restoring" @click="confirmRestoreDefaults">
            {{ restoring ? '恢复中' : '确认恢复' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getBossLoginStatus } from '../../api/boss'
import { restoreWorkspaceDefaults } from '../../api/settings'
import { normalizeRuntimeSettings } from '../../composables/useRuntimeSettings'
import { useScopedSettings } from '../../composables/useScopedSettings'
import { validateInteger } from '../../utils/formValidation'
import { useChatStore } from '../../stores/chat'
import BossLoginQrModal from '../BossLoginQrModal.vue'

const emit = defineEmits(['state-change'])
const chat = useChatStore()
const {
  value: workspace,
  loading,
  saving,
  error,
  dirty,
  load,
  save: saveWorkspace,
  setValue: setWorkspace,
} = useScopedSettings('workspace', normalizeRuntimeSettings)
const showLogin = ref(false)
const showRestoreConfirm = ref(false)
const restoring = ref(false)
const restoreError = ref('')
const authLoading = ref(false)
const bossStatus = ref(null)

const bossLoggedIn = computed(() => {
  const status = bossStatus.value || {}
  const data = status.data || {}
  return !!(
    status.ok ||
    status.authenticated ||
    status.status === 'logged_in' ||
    data.authenticated ||
    data.search_authenticated ||
    data.status === 'logged_in'
  )
})
const bossStatusLevel = computed(() => {
  if (authLoading.value) return 'checking'
  if (!bossStatus.value) return 'unknown'
  if (bossLoggedIn.value) return 'success'
  if (bossStatus.value.status === 'error' || bossStatus.value.error) return 'error'
  if (bossStatus.value.status === 'expired') return 'warning'
  if (['qr_ready', 'scanned', 'confirmed'].includes(bossStatus.value.status)) return 'pending'
  if (bossStatus.value.authRequired || bossStatus.value.status === 'auth_required') return 'warning'
  return 'unknown'
})
const bossStatusLabel = computed(
  () =>
    ({ success: '已登录', warning: '需登录', error: '异常', pending: '扫码中', checking: '检查中', unknown: '未知' })[
      bossStatusLevel.value
    ] || '未知',
)
const bossStatusText = computed(() => {
  if (authLoading.value) return '正在检查登录状态'
  if (!bossStatus.value) return '尚未检查登录状态'
  if (bossLoggedIn.value) return '已登录，可以读取 Boss 直聘岗位数据。'
  if (bossStatus.value.authRequired) return bossStatus.value.message || '需要扫码登录。'
  if (bossStatus.value.status) return `当前状态：${displayAuthStatus(bossStatus.value.status)}`
  return '未登录或登录状态不可用。'
})

watch(
  [dirty, saving, loading, restoring],
  () =>
    emit('state-change', {
      key: 'workspace',
      dirty: dirty.value,
      saving: saving.value || restoring.value,
      loading: loading.value,
    }),
  { immediate: true },
)
onMounted(() => {
  load()
  refreshBossStatus()
})

defineExpose({ save, openRestoreConfirm })

async function save() {
  error.value = ''
  try {
    const rules = [
      ['maxJobsPerRecommend', '每批展示岗位数', 1, 30],
      ['recommendOverfetchFactor', '候选池倍率', 1, 10],
      ['minimumRecommendedMatchScore', '最低推荐匹配度', 0, 100],
      ['bossSearchMaxPages', '单次抓取页数', 1, 5],
      ['bossSearchMaxPageDepth', '最大检索页深', 1, 10],
      ['bossSearchCacheTtlMinutes', '候选缓存时间', 1, 1440],
      ['bossSearchCooldownMinutesOnRisk', '风控冷却时间', 1, 1440],
      ['runtimeMaxTurns', '最大执行轮数', 1, 20],
      ['runtimeMaxToolCalls', '最大工具调用数', 1, 30],
      ['runtimeMaxFailures', '最大连续失败数', 1, 10],
      ['resumeWriterVersionLimit', '版本历史保留数', 5, 100],
    ]
    for (const [key, label, min, max] of rules) validateInteger(workspace.value?.[key], label, { min, max })
    validateInteger(resumeSizeMb(workspace.value?.maxResumeBytes), '上传大小上限', { min: 1, max: 20 })
  } catch (err) {
    error.value = err.message
    return false
  }
  return saveWorkspace()
}

function openRestoreConfirm() {
  restoreError.value = ''
  showRestoreConfirm.value = true
}
function closeRestoreConfirm() {
  if (!restoring.value) showRestoreConfirm.value = false
}
async function confirmRestoreDefaults() {
  if (restoring.value) return
  restoring.value = true
  restoreError.value = ''
  try {
    const settings = await restoreWorkspaceDefaults()
    setWorkspace(settings?.workspace)
    showRestoreConfirm.value = false
  } catch (err) {
    restoreError.value = err.message || '运行参数恢复失败'
  } finally {
    restoring.value = false
  }
}

function normalizeLimits() {
  if (workspace.value) workspace.value = normalizeRuntimeSettings(workspace.value)
}
function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(max, Math.max(min, Math.round(number))) : fallback
}
function resumeSizeMb(bytes) {
  const value = Number(bytes)
  return Number.isFinite(value) && value > 0 ? String(Math.round(value / 1048576)) : ''
}
function setResumeSizeMb(value) {
  if (workspace.value) workspace.value.maxResumeBytes = clampNumber(value, 1, 20, 5) * 1048576
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
  return (
    { logged_in: '已登录', auth_required: '未登录', expired: '二维码已过期', error: '检查失败' }[status] ||
    status ||
    '未知'
  )
}
function handleLoggedIn() {
  chat.authRequired = null
  showLogin.value = false
  refreshBossStatus()
}
function formatTime(value) {
  return value
    ? new Date(value).toLocaleString(undefined, {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
    : ''
}
</script>
