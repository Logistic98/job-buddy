<template>
  <section class="chat-panel" :style="{ '--composer-space': `${composerSpace}px` }" @wheel.capture="handlePanelWheel">
    <header class="panel-head">
      <div class="bot-avatar">AI</div>
      <div>
        <h1>{{ workbenchCopy.title }}</h1>
        <p>{{ workbenchCopy.description }}</p>
      </div>
    </header>

    <div v-if="showQuickPrompts" class="quick-prompts">
      <button v-for="item in prompts" :key="item" :disabled="chat.loading" @click="$emit('ask', item)">{{ item }}</button>
    </div>

    <div ref="chatScroll" class="chat-scroll" @scroll.passive="syncAutoFollow">
      <div class="messages">
        <template v-for="msg in visibleMessages" :key="msg.id">
          <details
            v-if="showProcessPanel(msg)"
            class="tool-process"
            :open="isOpen('panel', msg)"
            @toggle="setOpen('panel', msg, $event)"
          >
            <summary>
              <span>思考与工具执行过程</span>
              <strong v-if="!isStreamingMsg(msg) && panelLatestEvent(msg)" class="tool-latest-summary">
                <b :class="['tool-dot', panelLatestEvent(msg).status]"></b>
                <em>{{ panelLatestEvent(msg).name }}</em>
                <small>{{ panelLatestEvent(msg).detail }}</small>
              </strong>
              <b>{{ panelStatusText(msg) }}</b>
            </summary>
            <div v-if="isStreamingMsg(msg)" class="tool-thinking-step">
              <div class="loading-spinner"></div>
              <div class="loading-copy">
                <strong>{{ loadingTitle }}</strong>
                <span>{{ loadingSummary }}</span>
              </div>
              <div class="loading-count">{{ completedToolCount }}/{{ visibleToolEvents.length || 1 }}</div>
            </div>
            <div class="tool-step-list">
              <details v-for="item in messageToolEvents(msg)" :key="item.id" class="tool-step">
                <summary>
                  <b :class="['tool-dot', item.status]"></b>
                  <strong>{{ item.name }}</strong>
                  <small>{{ item.detail }}</small>
                </summary>
                <div class="tool-detail">
                  <template v-if="detailEntries(item.payload).length">
                    <div v-for="entry in detailEntries(item.payload)" :key="entry.key" class="tool-detail-row">
                      <span>{{ entry.key }}</span>
                      <em>{{ entry.value }}</em>
                    </div>
                  </template>
                  <p v-else class="tool-detail-empty">暂无明细</p>
                  <details v-if="detailRawJson(item.payload)" class="tool-raw-json">
                    <summary>查看原始 JSON</summary>
                    <pre>{{ detailRawJson(item.payload) }}</pre>
                  </details>
                </div>
              </details>
              <details
                v-if="msg.reasoning && msg.reasoning.trim()"
                class="tool-step reasoning-step"
                :open="isOpen('reasoning', msg)"
                @toggle="setOpen('reasoning', msg, $event)"
              >
                <summary>
                  <b :class="['tool-dot', isStreamingMsg(msg) && !String(msg.content || '').trim() ? 'running' : 'success']"></b>
                  <strong>模型推理</strong>
                  <small>{{ isStreamingMsg(msg) && !String(msg.content || '').trim() ? '正在逐字思考…' : `${msg.reasoning.length} 字` }}</small>
                </summary>
                <pre class="reasoning-text">{{ msg.reasoning }}</pre>
              </details>
            </div>
          </details>
          <article v-if="msg.role !== 'assistant' || assistantBubbleVisible(msg)" :class="['msg', msg.role, { pending: msg.pending }]">
            <div class="avatar">{{ msg.role === 'user' ? '我' : '职' }}</div>
            <div class="bubble">
              <template v-if="msg.pending">
                <span class="typing-line"><i></i><i></i><i></i>{{ loadingTitle }}</span>
                <small class="typing-subtitle">{{ loadingSummary }}</small>
              </template>
              <template v-else-if="msg.role === 'assistant'">
                <MarkdownRender
                  class="chat-markdown"
                  custom-id="job-chat"
                  :content="normalizeMarkdownContent(msg.content || '')"
                  :final="isMessageFinal(msg)"
                  html-policy="escape"
                  :max-live-nodes="0"
                  :batch-rendering="true"
                  :render-batch-size="16"
                  :render-batch-delay="8"
                  :render-batch-budget-ms="4"
                  :fade="false"
                  :typewriter="false"
                  :smooth-streaming="false"
                />
                <div v-if="msg.jobCards?.length" class="chat-job-cards">
                  <div class="chat-job-cards-head">
                    <strong>岗位推荐</strong>
                    <span>{{ msg.jobCards.length }} 个</span>
                  </div>
                  <article v-for="(item, idx) in msg.jobCards" :key="jobId(item, idx)" class="chat-job-card">
                    <div class="chat-job-main">
                      <strong>{{ jobTitle(item) }}</strong>
                      <b>{{ salaryText(item) }}</b>
                    </div>
                    <p>{{ company(item) }} · {{ locationText(item) }} · {{ experienceText(item) }}</p>
                    <p v-if="chatJobSummary(item)" class="chat-job-summary">{{ chatJobSummary(item) }}</p>
                    <div class="chat-job-meta">
                      <span v-if="item.matchScore">{{ item.matchScore }} 分</span>
                      <span v-for="tag in jobTags(item)" :key="tag">{{ tag }}</span>
                    </div>
                    <div class="chat-job-actions">
                      <a v-if="originalUrl(item)" class="chat-origin-link" :href="originalUrl(item)" target="_blank" rel="noreferrer" title="在外部浏览器打开 Boss 原岗位" @click.stop>Boss 原岗位</a>
                      <button type="button" :disabled="isChatJdLoading(item, idx)" @click.stop="toggleChatJd(item, idx)">{{ chatJdButtonText(item, idx) }}</button>
                      <button type="button" :disabled="chat.loading" @click.stop="analyzeChatJob(item)">分析此岗位</button>
                      <button type="button" :class="{ active: job.isFavorite(item) }" @click.stop="job.toggleFavorite(item)">{{ job.isFavorite(item) ? '已收藏' : '收藏' }}</button>
                    </div>
                    <p v-if="chatJdError(item, idx)" class="chat-job-jd-error">{{ chatJdError(item, idx) }}</p>
                    <div v-if="isChatJdOpen(item, idx) && chatJobFullJd(item)" class="chat-job-jd-full">{{ chatJobFullJd(item) }}</div>
                  </article>
                  <div class="chat-job-more">
                    <button type="button" :disabled="chat.loading" @click="requestMoreJobs">换一批 / 更多岗位</button>
                    <small>点击后再加载下一批岗位。</small>
                  </div>
                </div>
              </template>
              <template v-else>{{ msg.content || '' }}</template>
            </div>
          </article>
        </template>
        <article v-if="showProcessingHint" class="msg assistant">
          <div class="avatar">职</div>
          <div class="bubble">
            <span class="typing-line"><i></i><i></i><i></i>{{ loadingTitle }}</span>
            <small class="typing-subtitle">{{ loadingSummary }}</small>
          </div>
        </article>
      </div>
