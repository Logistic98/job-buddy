<template>
  <section class="chat-panel" :style="{ '--composer-space': `${composerSpace}px` }" @wheel.capture="handlePanelWheel">
    <div ref="chatScroll" class="chat-scroll" @scroll.passive="syncAutoFollow">
      <section v-if="showQuickPrompts" class="quick-prompts" aria-label="示例问题">
        <div class="quick-prompts-card">
          <p class="quick-prompts-guide">
            您好，我会结合您的求职画像、简历、收藏岗位及求职进展，动态检索 Boss
            直聘数据，为您提供岗位推荐、简历分析、面试辅导、技术答疑等服务。例如，您可以这样问：
          </p>
          <div class="quick-prompts-list">
            <button
              v-for="item in prompts"
              :key="item"
              type="button"
              :disabled="chat.loading"
              @click="applyPrompt(item)"
            >
              <span>{{ item }}</span>
            </button>
          </div>
        </div>
      </section>
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
            <div v-if="isStreamingMsg(msg) && activeRunningToolEventFor(msg)" class="tool-thinking-step">
              <div class="loading-spinner"></div>
              <div class="loading-copy">
                <strong>{{ panelLoadingTitle(msg) }}</strong>
                <span>{{ panelLoadingSummary(msg) }}</span>
              </div>
              <div class="loading-count">
                <strong>{{ completedToolCountFor(msg) }}/{{ messageToolEvents(msg).length || 1 }}</strong>
                <small>{{ activeRunningTimingTextFor(msg) }}</small>
              </div>
            </div>
            <div class="tool-step-list">
              <article v-for="item in processStepEvents(msg)" :key="item.id" class="tool-step-card">
                <header>
                  <b :class="['tool-dot', item.status]"></b>
                  <strong>{{ item.name }}</strong>
                  <div class="tool-step-meta">
                    <time v-if="toolEventTimingText(item)">{{ toolEventTimingText(item) }}</time>
                    <span :class="['tool-state', item.status]">{{ toolStateText(item.status) }}</span>
                  </div>
                </header>
                <div v-if="item.detail || toolEventHighlights(item).length" class="tool-step-overview">
                  <p v-if="item.detail" class="tool-step-summary">{{ item.detail }}</p>
                  <span v-else></span>
                  <button
                    v-if="toolEventHighlights(item).length"
                    type="button"
                    class="tool-detail-toggle"
                    :aria-expanded="isToolDetailOpen(msg, item)"
                    @click="toggleToolDetail(msg, item)"
                  >
                    {{ isToolDetailOpen(msg, item) ? '收起详情' : '展开详情' }}
                    <small>{{ toolDetailCount(item) }} 项</small>
                  </button>
                </div>
                <template v-if="isToolDetailOpen(msg, item)">
                  <dl class="tool-key-details">
                    <div v-for="detail in toolEventHighlights(item)" :key="`${item.id}:${detail.label}`">
                      <dt>{{ detail.label }}</dt>
                      <dd>{{ detail.value }}</dd>
                    </div>
                  </dl>
                  <section v-if="sandboxExecutionDetail(item)" class="tool-execution-detail">
                    <SandboxExecutionConsole
                      :detail="sandboxExecutionDetail(item)"
                      :execution-key="executionCopyKey(msg, item)"
                    />
                  </section>
                </template>
              </article>
              <details
                v-if="msg.reasoning && msg.reasoning.trim()"
                class="tool-step reasoning-step"
                :open="isOpen('reasoning', msg)"
                @toggle="setOpen('reasoning', msg, $event)"
              >
                <summary>
                  <b
                    :class="[
                      'tool-dot',
                      isStreamingMsg(msg) && !String(msg.content || '').trim() ? 'running' : 'success',
                    ]"
                  ></b>
                  <strong>模型思路摘要</strong>
                  <small>{{
                    isStreamingMsg(msg) && !String(msg.content || '').trim()
                      ? '正在提取关键思路…'
                      : `精选 ${reasoningHighlights(msg.reasoning).length} 条关键内容`
                  }}</small>
                </summary>
                <ul class="reasoning-highlights">
                  <li v-for="(line, index) in reasoningHighlights(msg.reasoning)" :key="index">{{ line }}</li>
                </ul>
              </details>
            </div>
          </details>
          <article
            v-if="msg.role !== 'assistant' || assistantBubbleVisible(msg)"
            :class="['msg', msg.role, { pending: msg.pending, 'has-job-cards': msg.jobCards?.length }]"
          >
            <div class="avatar">{{ msg.role === 'user' ? '我' : '职' }}</div>
            <div class="bubble">
              <template v-if="msg.pending">
                <span class="typing-line"><i></i><i></i><i></i>{{ loadingTitle }}</span>
                <small class="typing-subtitle">{{ loadingSummary }}</small>
              </template>
              <template v-else-if="msg.role === 'assistant'">
                <MarkdownContent
                  class="chat-markdown"
                  custom-id="job-chat"
                  :content="normalizeAssistantMarkdown(msg.content || '')"
                  :final="isMessageFinal(msg)"
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
                      <span v-for="tag in jobTags(item)" :key="tag">{{ tag }}</span>
                    </div>
                    <div class="chat-job-actions">
                      <a
                        v-if="originalUrl(item)"
                        class="chat-origin-link"
                        :href="originalUrl(item)"
                        target="_blank"
                        rel="noreferrer"
                        title="在外部浏览器打开 Boss 原岗位"
                        @click.stop
                        >Boss 原岗位</a
                      >
                      <button type="button" :disabled="job.isLoadingDetail(item)" @click.stop="toggleChatJd(item, idx)">
                        {{ chatJdButtonText(item, idx) }}
                      </button>
                      <button
                        v-if="hasRecommendationEvidence(item)"
                        type="button"
                        :aria-expanded="isRecommendationEvidenceOpen(item, idx)"
                        @click.stop="toggleRecommendationEvidence(item, idx)"
                      >
                        {{ isRecommendationEvidenceOpen(item, idx) ? '收起推荐依据' : '推荐依据' }}
                      </button>
                      <button type="button" :disabled="chat.loading" @click.stop="analyzeChatJob(item)">
                        {{ chatJobAnalysisButtonText(item) }}
                      </button>
                      <button
                        type="button"
                        :class="{ active: job.isFavorite(item) }"
                        @click.stop="toggleChatFavorite(item, idx)"
                      >
                        {{ job.isFavorite(item) ? '已收藏' : '收藏' }}
                      </button>
                    </div>
                    <p v-if="chatJdError(item, idx)" class="chat-job-jd-error">{{ chatJdError(item, idx) }}</p>
                    <div v-if="isChatJdOpen(item, idx) && chatJobFullJd(item)" class="chat-job-jd-full">
                      {{ chatJobFullJd(item) }}
                    </div>
                    <div v-if="isRecommendationEvidenceOpen(item, idx)" class="chat-job-recommendation-details">
                      <div class="chat-job-evidence-summary">
                        <span v-if="hasMatchScore(item)"><strong>匹配分</strong>{{ item.matchScore }} 分</span>
                        <span v-if="matchConfidence(item)"><strong>置信度</strong>{{ matchConfidence(item) }}</span>
                        <span v-if="item.matchRecommendation"
                          ><strong>投递建议</strong>{{ item.matchRecommendation }}</span
                        >
                        <span v-if="recommendationEvidenceLevel(item)"
                          ><strong>证据范围</strong>{{ recommendationEvidenceLevel(item) }}</span
                        >
                      </div>
                      <p v-if="recommendationReasons(item)" class="chat-job-recommendation">
                        <strong>推荐依据</strong>{{ recommendationReasons(item) }}
                      </p>
                      <p v-if="recommendationWarnings(item)" class="chat-job-warning">
                        <strong>注意</strong>{{ recommendationWarnings(item) }}
                      </p>
                    </div>
                  </article>
                  <div class="chat-job-more">
                    <button type="button" :disabled="chat.loading" @click="requestMoreJobs">换一批 / 更多岗位</button>
                    <small>点击后再加载下一批岗位。</small>
                  </div>
                </div>
                <div v-if="checkpointResumeInfo(msg)" class="checkpoint-resume-action">
                  <button type="button" :disabled="chat.loading" @click="resumeCheckpoint(msg)">
                    {{ chat.loading ? '正在恢复' : '从断点继续' }}
                  </button>
                  <small>从已完成节点继续，不重复执行已经成功的工具步骤。</small>
                </div>
              </template>
              <template v-else>
                <div v-if="msg.attachments?.length" class="message-attachments">
                  <span
                    v-for="attachment in msg.attachments"
                    :key="attachment.attachmentId || attachment.fileName"
                    class="message-attachment"
                  >
                    <span class="message-attachment-type" aria-hidden="true">{{
                      attachmentExtension(attachment)
                    }}</span>
                    <span class="message-attachment-name" :title="attachment.fileName">
                      {{ attachment.fileName }}
                    </span>
                  </span>
                </div>
                <div class="user-message-text">{{ msg.content || '' }}</div>
              </template>
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

    <form
      ref="composerEl"
      class="composer"
      :class="{ busy: chat.loading }"
      @submit.prevent="submit"
      @keydown.esc="closeAttachmentMenu"
    >
      <div class="composer-resume-bar">
        <button type="button" class="composer-resume-chip" @click="$emit('select-resume')">
          <span>当前简历</span>
          <strong>{{ resumeLabel }}</strong>
        </button>
        <button type="button" class="composer-resume-action" @click="$emit('select-resume')">选择简历</button>
      </div>
      <div v-if="!chat.loading && chat.pendingAttachments.length" class="composer-attachments" aria-live="polite">
        <div
          v-for="attachment in chat.pendingAttachments"
          :key="attachment.localId"
          :class="['composer-attachment', attachment.status]"
        >
          <span class="composer-attachment-type" aria-hidden="true">{{ attachmentExtension(attachment) }}</span>
          <div class="composer-attachment-copy">
            <strong :title="attachment.fileName">{{ attachment.fileName }}</strong>
            <small v-if="attachment.status === 'uploading'">正在上传并读取内容</small>
            <small v-else-if="attachment.status === 'error'">{{ attachment.error }}</small>
            <small v-else>已就绪 · {{ formatAttachmentSize(attachment.sizeBytes) }}</small>
          </div>
          <button
            type="button"
            aria-label="移除附件"
            :disabled="attachment.status === 'uploading'"
            @click="chat.removePendingAttachment(attachment)"
          >
            ×
          </button>
        </div>
      </div>
      <div v-if="chat.attachmentError" class="composer-file-notice" role="alert">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7.5v5.5M12 16.5h.01" />
        </svg>
        <span>{{ chat.attachmentError }}</span>
        <button type="button" aria-label="关闭文件错误提示" @click="chat.attachmentError = ''">×</button>
      </div>
      <div v-if="chat.serviceError" class="composer-service-error" role="alert">
        {{ chat.serviceError }}
      </div>
      <textarea
        ref="composerInput"
        v-model="input"
        :disabled="chat.loading"
        :placeholder="composerPlaceholder"
        @compositionstart="startComposing"
        @compositionend="finishComposing"
        @keydown.enter.exact="handleComposerEnter"
      />
      <p v-if="profileContextSummary" class="composer-profile-context">本次已使用：{{ profileContextSummary }}</p>
      <div class="composer-footer">
        <div class="composer-left-actions">
          <div class="attachment-control" @click.stop>
            <button
              type="button"
              class="composer-add-button"
              :class="{ active: attachmentMenuOpen }"
              :disabled="chat.loading || attachmentsFull"
              aria-label="添加文件"
              :aria-expanded="attachmentMenuOpen"
              @click="toggleAttachmentMenu"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
            <div v-if="attachmentMenuOpen" class="attachment-menu" role="menu">
              <button type="button" role="menuitem" @click="openAttachmentPicker">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    d="M8.5 12.5 14 7a3 3 0 0 1 4.24 4.24l-7.5 7.5a5 5 0 0 1-7.07-7.07l8-8a2.8 2.8 0 0 1 3.96 3.96l-8 8a.8.8 0 1 1-1.13-1.13l7.5-7.5"
                  />
                </svg>
                <span>
                  <strong>添加文件</strong>
                  <small>从电脑上传</small>
                </span>
              </button>
              <p>支持 PDF、DOC、DOCX、TXT、MD · 单个文件最大 128MB</p>
            </div>
            <input
              ref="attachmentInput"
              class="attachment-file-input"
              type="file"
              multiple
              accept=".pdf,.doc,.docx,.txt,.md,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown"
              :disabled="chat.loading || attachmentsFull"
              @change="pickAttachments"
            />
          </div>
          <span v-if="footerText" class="composer-status">{{ footerText }}</span>
        </div>
        <div class="composer-actions">
          <button v-if="canStop" type="button" class="stop-btn" @click="chat.stop">停止</button>
          <button
            class="composer-send-button"
            :disabled="chat.loading || attachmentsBusy || attachmentsFailed || !input.trim()"
            aria-label="发送"
            title="发送"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m7 11 5-5 5 5M12 6v12" />
            </svg>
          </button>
        </div>
      </div>
    </form>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import SandboxExecutionConsole from './SandboxExecutionConsole.vue'
