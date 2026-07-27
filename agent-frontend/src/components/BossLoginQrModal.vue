<template>
  <div v-if="visible" :class="embedded ? 'boss-login-embedded' : 'modal-mask boss-login-modal-mask'">
    <div :class="embedded ? 'boss-login-embedded-card' : 'modal-card boss-login-modal-card'">
      <button v-if="!embedded" class="close" @click="handleClose">×</button>
      <h2>Boss 直聘扫码登录</h2>
      <p v-if="!embedded">使用 Boss 直聘 App 扫码确认，系统会保存登录态并继续搜索。</p>

      <div v-if="state.imageBase64" class="qr-wrap">
        <img :src="qrImageSrc" alt="Boss 登录二维码" />
        <p class="qr-hint">{{ stageHint }}</p>
      </div>
      <div v-else class="qr-placeholder">
        <span>{{ stageHint }}</span>
      </div>

      <div class="login-stage-track" aria-label="Boss 登录进度">
        <div v-for="(stage, index) in loginStages" :key="stage" :class="['login-stage', stageClass(index)]">
          <span>{{ index + 1 }}</span>
          <small>{{ stage }}</small>
        </div>
      </div>

      <div :class="['login-status-card', `is-${statusTone}`]" role="status" aria-live="polite" aria-atomic="true">
        <span :class="['login-status-indicator', { active: statusChecking }]"></span>
        <div>
          <small>{{ statusKicker }}</small>
          <strong>{{ statusText }}</strong>
          <p>{{ statusDetail }}</p>
        </div>
      </div>
      <div class="modal-actions">
        <button class="secondary" :disabled="loading" @click="refreshQr()">{{ refreshLabel }}</button>
        <button class="secondary" :disabled="loading || statusChecking || !state.qrSessionId" @click="refreshStatus">
          {{ statusChecking ? '正在检查' : '立即检查状态' }}
        </button>
      </div>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { cancelBossLogin, getBossLoginQr, getBossLoginStatus } from '../api/boss'

const props = defineProps({
  visible: Boolean,
  sessionId: { type: String, default: '' },
  data: { type: Object, default: null },
  embedded: Boolean,
})
const emit = defineEmits(['close', 'logged-in'])

const state = reactive({
  qrSessionId: null,
  qrId: null,
  imageBase64: null,
  imageMime: 'image/png',
  status: 'idle',
  expiresAt: null,
})
const loading = ref(false)
const errorMessage = ref('')
const statusChecking = ref(false)
const processingSince = ref(0)
const nowTick = ref(Date.now())
let pollTimer = null
let pollInFlight = false
let disposed = false
let uiTimer = null
let authFlowSeq = 0
// Boss 工具的 qrStatus 在未登录时恒返回 waiting，从不下发 expired，因此二维码过期必须由前端兜底，
// 否则会无限轮询一张失效二维码、持续触发后端登录态检查（间接访问 Boss），存在风控风险。
const QR_LIFETIME_MS = 120000
let qrDeadline = 0

function armQrDeadline() {
  const parsed = state.expiresAt ? Date.parse(state.expiresAt) : NaN
  qrDeadline = Number.isFinite(parsed) && parsed > Date.now() ? parsed : Date.now() + QR_LIFETIME_MS
}

function markQrExpired() {
  qrDeadline = 0
  state.status = 'expired'
  stopPolling()
  stopUiTicker()
}

const qrImageSrc = computed(() =>
  state.imageBase64 ? `data:${state.imageMime || 'image/png'};base64,${state.imageBase64}` : '',
)

const statusText = computed(() => {
  switch (state.status) {
    case 'checking':
      return '正在确认登录态'
    case 'qr_ready':
      return '等待扫码'
    case 'waiting':
      return '等待扫码'
    case 'scanned':
      return '已扫码，请在手机上确认登录'
    case 'confirmed':
      return '已确认，保存登录态中'
    case 'logged_in':
      return '登录成功'
    case 'expired':
      return '二维码已过期，请刷新'
    case 'cancelled':
      return '已取消'
    case 'error':
      return '登录出错'
    default:
      return state.status === 'idle' ? '初始化中' : state.status
  }
})