</div>

    <form ref="composerEl" class="composer" :class="{ busy: chat.loading }" @submit.prevent="submit">
      <div class="composer-resume-bar">
        <button type="button" class="composer-resume-chip" @click="$emit('select-resume')">
          <span>当前简历</span>
          <strong>{{ resumeLabel }}</strong>
        </button>
        <button type="button" class="composer-resume-action" @click="$emit('select-resume')">选择简历</button>
      </div>
      <textarea v-model="input" :disabled="chat.loading" :placeholder="composerPlaceholder" @keydown.enter.exact.prevent="submit" />
      <p v-if="profileContextSummary" class="composer-profile-context">本次已使用：{{ profileContextSummary }}</p>
      <div class="composer-footer">
        <span :class="{ error: chat.serviceError }">{{ footerText }}</span>
        <div class="composer-actions">
          <button v-if="canStop" type="button" class="stop-btn" @click="chat.stop">停止</button>
          <button :disabled="chat.loading || !input.trim()">发送</button>
        </div>
      </div>
    </form>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownRender from 'markstream-vue'
import { useChatStore } from '../stores/chat'
import { useJobStore } from '../stores/job'
import { fetchJobDetail } from '../api/jobs'
defineEmits(['ask', 'select-resume'])
const props = defineProps({ resumeId: { type: String, default: '' }, resumeName: { type: String, default: '' } })
const chat = useChatStore()
const job = useJobStore()
const input = ref('')
const chatScroll = ref(null)
const composerEl = ref(null)
const composerSpace = ref(190)
const nowTick = ref(Date.now())
let elapsedTimer = null
let composerResizeObserver = null
// 过程面板/推理步骤的展开状态按消息 id 记忆，完成后不强制收起，避免“跑完过程就没了”。
const panelOpenState = ref({})
// 岗位职位描述（JD）懒加载状态：按 jobId 记忆加载中/错误/展开，仅在用户点击时才请求 Boss。
const jdLoadingKeys = ref(new Set())
const jdErrorMap = ref({})
const jdExpandedKeys = ref(new Set())
// 本次会话中最近一条流式生成的助手消息 id，完成后其过程面板仍默认展开。
const lastStreamedAssistantId = ref('')
const defaultWorkbenchCopy = {
  title: '求职工作台',
  description: '支持岗位推荐、简历分析、面试准备、笔试计划、项目深挖和求职进展梳理。',
  placeholder: '例如：筛选上海大模型应用开发 40-50K 岗位，或分析简历是否匹配 Agent 应用开发岗位',
  quick_prompts: ['帮我筛选上海大模型应用开发 40-50K 岗位', '分析当前简历是否匹配 Agent 应用开发岗位', '根据大模型应用开发岗位生成面试准备清单', '帮我准备 RAG、Tool Calling 和 Agent 方向笔试计划', '围绕我的大模型应用项目生成面试深挖问题', '帮我记录这家 AI 公司进入一面阶段'],
}
const workbenchCopy = defaultWorkbenchCopy
const profileContextSummary = computed(() => {
  const ctx = chat.lastPersonalContextEvent || {}
  const sources = Array.isArray(ctx.sources) ? ctx.sources : []
  if (sources.length) return sources.join(' / ')
  return String(ctx.summary || '').slice(0, 80)
})
const prompts = computed(() => defaultWorkbenchCopy.quick_prompts)