import { useChatStore } from '../stores/chat'
import { useJobStore } from '../stores/job'
import { firstJobDescriptionText, normalizeJobDescriptionText } from '../utils/jobText'
import { validateFile } from '../utils/formValidation'
import {
  activeToolSummary,
  normalizeAssistantMarkdown,
  selectReasoningHighlights,
  selectSandboxExecutionDetail,
  selectToolEventHighlights,
} from '../utils/chatHelpers'
import { bossDetailUrl } from '../utils/zhipinUrl'
defineEmits(['select-resume'])
const props = defineProps({ resumeId: { type: String, default: '' }, resumeName: { type: String, default: '' } })
const chat = useChatStore()
const job = useJobStore()
const input = ref('')
const isComposing = ref(false)
const composerInput = ref(null)
const chatScroll = ref(null)
const composerEl = ref(null)
const attachmentInput = ref(null)
const attachmentMenuOpen = ref(false)
const composerSpace = ref(190)
const nowTick = ref(Date.now())
let elapsedTimer = null
let composerResizeObserver = null
// 过程面板/推理步骤的展开状态按消息 id 记忆，完成后不强制收起，避免“跑完过程就没了”。
const panelOpenState = ref({})
// 推荐列表允许只携带摘要；完整职位描述由用户展开或发起分析时按需加载。
const MIN_CHAT_JOB_DESCRIPTION_CHARS = 30
const jdErrorMap = ref({})
const jdExpandedKeys = ref(new Set())
// 推荐依据默认收起，按岗位记忆展开状态，避免大段证据挤占候选岗位浏览空间。
const recommendationExpandedKeys = ref(new Set())
// 本次会话中最近一条流式生成的助手消息 id，完成后其过程面板仍默认展开。
const lastStreamedAssistantId = ref('')
const defaultWorkbenchCopy = {
  placeholder: '例如：筛选上海 Agent 与大模型应用开发 40-50K 岗位',
  quick_prompts: [
    '筛选上海 40-50K 大模型应用开发岗位',
    '分析当前简历与目标岗位的匹配度',
    '生成大模型应用开发面试准备清单',
  ],
}
const profileContextSummary = computed(() => {
  const ctx = chat.lastPersonalContextEvent || {}
  const sources = Array.isArray(ctx.sources) ? ctx.sources : []
  if (sources.length) return sources.join(' / ')
  return String(ctx.summary || '').slice(0, 80)
})
const prompts = computed(() => defaultWorkbenchCopy.quick_prompts)
const attachmentsBusy = computed(() => chat.pendingAttachments.some((item) => item.status === 'uploading'))
const attachmentsFailed = computed(() => chat.pendingAttachments.some((item) => item.status === 'error'))
const attachmentsFull = computed(() => chat.pendingAttachments.length >= 5)

