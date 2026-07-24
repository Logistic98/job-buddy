<template>
  <div>
    <p v-if="error" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
      {{ error }}
    </p>
    <div v-if="loading" class="empty-state">
      <strong>正在加载设置</strong>
      <p>请稍候。</p>
    </div>
    <section v-if="services" class="settings-section">
      <div class="setting-card">
        <div>
          <h3>服务地址</h3>
          <p>配置各后端服务的实际访问地址。保存后，健康检查会立即使用新地址。</p>
        </div>
        <div class="form-grid all-fields-required">
          <label
            ><span>Intent URL</span
            ><input
              aria-required="true"
              v-model.trim="services.intentUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8020"
          /></label>
          <label
            ><span>Runtime URL</span
            ><input
              aria-required="true"
              v-model.trim="services.runtimeUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8010"
          /></label>
          <label
            ><span>Memory URL</span
            ><input
              aria-required="true"
              v-model.trim="services.memoryUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8030"
          /></label>
          <label
            ><span>Tool URL</span
            ><input
              aria-required="true"
              v-model.trim="services.toolUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8040"
          /></label>
          <label
            ><span>Eval URL</span
            ><input
              aria-required="true"
              v-model.trim="services.evalUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8050"
          /></label>
          <label
            ><span>Sandbox URL</span
            ><input
              aria-required="true"
              v-model.trim="services.sandboxUrl"
              type="url"
              maxlength="512"
              placeholder="例如 http://localhost:8061"
          /></label>
          <label
            ><span>连接超时（秒）</span
            ><input
              aria-required="true"
              :value="durationSeconds(services.connectTimeout)"
              type="number"
              min="1"
              max="3600"
              step="1"
              placeholder="请输入 1-3600 的整数，例如 2"
              @input="setDuration('connectTimeout', $event.target.value)"
          /></label>
          <label
            ><span>读取超时（秒）</span
            ><input
              aria-required="true"
              :value="durationSeconds(services.readTimeout)"
              type="number"
              min="1"
              max="3600"
              step="1"
              placeholder="请输入 1-3600 的整数，例如 75"
              @input="setDuration('readTimeout', $event.target.value)"
          /></label>
        </div>
      </div>
      <div class="setting-card service-health-card">
        <div class="service-health-head">
          <div>
            <h3>服务健康状态</h3>
            <p>后台持续探测服务状态，点击服务可查看最近一段时间的监测记录。</p>
          </div>
          <button class="secondary-btn" :disabled="loading" @click="forceRefreshHealth">刷新状态</button>
        </div>
        <div class="source-list service-health-list">
          <article
            v-for="source in serviceRows"
            :key="source.id"
            :class="['source-item', 'service-health-item', { expanded: expandedServiceId === source.id }]"
          >
            <button class="service-health-summary" type="button" @click="toggleHistory(source.id)">
              <div class="service-health-main">
                <strong>{{ source.name }}</strong>
                <p>{{ source.healthUrl || source.url || '未配置' }}</p>
              </div>
              <div class="service-health-status">
                <span :class="['health-state', source.status]">{{ statusText(source.status) }}</span
                ><span class="service-uptime">{{ source.uptimeText }}</span>
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
              <span>{{ source.historyStartText }}</span
              ><span>{{ source.history.length ? `${source.uptimeText} 可用率` : '暂无监测记录' }}</span
              ><span>现在</span>
            </div>
            <div v-if="expandedServiceId === source.id" class="service-history-detail">
              <div class="service-history-meta">
                <span>监测次数：{{ source.history.length }}</span
                ><span>最近检查：{{ formatTime(source.checkedAt) || '暂无' }}</span
                ><span>状态：{{ source.message || statusText(source.status) }}</span>
              </div>
              <div class="service-history-list">
                <div
                  v-for="(point, index) in source.history.slice().reverse()"
                  :key="`${source.id}-detail-${point.checkedAt || index}`"
                  class="service-history-row"
                >
                  <span :class="['history-dot', point.status]"></span><strong>{{ statusText(point.status) }}</strong
                  ><span>{{ formatFullTime(point.checkedAt) }}</span
                  ><em>{{ point.message || '-' }}</em>
                </div>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { getSettings, refreshServiceHealth } from '../../api/settings'
import { useScopedSettings } from '../../composables/useScopedSettings'
import { validateHttpUrl, validateInteger } from '../../utils/formValidation'