const stageHint = computed(() => {
  if (state.status === 'checking') return '正在校验 Boss 直聘登录态。'
  if (!state.imageBase64 && state.status === 'idle') return '正在生成二维码'
  if (state.status === 'expired') return '二维码已过期，点击“刷新二维码”重新生成'
  if (state.status === 'logged_in') return '登录完成，继续处理请求。'
  if (state.status === 'confirmed') return '手机端已确认，保存登录态中。'
  if (state.status === 'scanned') return '已扫码，请在手机端确认。'
  if (state.status === 'error') return errorMessage.value || '登录失败，请刷新二维码重试'
  return '请使用 Boss 直聘 App 扫描二维码'
})

const refreshLabel = computed(() => (state.imageBase64 ? '刷新二维码' : '获取二维码'))
const processingSeconds = computed(() =>
  processingSince.value ? Math.max(0, Math.floor((nowTick.value - processingSince.value) / 1000)) : 0,
)
const loginStages = ['扫描二维码', '手机确认', '登录校验']
const activeStageIndex = computed(() => {
  if (['qr_ready', 'waiting'].includes(state.status)) return 0
  if (state.status === 'scanned') return 1
  if (state.status === 'confirmed') return 2
  if (state.status === 'logged_in') return loginStages.length
  return -1
})
const statusTone = computed(() => {
  if (state.status === 'logged_in') return 'success'
  if (['scanned', 'confirmed'].includes(state.status)) return 'progress'
  if (['expired', 'error', 'cancelled'].includes(state.status)) return 'warning'
  return 'waiting'
})
const statusKicker = computed(() => {
  if (statusChecking.value) return '自动检测中'
  if (state.status === 'logged_in') return '登录完成'
  if (['expired', 'error', 'cancelled'].includes(state.status)) return '需要处理'
  return '实时状态'
})
const statusDetail = computed(() => {
  if (state.status === 'checking') return '正在校验已有登录态，失效后会自动生成二维码。'
  if (['qr_ready', 'waiting'].includes(state.status)) {
    return statusChecking.value ? '正在获取最新扫码结果…' : '状态每秒自动更新，扫码后无需手动刷新。'
  }
  if (state.status === 'scanned') return `已检测到扫码，请在 App 中确认。已等待 ${processingSeconds.value} 秒。`
  if (state.status === 'confirmed') return `手机端已确认，正在建立可用登录态。已处理 ${processingSeconds.value} 秒。`
  if (state.status === 'logged_in') return '登录态已保存，即将继续刚才的操作。'
  if (state.status === 'expired') return '当前二维码已失效，请刷新后重新扫描。'
  if (state.status === 'error') return errorMessage.value || '登录处理失败，请刷新二维码后重试。'
  if (state.status === 'cancelled') return '本次扫码已取消。'
  return '正在准备 Boss 直聘登录。'
})

function stageClass(index) {
  if (activeStageIndex.value === loginStages.length || index < activeStageIndex.value) return 'done'
  if (index === activeStageIndex.value) return 'current'
  return 'pending'
}

watch(
  () => props.visible,
  async (visible) => {
    disposed = !visible
    if (visible) await startLoginFlow()
    else {
      authFlowSeq++
      stopPolling()
      stopUiTicker()
    }
  },
  { immediate: true },
)

async function startLoginFlow() {
  const initial = props.data || {}
  if (initial.qrSessionId || initial.imageBase64) {
    state.qrSessionId = initial.qrSessionId || null
    state.qrId = initial.qrId || null
    state.imageBase64 = initial.imageBase64 || null
    state.imageMime = initial.imageMime || 'image/png'
    state.status = initial.status || 'qr_ready'
    state.expiresAt = initial.expiresAt || null
    if (state.qrSessionId) {
      armQrDeadline()
      startPolling()
    }
    return
  }
  if (initial.authRequired === true || initial.status === 'auth_required') {
    await refreshQr(false)
    return
  }
  await checkLoginThenMaybeQr()
}

async function checkLoginThenMaybeQr() {
  const seq = ++authFlowSeq
  loading.value = true
  errorMessage.value = ''
  stopPolling()
  startUiTicker('checking')
  state.status = 'checking'
  try {
    const status = await getBossLoginStatus(props.sessionId, null)
    if (seq !== authFlowSeq || !props.visible) return
    if (status?.ok || status?.authenticated || status?.status === 'logged_in') {
      state.status = 'logged_in'
      stopUiTicker()
      emit('logged-in')
      return
    }
    await refreshQr(false, seq)
  } catch (_) {
    if (seq === authFlowSeq && props.visible) await refreshQr(false, seq)
  } finally {
    if (seq === authFlowSeq) loading.value = false
  }
}