const hasUserMessage = computed(() => chat.messages.some(msg => msg.role === 'user'))
const showQuickPrompts = computed(() => !hasUserMessage.value && !chat.loading)
const visibleMessages = computed(() => chat.messages.filter(msg => {
  if (msg.role !== 'assistant') return true
  if (msg.pending) return false
  const hasContent = !!String(msg.content || '').trim()
  const hasCards = Array.isArray(msg.jobCards) && msg.jobCards.length > 0
  const hasTools = Array.isArray(msg.toolEvents) && msg.toolEvents.length > 0
  const hasReasoning = !!String(msg.reasoning || '').trim()
  return hasContent || hasCards || hasTools || hasReasoning
}))
const hiddenToolEventIds = new Set(['sse_connect', 'request_init'])
const visibleToolEvents = computed(() => chat.toolEvents.filter(item => item && !hiddenToolEventIds.has(item.id)))
const latestToolEvent = computed(() => visibleToolEvents.value[visibleToolEvents.value.length - 1] || null)
const currentToolEvent = computed(() => [...visibleToolEvents.value].reverse().find(item => item.status === 'running') || latestToolEvent.value || null)
const completedToolCount = computed(() => visibleToolEvents.value.filter(item => item.status === 'success').length)
const lastAssistantId = computed(() => {
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    if (chat.messages[i]?.role === 'assistant') return chat.messages[i].id
  }
  return ''
})
const lastAssistantContent = computed(() => {
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    if (chat.messages[i]?.role === 'assistant') return String(chat.messages[i].content || '').trim()
  }
  return ''
})
// 仅在还没有任何工具事件的最初瞬间显示独立的处理中气泡；一旦执行过程面板出现，就交给面板展示，避免重复。
const showProcessingHint = computed(() => chat.loading && !lastAssistantContent.value && visibleToolEvents.value.length === 0)
const loadingTitle = computed(() => currentToolEvent.value?.name || '正在处理')
const currentToolElapsedSeconds = computed(() => {
  const startedAt = Number(currentToolEvent.value?.startedAt || 0)
  if (!startedAt) return 0
  return Math.max(0, Math.floor((nowTick.value - startedAt) / 1000))
})
const loadingSummary = computed(() => {
  const detail = currentToolEvent.value?.detail || '请求已提交，正在初始化会话和服务链路，请稍候。'
  if (!chat.loading || !currentToolElapsedSeconds.value) return detail
  return `${detail}（已等待 ${currentToolElapsedSeconds.value} 秒）`
})
const toolProcessText = computed(() => {
  const total = visibleToolEvents.value.length
  if (chat.loading) return `进行中 · ${completedToolCount.value}/${total || 1} 步 · ${currentToolElapsedSeconds.value} 秒`
  return `已完成 ${completedToolCount.value}/${total || completedToolCount.value} 步`
})
const composerPlaceholder = computed(() => chat.loading ? '正在处理当前请求，请等待结果返回后再继续输入' : defaultWorkbenchCopy.placeholder)
const resumeLabel = computed(() => props.resumeName || (props.resumeId ? '已关联简历' : '未选择简历'))
const canStop = computed(() => chat.loading || !!chat.abortController)