const hasUserMessage = computed(() => chat.messages.some((msg) => msg.role === 'user'))
const showQuickPrompts = computed(() => !hasUserMessage.value && !chat.loading)
const visibleMessages = computed(() =>
  chat.messages.filter((msg) => {
    if (msg.role !== 'assistant') return true
    if (msg.pending) return false
    const hasContent = !!String(msg.content || '').trim()
    const hasCards = Array.isArray(msg.jobCards) && msg.jobCards.length > 0
    const hasTools = Array.isArray(msg.toolEvents) && msg.toolEvents.length > 0
    const hasReasoning = !!String(msg.reasoning || '').trim()
    return hasContent || hasCards || hasTools || hasReasoning
  }),
)
const hiddenToolEventIds = new Set(['sse_connect', 'request_init'])
const visibleToolEvents = computed(() => chat.toolEvents.filter((item) => item && !hiddenToolEventIds.has(item.id)))
const latestToolEvent = computed(() => visibleToolEvents.value[visibleToolEvents.value.length - 1] || null)
const activeRunningToolEvent = computed(
  () => [...visibleToolEvents.value].reverse().find((item) => item.status === 'running') || null,
)
const currentToolEvent = computed(() => activeRunningToolEvent.value || latestToolEvent.value || null)
const terminalToolStatuses = new Set(['success', 'error', 'down', 'cancelled'])
// 工具事件签名独立成 computed：答案逐 token 流式期间工具事件不变，签名命中缓存，
// 避免滚动跟随的 watch 在每个 token 上对全部工具事件重复做 map+join 字符串拼接。
const toolEventsSignature = computed(() =>
  visibleToolEvents.value.map((item) => `${item.id}:${item.status}:${item.detail || ''}`).join('|'),
)
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
const showProcessingHint = computed(
  () => chat.loading && !lastAssistantContent.value && visibleToolEvents.value.length === 0,
)
const loadingTitle = computed(() => currentToolEvent.value?.name || '正在处理')
const loadingSummary = computed(() => activeToolSummary(currentToolEvent.value))
const composerPlaceholder = computed(() =>
  chat.loading ? '正在处理当前请求，请等待结果返回后再继续输入' : defaultWorkbenchCopy.placeholder,
)
const resumeLabel = computed(() => props.resumeName || (props.resumeId ? '已关联简历' : '未选择简历'))
const canStop = computed(() => chat.loading || !!chat.abortController)
const attachmentMaxBytes = 128 * 1024 * 1024