const emit = defineEmits(['state-change'])
const normalizeServices = (value) => ({
  intentUrl: value?.intentUrl || '',
  runtimeUrl: value?.runtimeUrl || '',
  memoryUrl: value?.memoryUrl || '',
  toolUrl: value?.toolUrl || '',
  evalUrl: value?.evalUrl || '',
  sandboxUrl: value?.sandboxUrl || '',
  connectTimeout: value?.connectTimeout || '2s',
  readTimeout: value?.readTimeout || '75s',
})
const {
  value: services,
  loading,
  saving,
  error,
  dirty,
  load,
  save: saveServices,
} = useScopedSettings('services', normalizeServices)
const serviceStatuses = ref({})
const expandedServiceId = ref('')
let timer = null
const serviceRows = computed(() => Object.values(serviceStatuses.value || {}).map(enrichServiceRow))

watch(
  [dirty, saving, loading],
  () => emit('state-change', { key: 'services', dirty: dirty.value, saving: saving.value, loading: loading.value }),
  { immediate: true },
)
onMounted(async () => {
  const settings = await load()
  recordStatuses(settings?.serviceStatuses)
})
onActivated(startPolling)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
defineExpose({ save })

async function save() {
  error.value = ''
  try {
    for (const [key, label] of [
      ['intentUrl', 'Intent URL'],
      ['runtimeUrl', 'Runtime URL'],
      ['memoryUrl', 'Memory URL'],
      ['toolUrl', 'Tool URL'],
      ['evalUrl', 'Eval URL'],
      ['sandboxUrl', 'Sandbox URL'],
    ]) {
      services.value[key] = validateHttpUrl(services.value[key], label, { required: true })
    }
    validateInteger(durationSeconds(services.value.connectTimeout), '连接超时', { min: 1, max: 3600 })
    validateInteger(durationSeconds(services.value.readTimeout), '读取超时', { min: 1, max: 3600 })
  } catch (err) {
    error.value = err.message
    return false
  }
  return saveServices()
}

function startPolling() {
  stopPolling()
  timer = window.setInterval(() => {
    if (!loading.value && !saving.value) loadLatestHealth()
  }, 10000)
}
function stopPolling() {
  if (timer) window.clearInterval(timer)
  timer = null
}
async function loadLatestHealth() {
  try {
    const latest = await getSettings()
    recordStatuses(latest?.serviceStatuses)
  } catch (err) {
    error.value = err.message || '服务健康状态加载失败'
  }
}
async function forceRefreshHealth() {
  try {
    recordStatuses(await refreshServiceHealth())
  } catch (err) {
    error.value = err.message || '服务健康状态刷新失败'
  }
}
function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(max, Math.max(min, Math.round(number))) : fallback
}
function durationSeconds(value) {
  if (value == null || value === '') return ''
  const text = String(value).trim().toUpperCase()
  const isoMatch = text.match(/^PT(?:(\d+)M)?(?:(\d+)S)?$/)
  if (isoMatch) return String(Number(isoMatch[1] || 0) * 60 + Number(isoMatch[2] || 0))
  const secondMatch = text.match(/^(\d+)(?:S|秒)?$/)
  return secondMatch ? secondMatch[1] : String(value)
}
function setDuration(key, value) {
  if (services.value) services.value[key] = `${clampNumber(value, 1, 3600, 1)}s`
}
function toggleHistory(id) {
  expandedServiceId.value = expandedServiceId.value === id ? '' : id
}
function enrichServiceRow(source) {
  const row = source || {}
  const history = Array.isArray(row.history) ? row.history : []
  const successCount = history.filter((point) => point.status === 'running').length
  const uptime = history.length ? Math.round((successCount / history.length) * 10000) / 100 : 0
  return {
    ...row,
    history,
    uptimeText: history.length ? `${uptime.toFixed(2)}%` : '暂无数据',
    historyStartText: history.length ? formatTime(history[0].checkedAt) : '开始监测',
  }
}
function recordStatuses(statuses) {
  if (statuses) serviceStatuses.value = statuses
}
function historyPointTitle(point) {
  return `${formatFullTime(point.checkedAt)} · ${statusText(point.status)}${point.message ? ` · ${point.message}` : ''}`
}
function statusText(status) {
  return { running: '运行成功', down: '未运行', not_configured: '未配置', unknown: '未知' }[status] || status || '未知'
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
function formatFullTime(value) {
  return value
    ? new Date(value).toLocaleString(undefined, {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      })
    : ''
}
</script>