async function refreshQr(setLoading = true, flowSeq = ++authFlowSeq) {
  if (setLoading) loading.value = true
  errorMessage.value = ''
  stopPolling()
  stopUiTicker()
  try {
    const data = await getBossLoginQr(props.sessionId)
    if (flowSeq !== authFlowSeq || !props.visible) return
    state.qrSessionId = data.qrSessionId
    state.qrId = data.qrId
    state.imageBase64 = data.imageBase64
    state.imageMime = data.imageMime || 'image/png'
    state.status = data.status || 'qr_ready'
    state.expiresAt = data.expiresAt
    if (data.error) {
      errorMessage.value = data.error.message || '二维码生成失败'
      return
    }
    armQrDeadline()
    startPolling()
  } catch (err) {
    if (flowSeq === authFlowSeq) errorMessage.value = err.message || '获取二维码失败'
  } finally {
    if (setLoading && flowSeq === authFlowSeq) loading.value = false
  }
}

async function refreshStatus() {
  if (!state.qrSessionId || pollInFlight) return
  loading.value = true
  pollInFlight = true
  statusChecking.value = true
  try {
    const data = await getBossLoginStatus(props.sessionId, state.qrSessionId)
    applyStatus(data)
  } catch (err) {
    errorMessage.value = err.message || '刷新状态失败'
  } finally {
    pollInFlight = false
    statusChecking.value = false
    loading.value = false
  }
}

function applyStatus(data) {
  if (!data) return
  // Boss 工具在等待扫码期间会持续下发最新活码，二维码刷新后替换展示并重置过期倒计时，
  // 确保用户扫到的始终是与当前 uuid 绑定的活码，避免扫到已失效二维码导致登录确认超时。
  if (data.imageBase64 && data.imageBase64 !== state.imageBase64) {
    state.imageBase64 = data.imageBase64
    state.imageMime = data.imageMime || state.imageMime || 'image/png'
    armQrDeadline()
  }
  const nextStatus = data.status || state.status
  if (['scanned', 'confirmed'].includes(nextStatus) && state.status !== nextStatus) startUiTicker(nextStatus)
  state.status = nextStatus
  if (data.error?.message) {
    errorMessage.value = data.error.message
  } else if (state.status !== 'error') {
    errorMessage.value = ''
  }
  if (state.status === 'logged_in' || data.ok) {
    state.status = 'logged_in'
    stopPolling()
    stopUiTicker()
    emit('logged-in')
  } else if (['expired', 'error', 'cancelled'].includes(state.status)) {
    stopPolling()
    stopUiTicker()
  }
}

function startPolling() {
  stopPolling()
  scheduleNextPoll(1000)
}

function scheduleNextPoll(delay) {
  if (
    disposed ||
    !props.visible ||
    !state.qrSessionId ||
    ['logged_in', 'expired', 'error', 'cancelled'].includes(state.status)
  )
    return
  pollTimer = window.setTimeout(runPoll, delay)
}

async function runPoll() {
  pollTimer = null
  if (disposed || !props.visible || !state.qrSessionId || pollInFlight) return
  // 二维码超过有效期即停止轮询并提示刷新，避免对一张失效二维码无限轮询。
  if (qrDeadline && Date.now() >= qrDeadline && !['logged_in', 'confirmed', 'scanned'].includes(state.status)) {
    markQrExpired()
    return
  }
  pollInFlight = true
  statusChecking.value = true
  try {
    const data = await getBossLoginStatus(props.sessionId, state.qrSessionId)
    applyStatus(data)
  } catch (err) {
    errorMessage.value = err.message || '刷新状态失败'
  } finally {
    pollInFlight = false
    statusChecking.value = false
    scheduleNextPoll(['scanned', 'confirmed'].includes(state.status) ? 250 : 1000)
  }
}

function stopPolling() {
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = null
}

function startUiTicker(status) {
  processingSince.value = Date.now()
  nowTick.value = Date.now()
  if (uiTimer) window.clearInterval(uiTimer)
  uiTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
  if (status) state.status = status
}

function stopUiTicker() {
  if (uiTimer) window.clearInterval(uiTimer)
  uiTimer = null
  processingSince.value = 0
}