const footerText = computed(() => {
  if (chat.serviceError) return chat.serviceError
  if (chat.loading) return loadingSummary.value
  return props.resumeId ? '已关联当前简历' : '未关联简历，可先上传后做匹配'
})
function isMessageFinal(msg) {
  if (!msg || msg.role !== 'assistant') return true
  const lastAssistant = [...chat.messages].reverse().find(item => item.role === 'assistant')
  return !chat.loading || !lastAssistant || lastAssistant.id !== msg.id
}

function jobTitle(item) { return item.jobName || item.job_name || item.title || item.name || '未知岗位' }
function company(item) { return item.brandName || item.companyName || item.company || '未知公司' }
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
function salaryText(item) {
  const value = item.salaryDesc || item.salary_desc || item.salary || item.salaryText || item.salaryName || item.salaryRange || item.jobSalary || item.pay || item.wage || item.compensation || ''
  return String(value || '').trim() || '薪资未标注'
}
function chatJobSummary(item) {
  const parts = [item.jobDegree || item.education, item.companyIndustry || item.brandIndustry || item.industry, item.companyScale || item.brandScaleName, item.companyStage || item.brandStageName].filter(Boolean)
  return parts.join(' · ')
}
function jobId(item, idx) { return String(item.securityId || item.id || item.jobId || item.encryptJobId || `job_${idx}`) }
function chatJobFullJd(item) {
  return String(item.jobDescription || item.description || item.postDescription || item.jobDesc || item.jobSecText || item.detailText || '').trim()
}
function isChatJdLoading(item, idx) { return jdLoadingKeys.value.has(jobId(item, idx)) }
function chatJdError(item, idx) { return jdErrorMap.value[jobId(item, idx)] || '' }
function isChatJdOpen(item, idx) { return jdExpandedKeys.value.has(jobId(item, idx)) }
function chatJdButtonText(item, idx) {
  if (isChatJdLoading(item, idx)) return '加载中'
  if (isChatJdOpen(item, idx)) return '收起职位描述'
  return chatJobFullJd(item) ? '查看职位描述' : '加载职位描述'
}
async function toggleChatJd(item, idx) {
  const key = jobId(item, idx)
  if (isChatJdOpen(item, idx)) {
    jdExpandedKeys.value.delete(key)
    jdExpandedKeys.value = new Set(jdExpandedKeys.value)
    return
  }
  if (!chatJobFullJd(item)) {
    if (jdLoadingKeys.value.has(key)) return
    const securityId = item.securityId || item.security_id || item.encryptJobId || item.encrypt_job_id || ''
    const detailUrl = originalUrl(item)
    if (!securityId && !detailUrl) {
      jdErrorMap.value = { ...jdErrorMap.value, [key]: '缺少 Boss 原岗位链接或 securityId，无法安全加载职位描述。' }
      return
    }
    jdLoadingKeys.value.add(key)
    jdLoadingKeys.value = new Set(jdLoadingKeys.value)
    jdErrorMap.value = { ...jdErrorMap.value, [key]: '' }
    try {
      const detail = await fetchJobDetail(securityId, detailUrl)
      if (detail && typeof detail === 'object') Object.assign(item, detail)
    } catch (error) {
      if (error?.authRequired) {
        chat.authRequired = error.authData || { message: error.message }
        return
      }
      jdErrorMap.value = { ...jdErrorMap.value, [key]: error?.message || '获取岗位详情失败' }
      return
    } finally {
      jdLoadingKeys.value.delete(key)
      jdLoadingKeys.value = new Set(jdLoadingKeys.value)
    }
    if (!chatJobFullJd(item)) {
      jdErrorMap.value = { ...jdErrorMap.value, [key]: '未获取到职位描述，请稍后重试或打开 Boss 原岗位查看。' }
      return
    }
  }
  jdExpandedKeys.value.add(key)
  jdExpandedKeys.value = new Set(jdExpandedKeys.value)
}
function jobTags(item) {
  return [...(item.skills || []), ...(item.skillList || []), ...(item.jobLabels || []), item.brandIndustry, item.industry]
    .map(x => String(x || '').trim())
    .filter(x => x && !/^\d{4,}$/.test(x))
    .slice(0, 6)
}