const footerText = computed(() => {
  if (chat.loading) return loadingSummary.value
  if (attachmentsBusy.value) return '正在上传并解析附件'
  if (attachmentsFailed.value) return '请处理失败文件'
  if (chat.pendingAttachments.length) return `${chat.pendingAttachments.length} 个文件已就绪`
  return props.resumeId ? '' : '未关联简历，可先上传后做匹配'
})

async function pickAttachments(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  attachmentMenuOpen.value = false
  chat.attachmentError = ''
  const valid = []
  for (const file of files) {
    try {
      validateFile(file, '文件', {
        extensions: ['pdf', 'doc', 'docx', 'txt', 'md'],
        mimeTypes: [
          'application/pdf',
          'application/msword',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          'text/plain',
          'text/markdown',
          'text/x-markdown',
          'application/octet-stream',
        ],
        maxBytes: attachmentMaxBytes,
      })
      valid.push(file)
    } catch (error) {
      chat.attachmentError = formatAttachmentValidationError(file, error)
    }
  }
  if (valid.length) await chat.addAttachments(valid)
}

function formatAttachmentValidationError(file, error) {
  const fileName = String(file?.name || '该文件').trim()
  let reason = String(error?.message || '文件校验失败').trim()
  reason = reason.replace(/^文件不能超过/, '文件大小不能超过')
  return `未添加“${fileName}”：${reason}`
}