function handleClose() {
  authFlowSeq++
  qrDeadline = 0
  stopPolling()
  stopUiTicker()
  statusChecking.value = false
  const qrSessionId = state.qrSessionId
  const shouldCancel = qrSessionId && !['logged_in', 'expired', 'cancelled'].includes(state.status)
  state.qrSessionId = null
  state.imageBase64 = null
  state.status = 'idle'
  emit('close')
  if (shouldCancel) {
    cancelBossLogin(props.sessionId, qrSessionId).catch(() => {})
  }
}

onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  stopUiTicker()
})
</script>

<style scoped>
.boss-login-modal-mask {
  background: transparent;
}
.boss-login-modal-card {
  box-shadow: none;
}
.boss-login-embedded {
  display: flex;
  min-height: 0;
  overflow-y: auto;
  flex: 1;
  justify-content: center;
  padding: 2px 0;
}
.boss-login-embedded-card {
  width: min(520px, 100%);
  margin: auto;
  text-align: center;
}
.boss-login-embedded-card h2 {
  margin: 0 0 8px;
}
.boss-login-embedded-card > p {
  color: #667085;
  line-height: 1.6;
}
.qr-wrap {
  text-align: center;
}
.qr-wrap img {
  width: 260px;
  height: 260px;
  border-radius: 14px;
  background: #fff;
  padding: 12px;
}
.qr-hint {
  color: #667085;
  font-size: 13px;
  margin-top: 4px;
}
.login-stage-track {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 14px 0 12px;
}
.login-stage {
  position: relative;
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: #98a2b3;
  text-align: left;
}
.login-stage:not(:last-child)::after {
  position: absolute;
  top: 12px;
  right: -4px;
  width: 8px;
  height: 1px;
  background: #d9e0ec;
  content: '';
}
.login-stage > span {
  display: grid;
  width: 24px;
  height: 24px;
  flex: none;
  place-items: center;
  border: 1px solid #d9e0ec;
  border-radius: 50%;
  background: #fff;
  font-size: 11px;
  font-weight: 800;
}
.login-stage small {
  overflow: hidden;
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.login-stage.current {
  color: #3157ff;
}
.login-stage.current > span {
  border-color: #8da2ff;
  background: #eef2ff;
  box-shadow: 0 0 0 4px rgba(49, 87, 255, 0.08);
}
.login-stage.done {
  color: #16794a;
}
.login-stage.done > span {
  border-color: #79cba5;
  background: #eaf8f0;
}
.login-status-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: flex-start;
  gap: 11px;
  border: 1px solid #dfe6f1;
  border-radius: 14px;
  background: #f8fafc;
  padding: 12px 14px;
  text-align: left;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}
.login-status-indicator {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  border-radius: 50%;
  background: #98a2b3;
}
.login-status-indicator.active {
  animation: boss-status-pulse 1.1s ease-out infinite;
}
.login-status-card > div {
  min-width: 0;
}
.login-status-card small,
.login-status-card strong {
  display: block;
}
.login-status-card small {
  margin-bottom: 2px;
  color: #667085;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.06em;
}
.login-status-card strong {
  color: #172033;
  font-size: 15px;
}
.login-status-card p {
  margin: 3px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}
.login-status-card.is-progress {
  border-color: #b7c5ff;
  background: #f2f5ff;
  box-shadow: 0 8px 24px rgba(49, 87, 255, 0.08);
}
.login-status-card.is-progress .login-status-indicator {
  background: #3157ff;
}
.login-status-card.is-success {
  border-color: #a8dbc2;
  background: #eefaf4;
}
.login-status-card.is-success .login-status-indicator {
  background: #168a56;
}
.login-status-card.is-warning {
  border-color: #f2c6c2;
  background: #fff6f5;
}
.login-status-card.is-warning .login-status-indicator {
  background: #d92d20;
}
.login-status-card.is-waiting .login-status-indicator.active {
  background: #3157ff;
}
.modal-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
.modal-actions .secondary {
  flex: 1;
}
.error {
  color: #d92d20;
  font-size: 13px;
  margin-top: 8px;
}
@keyframes boss-status-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(49, 87, 255, 0.35);
  }
  70% {
    box-shadow: 0 0 0 7px rgba(49, 87, 255, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(49, 87, 255, 0);
  }
}
@media (max-width: 560px) {
  .login-stage small {
    font-size: 11px;
  }
  .modal-actions {
    flex-direction: column;
  }
}
@media (prefers-reduced-motion: reduce) {
  .login-status-indicator.active {
    animation: none;
  }
}
</style>