function messageToolEvents(msg) {
  const events = Array.isArray(msg?.toolEvents) ? msg.toolEvents : []
  return events.filter(item => item && !hiddenToolEventIds.has(item.id))
}

function isStreamingMsg(msg) {
  return chat.loading && msg?.role === 'assistant' && msg.id === lastAssistantId.value
}

function showProcessPanel(msg) {
  if (!msg || msg.role !== 'assistant') return false
  return messageToolEvents(msg).length > 0 || !!String(msg.reasoning || '').trim() || isStreamingMsg(msg)
}

function assistantBubbleVisible(msg) {
  if (msg.pending) return true
  return !!String(msg.content || '').trim() || (Array.isArray(msg.jobCards) && msg.jobCards.length > 0)
}

function isOpen(kind, msg) {
  const stored = panelOpenState.value[`${kind}:${msg.id}`]
  if (stored !== undefined) return stored
  return isStreamingMsg(msg) || msg.id === lastStreamedAssistantId.value
}

function setOpen(kind, msg, event) {
  panelOpenState.value[`${kind}:${msg.id}`] = event?.target?.open !== false
}

function panelLatestEvent(msg) {
  const events = messageToolEvents(msg)
  return events[events.length - 1] || null
}

function panelStatusText(msg) {
  if (isStreamingMsg(msg)) return toolProcessText.value
  const events = messageToolEvents(msg)
  const done = events.filter(item => item.status === 'success').length
  return `已完成 ${done}/${events.length || done} 步`
}

function normalizeMarkdownContent(content) {
  return linkifyBareUrls(String(content || ''))
}

function linkifyBareUrls(content) {
  const urlPattern = /https?:\/\/[^\s<>"'，。；、！？（）()\[\]{}【】《》\u4e00-\u9fff]+/g
  return String(content || '').replace(urlPattern, (raw, offset, source) => {
    const before = source.slice(Math.max(0, offset - 3), offset)
    if (before.endsWith('](') || before.endsWith(']（') || before.endsWith('<')) return raw
    const trailing = raw.match(/[.,;:!?]+$/)?.[0] || ''
    const url = trailing ? raw.slice(0, -trailing.length) : raw
    if (!url) return raw
    return `[${url}](${url})${trailing}`
  })
}

// 推理/过程面板默认完整展示，不做任何文本截断：用户要看到全部推理依据。
function previewValue(value) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return `${value.length} 项`
  try {
    return JSON.stringify(value)
  } catch (_) {
    return String(value)
  }
}

function detailEntries(payload) {
  if (payload === null || payload === undefined || payload === '') return []
  if (typeof payload === 'string') {
    return [{ key: '内容', value: payload }]
  }
  if (Array.isArray(payload)) return [{ key: '条目数', value: `${payload.length} 项` }]
  if (typeof payload !== 'object') return [{ key: '值', value: String(payload) }]
  return Object.entries(payload).map(([key, value]) => ({ key, value: previewValue(value) }))
}

function detailRawJson(payload) {
  if (payload === null || payload === undefined || payload === '') return ''
  if (typeof payload === 'string') return payload.length > 400 ? payload : ''
  try {
    const text = JSON.stringify(payload, null, 2)
    if (text.length <= 240) return ''
    return text
  } catch (_) {
    return ''
  }
}