function toggleAttachmentMenu() {
  if (chat.loading || attachmentsFull.value) return
  attachmentMenuOpen.value = !attachmentMenuOpen.value
}

function closeAttachmentMenu() {
  attachmentMenuOpen.value = false
}

function openAttachmentPicker() {
  closeAttachmentMenu()
  attachmentInput.value?.click()
}

function attachmentExtension(attachment) {
  const suffix = String(attachment?.suffix || attachment?.fileName?.split('.').pop() || 'FILE')
    .trim()
    .toUpperCase()
  return suffix.slice(0, 4) || 'FILE'
}

function formatAttachmentSize(value) {
  const bytes = Number(value || 0)
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
function isMessageFinal(msg) {
  if (!msg || msg.role !== 'assistant') return true
  const lastAssistant = [...chat.messages].reverse().find((item) => item.role === 'assistant')
  return !chat.loading || !lastAssistant || lastAssistant.id !== msg.id
}

function jobTitle(item) {
  return item.jobName || item.job_name || item.title || item.name || '未知岗位'
}
function company(item) {
  return item.brandName || item.companyName || item.company || '未知公司'
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
function chatJobSummary(item) {
  const parts = [
    item.jobDegree || item.education,
    item.companyIndustry || item.brandIndustry || item.industry,
    item.companyScale || item.brandScaleName,
    item.companyStage || item.brandStageName,
  ].filter(Boolean)
  return parts.join(' · ')
}
function recommendationReasons(item) {
  const rows = Array.isArray(item?.recommendationReasons) ? item.recommendationReasons : []
  return rows
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .slice(0, 2)
    .join('；')
}
function recommendationWarnings(item) {
  const rows = Array.isArray(item?.recommendationWarnings) ? item.recommendationWarnings : []
  return rows
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .slice(0, 1)
    .join('；')
}
function hasRecommendationEvidence(item) {
  return !!(
    hasMatchScore(item) ||
    matchConfidence(item) ||
    item?.matchRecommendation ||
    recommendationEvidenceLevel(item) ||
    recommendationReasons(item) ||
    recommendationWarnings(item)
  )
}
function hasMatchScore(item) {
  return item?.matchScore !== undefined && item?.matchScore !== null && item?.matchScore !== ''
}
function recommendationEvidenceLevel(item) {
  return (
    {
      full_jd: '完整职位描述',
      list_metadata: '岗位列表信息',
    }[String(item?.recommendationEvidenceLevel || '').toLowerCase()] || ''
  )
}
function isRecommendationEvidenceOpen(item, idx) {
  return recommendationExpandedKeys.value.has(jobId(item, idx))
}
function toggleRecommendationEvidence(item, idx) {
  const key = jobId(item, idx)
  if (recommendationExpandedKeys.value.has(key)) recommendationExpandedKeys.value.delete(key)
  else recommendationExpandedKeys.value.add(key)
  recommendationExpandedKeys.value = new Set(recommendationExpandedKeys.value)
}
function matchConfidence(item) {
  return { high: '高', medium: '中', low: '低' }[String(item?.matchConfidence || '').toLowerCase()] || ''
}
function jobId(item, idx) {
  return String(item.securityId || item.id || item.jobId || item.encryptJobId || `job_${idx}`)
}
function resolvedChatJob(item) {
  const detail = job.detailSnapshot(item)
  return detail ? { ...item, ...detail } : item
}
function chatJobFullJd(item) {
  return normalizeJobDescriptionText(firstJobDescriptionText(resolvedChatJob(item)))
}
function hasSufficientChatJobDescription(item) {
  return chatJobFullJd(item).length >= MIN_CHAT_JOB_DESCRIPTION_CHARS
}
function chatJdError(item, idx) {
  return jdErrorMap.value[jobId(item, idx)] || ''
}
function isChatJdOpen(item, idx) {
  return jdExpandedKeys.value.has(jobId(item, idx))
}
function chatJdButtonText(item, idx) {
  if (job.isLoadingDetail(item)) return '加载中'
  const hasSufficientDescription = hasSufficientChatJobDescription(item)
  if (hasSufficientDescription && isChatJdOpen(item, idx)) return '收起职位描述'
  return hasSufficientDescription ? '查看职位描述' : '加载职位描述'
}
function chatJobAnalysisButtonText(item) {
  return chat.isAnalyzingSelectedJob(item) ? '分析中' : '分析此岗位'
}
async function toggleChatFavorite(item, idx) {
  const key = jobId(item, idx)
  jdErrorMap.value = { ...jdErrorMap.value, [key]: '' }
  try {
    await job.toggleFavorite(item)
  } catch (error) {
    if (error?.authRequired) {
      chat.authRequired = error.authData || { message: error.message }
      return
    }
    jdErrorMap.value = { ...jdErrorMap.value, [key]: error?.message || '收藏岗位失败' }
  }
}
function showMissingChatJobDescription(item, idx, message = '') {
  const key = jobId(item, idx)
  jdErrorMap.value = {
    ...jdErrorMap.value,
    [key]: message || '未获取到完整职位描述，请稍后重试或打开 Boss 原岗位查看。',
  }
}
async function ensureChatJobDescription(item, idx) {
  const key = jobId(item, idx)
  if (hasSufficientChatJobDescription(item)) return true
  jdErrorMap.value = { ...jdErrorMap.value, [key]: '' }
  try {
    const detail = await job.loadJobDetail(item, originalUrl(item))
    if (detail && typeof detail === 'object') Object.assign(item, detail)
  } catch (error) {
    if (error?.authRequired) {
      chat.authRequired = error.authData || { message: error.message }
      return false
    }
    showMissingChatJobDescription(item, idx, error?.message)
    return false
  }
  if (hasSufficientChatJobDescription(item)) {
    jdErrorMap.value = { ...jdErrorMap.value, [key]: '' }
    return true
  }
  showMissingChatJobDescription(item, idx, job.detailError(item))
  return false
}
async function toggleChatJd(item, idx) {
  const key = jobId(item, idx)
  if (hasSufficientChatJobDescription(item) && isChatJdOpen(item, idx)) {
    jdExpandedKeys.value.delete(key)
    jdExpandedKeys.value = new Set(jdExpandedKeys.value)
    return
  }
  if (!(await ensureChatJobDescription(item, idx))) return
  jdErrorMap.value = { ...jdErrorMap.value, [key]: '' }
  jdExpandedKeys.value.add(key)
  jdExpandedKeys.value = new Set(jdExpandedKeys.value)
}

function compactSelectedJobForAnalysis(item) {
  if (!item || typeof item !== 'object') return {}
  const source = resolvedChatJob(item)
  const result = {}
  const putText = (key, aliases, maxLength = 512) => {
    const value = aliases
      .map((alias) => source[alias])
      .find((candidate) => candidate !== undefined && candidate !== null)
    const normalized = String(value || '').trim()
    if (normalized) result[key] = normalized.slice(0, maxLength)
  }
  putText('securityId', ['securityId', 'security_id', 'id', 'jobId', 'encryptJobId', 'encrypt_job_id'], 256)
  putText('jobName', ['jobName', 'job_name', 'title', 'name'])
  putText('company', ['brandName', 'companyName', 'company'])
  putText('salary', ['salaryDesc', 'salary_desc', 'salary', 'salaryText', 'jobSalary'], 256)
  putText('city', ['cityName', 'city', 'location', 'areaDistrict'], 256)
  putText('experience', ['jobExperience', 'experience', 'experienceName'], 256)
  putText('degree', ['jobDegree', 'education', 'degree', 'degreeName'], 256)
  putText('industry', ['brandIndustry', 'companyIndustry', 'industry', 'industryName'], 256)
  putText('originalUrl', ['originalUrl', 'jobUrl', 'url', 'href', 'link', 'detailUrl', 'jobDetailUrl'], 2048)
  const description = normalizeJobDescriptionText(firstJobDescriptionText(source)).slice(0, 2400)
  if (description) result.jobDescription = description
  const tags = jobTags(source)
    .map((tag) => tag.slice(0, 120))
    .slice(0, 12)
  if (tags.length) result.skills = tags
  return result
}

function jobTags(item) {
  return [
    ...(item.skills || []),
    ...(item.skillList || []),
    ...(item.jobLabels || []),
    item.brandIndustry,
    item.industry,
  ]
    .map((x) => String(x || '').trim())
    .filter((x) => x && !/^\d{4,}$/.test(x))
    .slice(0, 6)
}

function messageToolEvents(msg) {
  const events = Array.isArray(msg?.toolEvents) ? msg.toolEvents : []
  return events.filter((item) => item && !hiddenToolEventIds.has(item.id))
}

function activeRunningToolEventFor(msg) {
  return [...messageToolEvents(msg)].reverse().find((item) => item.status === 'running') || null
}

function completedToolCountFor(msg) {
  return messageToolEvents(msg).filter((item) => terminalToolStatuses.has(item.status)).length
}

function panelCurrentToolEvent(msg) {
  const events = messageToolEvents(msg)
  return activeRunningToolEventFor(msg) || events[events.length - 1] || null
}

function panelLoadingTitle(msg) {
  return panelCurrentToolEvent(msg)?.name || '正在处理'
}

function panelLoadingSummary(msg) {
  return activeToolSummary(panelCurrentToolEvent(msg))
}

function activeRunningTimingTextFor(msg) {
  const active = activeRunningToolEventFor(msg)
  const startedAt = Number(active?.startedAt || 0)
  const elapsed = startedAt ? Math.max(0, Math.floor((nowTick.value - startedAt) / 1000)) : 0
  return [toolEventClockText(active), elapsed ? `已运行 ${elapsed} 秒` : '刚刚开始'].filter(Boolean).join(' · ')
}

function processStepEvents(msg) {
  const events = messageToolEvents(msg)
  const active = activeRunningToolEventFor(msg)
  const visible =
    isStreamingMsg(msg) && active ? events.filter((item) => item.id !== active.id || item.status !== 'running') : events
  // 过程始终按时间倒序展示：当前运行步骤在顶部，完成后也保持最新步骤在最上方。
  return [...visible].reverse()
}

function checkpointResumeInfo(msg) {
  const events = messageToolEvents(msg)
  const failed = [...events]
    .reverse()
    .find(
      (item) =>
        item.id === 'runtime_managed' &&
        ['error', 'cancelled'].includes(item.status) &&
        item.payload?.resumable === true &&
        String(item.payload?.runId || '').trim(),
    )
  if (!failed) return null
  const runId = String(failed.payload.runId).trim()
  const alreadyResumed = chat.messages.some((message) =>
    messageToolEvents(message).some(
      (item) => String(item.payload?.resumedFromRunId || item.payload?.resumed_from_run_id || '').trim() === runId,
    ),
  )
  return alreadyResumed ? null : { runId }
}

async function resumeCheckpoint(msg) {
  if (chat.loading) return false
  const recovery = checkpointResumeInfo(msg)
  if (!recovery) return false
  const messageIndex = chat.messages.findIndex((item) => item.id === msg.id)
  let userMessage = null
  for (let index = messageIndex - 1; index >= 0; index -= 1) {
    if (chat.messages[index]?.role === 'user') {
      userMessage = chat.messages[index]
      break
    }
  }
  if (!userMessage) return false
  return chat.send(userMessage.content, props.resumeId, {
    replay: true,
    resumeRunId: recovery.runId,
    turnId: userMessage.turnId || userMessage.id,
    attachments: Array.isArray(userMessage.attachments) ? userMessage.attachments : [],
  })
}

function latestCheckpointResumeMessage() {
  const latestAssistant = [...chat.messages].reverse().find((item) => item?.role === 'assistant')
  return latestAssistant && checkpointResumeInfo(latestAssistant) ? latestAssistant : null
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
  const events = messageToolEvents(msg)
  const done = events.filter((item) => terminalToolStatuses.has(item.status)).length
  if (isStreamingMsg(msg)) {
    const current = panelCurrentToolEvent(msg)
    const startedAt = Number(current?.startedAt || 0)
    const elapsed = startedAt ? Math.max(0, Math.floor((nowTick.value - startedAt) / 1000)) : 0
    return `进行中 · ${done}/${events.length || 1} 步 · ${elapsed} 秒`
  }
  const errors = events.filter((item) => ['error', 'down'].includes(item.status)).length
  if (errors) return `已结束 ${done}/${events.length || done} 步 · ${errors} 项异常`
  return `已完成 ${done}/${events.length || done} 步`
}

function toolStateText(status) {
  return (
    {
      running: '进行中',
      success: '已完成',
      error: '异常',
      down: '不可用',
      cancelled: '已停止',
    }[status] || '待处理'
  )
}

function toolEventClockText(item) {
  const raw = item?.time || item?.updatedAt || item?.startedAt
  if (!raw) return ''
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

function toolEventDurationText(item) {
  const startedAt = Number(item?.startedAt || 0)
  const updatedAt = Number(item?.updatedAt || 0)
  if (!startedAt || !updatedAt || updatedAt <= startedAt) return ''
  const durationMs = updatedAt - startedAt
  if (durationMs < 1000) return `${durationMs} 毫秒`
  return `${(durationMs / 1000).toFixed(durationMs < 10000 ? 1 : 0)} 秒`
}

function toolEventTimingText(item) {
  return [toolEventClockText(item), toolEventDurationText(item)].filter(Boolean).join(' · ')
}

function toolEventHighlights(item) {
  return selectToolEventHighlights(item)
}

function sandboxExecutionDetail(item) {
  return selectSandboxExecutionDetail(item)
}

function toolDetailCount(item) {
  return toolEventHighlights(item).length + (sandboxExecutionDetail(item) ? 3 : 0)
}

function executionCopyKey(msg, item) {
  return `${String(msg?.id || 'message')}:${String(item?.id || 'sandbox-code')}`
}

function toolDetailStateKey(msg, item) {
  return `tool-detail:${msg?.id || 'message'}:${item?.id || 'step'}`
}

function isToolDetailOpen(msg, item) {
  return panelOpenState.value[toolDetailStateKey(msg, item)] === true
}

function toggleToolDetail(msg, item) {
  const key = toolDetailStateKey(msg, item)
  panelOpenState.value[key] = !isToolDetailOpen(msg, item)
}

function reasoningHighlights(reasoning) {
  return selectReasoningHighlights(reasoning)
}

function requestMoreJobs() {
  if (chat.loading) return
  // 换一批仍由后端复用上一轮检索条件，但前端按普通消息轮次展示本次请求。
  chat.send('换一批', props.resumeId, { flipJobs: true })
}

async function analyzeChatJob(item) {
  if (!item || chat.loading) return
  const prompt = `分析此岗位与当前简历的匹配度：${jobTitle(item)} / ${company(item)}。请给出评分、结论、匹配优势、主要差距和下一步建议。`
  await chat.send(prompt, props.resumeId, { selectedJob: compactSelectedJobForAnalysis(item) })
}

onMounted(() => {
  elapsedTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
  updateComposerSpace()
  if (window.ResizeObserver && composerEl.value) {
    composerResizeObserver = new ResizeObserver(updateComposerSpace)
    composerResizeObserver.observe(composerEl.value)
  }
  document.addEventListener('click', closeAttachmentMenu)
})

onBeforeUnmount(() => {
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  if (composerResizeObserver) composerResizeObserver.disconnect()
  document.removeEventListener('click', closeAttachmentMenu)
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
    toolEventsSignature.value,
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
  if (!text || chat.loading || attachmentsBusy.value || attachmentsFailed.value) return
  if (text === '继续' && chat.pendingAttachments.length === 0) {
    const recoverableMessage = latestCheckpointResumeMessage()
    if (recoverableMessage) {
      input.value = ''
      const resumed = await resumeCheckpoint(recoverableMessage)
      if (!resumed && !chat.loading) input.value = text
      return
    }
  }
  input.value = ''
  const sent = await chat.send(text, props.resumeId)
  if (!sent && !chat.loading) input.value = text
}

function startComposing() {
  isComposing.value = true
}

function finishComposing() {
  isComposing.value = false
}

function handleComposerEnter(event) {
  // Enter 在输入法组合态中用于确认候选词，不能被当作发送操作。
  // keyCode 229 兼容少数不会可靠设置 isComposing 的浏览器和输入法。
  if (isComposing.value || event?.isComposing || event?.keyCode === 229) return
  event?.preventDefault()
  submit()
}

function applyPrompt(text) {
  input.value = text
  nextTick(() => composerInput.value?.focus())
}

defineExpose({
  submitPrompt: async (text) => {
    if (!text || chat.loading) return false
    return chat.send(text, props.resumeId)
  },
})
</script>