function requestMoreJobs() {
  if (chat.loading) return
  chat.send('换一批', props.resumeId)
}

function analyzeChatJob(item) {
  if (chat.loading || !item) return
  const text = `请针对「${jobTitle(item)} / ${company(item)}」这个岗位，结合当前简历分析匹配度、优势、差距、面试准备重点和是否建议投递。`
  chat.send(text, props.resumeId, { selectedJob: item })
}

onMounted(() => {
  elapsedTimer = window.setInterval(() => { nowTick.value = Date.now() }, 1000)
  updateComposerSpace()
  if (window.ResizeObserver && composerEl.value) {
    composerResizeObserver = new ResizeObserver(updateComposerSpace)
    composerResizeObserver.observe(composerEl.value)
  }
})

onBeforeUnmount(() => {
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  if (composerResizeObserver) composerResizeObserver.disconnect()
})

function updateComposerSpace() {
  nextTick(() => {
    const el = composerEl.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    composerSpace.value = Math.ceil(rect.height + 28)
  })
}

function handlePanelWheel(event) {
  const el = chatScroll.value
  if (!el || !event || !event.deltaY) return
  // 用户向上滚动时同步关闭贴底跟随：wheel 在 scroll 事件之前触发，
  // 若只依赖 scroll 监听，流式逐 token 的 scrollToBottom 会抢在监听器前把位置拉回底部，用户永远滚不上去。
  if (event.deltaY < 0 && el.scrollHeight > el.clientHeight) autoFollow.value = false
  const target = event.target
  if (target?.closest?.('.chat-scroll')) return
  if (target?.closest?.('textarea,input,select')) return
  const maxScrollTop = Math.max(0, el.scrollHeight - el.clientHeight)
  if (maxScrollTop <= 0) return
  const nextTop = Math.max(0, Math.min(maxScrollTop, el.scrollTop + event.deltaY))
  if (nextTop === el.scrollTop) return
  el.scrollTop = nextTop
  event.preventDefault()
}

// 贴底跟随：用户上滚离开底部后暂停自动滚动，滚回底部附近时恢复，避免流式期间用户无法回看历史。
const autoFollow = ref(true)
const followThresholdPx = 48

function syncAutoFollow() {
  const el = chatScroll.value
  if (!el) return
  autoFollow.value = el.scrollHeight - el.scrollTop - el.clientHeight <= followThresholdPx
}

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = chatScroll.value
    if (!el) return
    if (!force && !autoFollow.value) return
    // 必须用 instant：behavior 'auto' 会沿用 CSS scroll-behavior，若容器是 smooth，
    // 流式逐 token 触发的连续滚动会不断重启平滑动画，位置永远停在起点（实测卡死在顶部）。
    el.scrollTo({ top: el.scrollHeight, behavior: 'instant' })
    autoFollow.value = true
  })
}

watch(
  () => chat.messages.length,
  (next, prev) => {
    // 新消息加入（用户发送或助手占位）时强制回到底部并恢复跟随。
    if (next > (prev || 0)) scrollToBottom(true)
  },
  { flush: 'post' },
)

watch(
  () => chat.sessionId,
  () => {
    scrollToBottom(true)
  },
  { flush: 'post' },
)

watch(
  () => [
    chat.loading,
    chat.messages[chat.messages.length - 1]?.content || '',
    chat.messages[chat.messages.length - 1]?.reasoning || '',
    visibleToolEvents.value.length,
    visibleToolEvents.value.map(item => `${item.id}:${item.status}:${item.detail || ''}`).join('|'),
  ],
  () => {
    scrollToBottom()
  },
  { flush: 'post' },
)

watch(
  () => [chat.loading, lastAssistantId.value],
  ([loading, id]) => {
    if (loading && id) lastStreamedAssistantId.value = id
  },
)

async function submit() {
  const text = input.value.trim()
  if (!text || chat.loading) return
  input.value = ''
  const sent = await chat.send(text, props.resumeId)
  if (!sent && !chat.loading) input.value = text
}
defineExpose({
  submitPrompt: async text => {
    if (!text || chat.loading) return false
    return chat.send(text, props.resumeId)
  },
})
</script>
