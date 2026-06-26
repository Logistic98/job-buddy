import { defineStore } from 'pinia'
import { deleteSession, listSessionMessages, listSessions, streamChat } from '../api/chat'
import { getBossLoginStatus } from '../api/boss'
import {
  filterVisibleToolEvents,
  formatSendError,
  isAbortError,
  isBossAuthenticated,
  normalizeMessageText,
  normalizeToolEvent,
  requestKey,
} from '../utils/chatHelpers'
import {
  buildSnapshotFromMessages,
  buildSnapshotFromRows,
  lastJobCards,
  lastResumeMatch,
  lastToolEvents,
  normalizeSessionRows,
} from '../utils/chatSnapshots'

const duplicateSubmitWindowMs = 1800

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: '',
    sessions: [],
    messages: [],
    loading: false,
    intent: null,
    trace: [],
    authRequired: null,
    lastJobCardsEvent: null,
    lastResumeMatchEvent: null,
    lastMemoryContextEvent: [],
    lastPersonalContextEvent: null,
    toolEvents: [],
    serviceError: '',
    pendingAuthRequest: null,
    bossAuthStatus: { checkedAt: 0, authenticated: false, raw: null },
    bossAuthCheckPromise: null,
    bossAuthReplayKeys: [],
    abortController: null,
    inFlightRequestKey: '',
    sessionSnapshots: {},
    sessionMessageRequests: {},
    switchingSessionId: '',
    lastSubmitKey: '',
    lastSubmitAt: 0,
  }),
  actions: {
    newSession() {
      this.sessionId = ''
      this.messages = []
      this.intent = null
      this.authRequired = null
      this.lastJobCardsEvent = []
      this.lastResumeMatchEvent = null
      this.lastMemoryContextEvent = []
      this.lastPersonalContextEvent = null
      this.toolEvents = []
      this.serviceError = ''
      this.pendingAuthRequest = null
      this.abortController?.abort()
      this.abortController = null
      this.inFlightRequestKey = ''
    },
    async loadSessions() {
      try {
        this.sessions = await listSessions()
        this.serviceError = ''
        this.prefetchRecentSessionMessages()
      } catch (error) {
        this.serviceError = formatSendError(error)
        throw error
      }
    },
    async openSession(sessionId) {
      if (!sessionId) return false
      if (this.loading && this.sessionId === sessionId) return false
      this.snapshotCurrentSession()
      if (this.loading && this.sessionId !== sessionId) {
        try { this.abortController?.abort() } catch (_) {}
        this.abortController = null
        this.inFlightRequestKey = ''
        this.loading = false
      }
      this.sessionId = sessionId
      this.switchingSessionId = sessionId
      this.authRequired = null
      this.pendingAuthRequest = null
      const snapshot = this.sessionSnapshots[sessionId]
      if (snapshot?.messages?.length) {
        this.applyCachedSessionSnapshot(sessionId)
        this.switchingSessionId = ''
      } else {
        this.messages = []
        this.toolEvents = []
        this.lastJobCardsEvent = []
        this.lastResumeMatchEvent = null
        this.lastPersonalContextEvent = null
      }
      try {
        const rows = await this.loadSessionMessagesCached(sessionId, { force: true })
        if (this.sessionId !== sessionId) return false
        this.applySessionRows(sessionId, rows)
        this.applySessionSnapshot(sessionId)
        this.serviceError = ''
        return true
      } catch (error) {
        if (this.sessionId === sessionId) this.serviceError = formatSendError(error)
        return false
      } finally {
        if (this.switchingSessionId === sessionId) this.switchingSessionId = ''
      }
    },
    async loadSessionMessagesCached(sessionId, options = {}) {
      if (!sessionId) return []
      if (!options.force && this.sessionSnapshots[sessionId]?.messages?.length) return this.sessionSnapshots[sessionId].rows || []
      if (!this.sessionMessageRequests[sessionId]) {
        this.sessionMessageRequests[sessionId] = listSessionMessages(sessionId)
          .then(rows => {
            this.cacheSessionRows(sessionId, Array.isArray(rows) ? rows : [])
            return Array.isArray(rows) ? rows : []
          })
          .finally(() => { delete this.sessionMessageRequests[sessionId] })
      }
      return this.sessionMessageRequests[sessionId]
    },
    prefetchRecentSessionMessages(limit = 8) {
      const rows = Array.isArray(this.sessions) ? this.sessions.slice(0, limit) : []
      for (const item of rows) {
        const sessionId = item?.sessionId
        if (!sessionId || sessionId === this.sessionId || this.sessionSnapshots[sessionId]?.messages?.length) continue
        this.loadSessionMessagesCached(sessionId).catch(() => {})
      }
    },
    cacheSessionRows(sessionId, rows = []) {
      this.sessionSnapshots[sessionId] = buildSnapshotFromRows(sessionId, rows, this.sessionSnapshots[sessionId])
    },
    applyServerRowsInPlace(sessionId, rows = []) {
      this.cacheSessionRows(sessionId, rows)
      const incoming = normalizeSessionRows(sessionId, rows)
      if (!incoming.length || incoming.length !== this.messages.length) return false
      if (!incoming.every((item, idx) => item.role === this.messages[idx]?.role)) return false
      incoming.forEach((item, idx) => {
        const current = this.messages[idx]
        const content = String(item.content || '')
        // 仅在内存内容为空时回填服务端内容。done 后的强制重载会与异步落库竞争，
        // 此时终态 message 事件已把权威答案写入内存，若用尚未落库完成的服务端旧值覆盖非空内存，
        // 会把刚刚流式产出的答案抹掉，因此非空内存以内存为准。
        if (content.trim() && !String(current.content || '').trim()) current.content = content
        // 服务端缺失的字段不回写，保留内存里已有的推理过程/工具事件/岗位卡片，避免落库延迟导致过程消失。
        if (String(item.reasoning || '').trim() && item.reasoning !== current.reasoning) current.reasoning = item.reasoning
        if (item.jobCards?.length) current.jobCards = item.jobCards
        if (item.toolEvents?.length) current.toolEvents = item.toolEvents
      })
      const tools = lastToolEvents(this.messages)
      if (tools.length) this.toolEvents = tools
      const jobs = lastJobCards(this.messages)
      if (jobs.length) this.lastJobCardsEvent = jobs
      return true
    },
    applySessionRows(sessionId, rows = []) {
      this.cacheSessionRows(sessionId, rows)
      this.applyCachedSessionSnapshot(sessionId)
      this.restoreSessionDerivedState(rows)
    },
    applyCachedSessionSnapshot(sessionId) {
      const snapshot = this.sessionSnapshots[sessionId]
      if (!snapshot?.messages?.length) return
      this.messages = JSON.parse(JSON.stringify(snapshot.messages))
      this.toolEvents = filterVisibleToolEvents(snapshot.toolEvents || [])
      this.lastJobCardsEvent = JSON.parse(JSON.stringify(snapshot.lastJobCardsEvent || []))
      this.lastResumeMatchEvent = snapshot.lastResumeMatchEvent ? JSON.parse(JSON.stringify(snapshot.lastResumeMatchEvent)) : null
      this.lastPersonalContextEvent = snapshot.lastPersonalContextEvent ? JSON.parse(JSON.stringify(snapshot.lastPersonalContextEvent)) : null
    },
    restoreSessionDerivedState(rows = []) {
      this.lastJobCardsEvent = lastJobCards(this.messages)
      this.lastResumeMatchEvent = lastResumeMatch(rows)
      this.toolEvents = lastToolEvents(this.messages)
    },
    async syncCurrentMessagesFromServer() {
      if (!this.sessionId) return
      const sessionId = this.sessionId
      // 重载前先留存当前内存里最后一条助手消息的推理过程，作为服务端缺失时的兜底，避免重载覆盖丢失。
      const memoryLastAssistant = [...this.messages].reverse().find(item => item.role === 'assistant')
      const memoryToolEvents = filterVisibleToolEvents(memoryLastAssistant?.toolEvents || [])
      const memoryJobCards = Array.isArray(memoryLastAssistant?.jobCards) ? [...memoryLastAssistant.jobCards] : []
      const memoryReasoning = typeof memoryLastAssistant?.reasoning === 'string' ? memoryLastAssistant.reasoning : ''
      const rows = await this.loadSessionMessagesCached(sessionId, { force: true })
      if (this.sessionId !== sessionId || !Array.isArray(rows) || rows.length === 0) return
      // 优先原位合并：保持现有消息对象与 id 不变，只更新字段，避免整列表替换导致 DOM 全量重建闪烁。
      if (!this.applyServerRowsInPlace(sessionId, rows)) {
        // 服务端行数少于内存（落库尚未完成）时跳过替换，保留内存内容，避免答案/过程短暂回退。
        if (rows.length < this.messages.length) return
        this.applySessionRows(sessionId, rows)
        this.applySessionSnapshot(sessionId)
      }
      // 服务端最后一条助手消息若没有推理过程/岗位卡片，则用内存留存兜底，保证刷新前推理过程仍可见。
      const serverLastAssistant = [...this.messages].reverse().find(item => item.role === 'assistant')
      if (serverLastAssistant) {
        if (!serverLastAssistant.toolEvents?.length && memoryToolEvents.length) {
          serverLastAssistant.toolEvents = memoryToolEvents
          this.toolEvents = memoryToolEvents
        }
        if (!serverLastAssistant.jobCards?.length && memoryJobCards.length) {
          serverLastAssistant.jobCards = memoryJobCards
        }
        // 服务端最后一条助手消息缺推理过程时用内存留存兜底，保证落库未完成时刷新仍可回看。
        if (!String(serverLastAssistant.reasoning || '').trim() && memoryReasoning) {
          serverLastAssistant.reasoning = memoryReasoning
        }
        this.snapshotCurrentSession()
      }
    },
    snapshotCurrentSession() {
      if (!this.sessionId) return
      this.sessionSnapshots[this.sessionId] = buildSnapshotFromMessages(this)
    },
    applySessionSnapshot(sessionId) {
      const snapshot = this.sessionSnapshots[sessionId]
      if (!snapshot) return
      const hasServerJobs = this.messages.some(item => item.jobCards?.length)
      const hasServerTools = this.messages.some(item => item.toolEvents?.length) || this.toolEvents.length
      if (!hasServerJobs && Array.isArray(snapshot.messages) && snapshot.messages.some(item => item.jobCards?.length)) {
        this.messages = snapshot.messages
      }
      if (!hasServerTools && Array.isArray(snapshot.toolEvents) && snapshot.toolEvents.length) {
        this.toolEvents = filterVisibleToolEvents(snapshot.toolEvents)
        const lastAssistant = [...this.messages].reverse().find(item => item.role === 'assistant')
        if (lastAssistant && !lastAssistant.toolEvents?.length) lastAssistant.toolEvents = [...snapshot.toolEvents]
      }
      if ((!this.lastJobCardsEvent || !this.lastJobCardsEvent.length) && snapshot.lastJobCardsEvent?.length) {
        this.lastJobCardsEvent = snapshot.lastJobCardsEvent
      }
      if (!this.lastResumeMatchEvent && snapshot.lastResumeMatchEvent) this.lastResumeMatchEvent = snapshot.lastResumeMatchEvent
      if (!this.lastPersonalContextEvent && snapshot.lastPersonalContextEvent) this.lastPersonalContextEvent = snapshot.lastPersonalContextEvent
    },
    async removeSession(sessionId) {
      await deleteSession(sessionId)
      delete this.sessionSnapshots[sessionId]
      if (this.sessionId === sessionId) this.newSession()
      await this.loadSessions()
    },
    async send(message, resumeId, options = {}) {
      const text = normalizeMessageText(message)
      if (!text) return false
      const selectedJob = options.selectedJob && typeof options.selectedJob === 'object' ? options.selectedJob : null
      const key = requestKey(this.sessionId, resumeId, text, selectedJob)
      const now = Date.now()
      if (this.loading || this.inFlightRequestKey) {
        this.serviceError = '当前请求仍在处理中，请等待返回结果后再发送。'
        return false
      }
      if (!options.replay && this.lastSubmitKey === key && now - this.lastSubmitAt < duplicateSubmitWindowMs) {
        return false
      }
      this.loading = true
      this.inFlightRequestKey = key
      this.lastSubmitKey = key
      this.lastSubmitAt = now
      this.serviceError = ''
      this.authRequired = null
      this.pendingAuthRequest = null
      if (!options.replay) {
        const authReplayKey = `${text || ''}::${resumeId || ''}::${selectedJob ? key : ''}`
        this.bossAuthReplayKeys = this.bossAuthReplayKeys.filter(item => item !== authReplayKey)
        this.messages.push({ id: crypto.randomUUID(), role: 'user', content: text })
      }
      const fallbackFlipAssistantId = options.flipJobs
        ? [...this.messages].reverse().find(item => item.role === 'assistant' && item.jobCards?.length)?.id
        : ''
      const reusableAssistantId = options.assistantId && (options.resumeAfterAuth || options.flipJobs)
        ? options.assistantId
        : (fallbackFlipAssistantId || '')
      const reused = reusableAssistantId ? this.messages.find(item => item.id === reusableAssistantId) : null
      if (options.resumeAfterAuth && reused) {
        // 续跑复用同一条助手消息（同一个过程框）：清掉登录墙文案与上一轮残留工具事件，
        // 让续跑后的搜索过程在同一个框里干净地接着展示，而不是又新开一个过程框。
        reused.content = ''
        reused.reasoning = ''
        reused.toolEvents = []
        reused.pending = false
        this.toolEvents = []
      } else if (options.flipJobs && reused) {
        // 换一批复用当前岗位卡片所在的助手消息：保留旧岗位直到新 job_cards 到达，
        // 只重置本轮工具过程，避免卡片区域闪空或追加新的助手消息。
        reused.toolEvents = []
        reused.pending = false
        this.toolEvents = []
      } else {
        this.toolEvents = []
        if (!options.flipJobs) this.lastJobCardsEvent = []
      }
      this.abortController?.abort()
      this.abortController = new AbortController()
      const streamSignal = this.abortController.signal
      // 流式过程中若切换会话，openSession 会 abort 当前请求。此后任何迟到的 SSE 回调都不应再改动
      // this.messages（此时已是另一个会话的消息列表），否则会把上一个会话的流式内容/工具事件串进新会话。
      const isStreamStale = () => streamSignal.aborted
      const assistantId = reusableAssistantId || crypto.randomUUID()
      // 续跑/换一批复用同一条助手消息时，视为已存在，避免收尾逻辑误判为空消息或漏清 pending。
      let assistantCreated = !!(reusableAssistantId && this.messages.some(item => item.id === assistantId))
      let errorAppended = false
      let doneReceived = false
      const ensureAssistant = () => {
        let msg = this.messages.find(item => item.id === assistantId)
        if (!msg) {
          msg = {
            id: assistantId,
            role: 'assistant',
            content: '',
            reasoning: '',
            pending: false,
            toolEvents: [...this.toolEvents],
            jobCards: Array.isArray(this.lastJobCardsEvent) ? [...this.lastJobCardsEvent] : [],
          }
          this.messages.push(msg)
          assistantCreated = true
        } else {
          if (!msg.toolEvents?.length && this.toolEvents.length) msg.toolEvents = [...this.toolEvents]
          if (!msg.jobCards?.length && Array.isArray(this.lastJobCardsEvent) && this.lastJobCardsEvent.length) msg.jobCards = [...this.lastJobCardsEvent]
        }
        return msg
      }
      const appendAssistant = text => {
        if (!text || isStreamStale()) return
        const msg = ensureAssistant()
        msg.pending = false
        msg.content = msg.content ? `${msg.content}\n${text}` : text
      }
      let streamedDelta = false
      const appendAssistantDelta = delta => {
        if (!delta || isStreamStale()) return
        const msg = ensureAssistant()
        msg.pending = false
        msg.content = (msg.content || '') + delta
        streamedDelta = true
      }
      const appendReasoningDelta = delta => {
        if (!delta || isStreamStale()) return
        const msg = ensureAssistant()
        msg.pending = false
        msg.reasoning = (msg.reasoning || '') + delta
      }
      const finishRequest = () => {
        this.loading = false
        if (this.inFlightRequestKey === key) this.inFlightRequestKey = ''
        const msg = assistantCreated ? this.messages.find(item => item.id === assistantId) : null
        if (msg?.pending) msg.pending = false
      }
      try {
        await streamChat({ message: text, sessionId: this.sessionId, resumeId, resumeAfterAuth: !!options.resumeAfterAuth, flipJobs: !!options.flipJobs, selectedJob }, {
          signal: this.abortController.signal,
          session: data => { this.sessionId = data.sessionId },
          intent: data => { this.intent = data },
          trace: data => { this.trace = data },
          message: data => {
            if (isStreamStale()) return
            const answerText = typeof data === 'string' ? data : (data.content ?? data.text)
            if (streamedDelta) {
              const msg = ensureAssistant()
              msg.pending = false
              // 终态全文与逐字累积一致时不重新赋值，避免 Markdown 全量重渲染闪烁。
              if (answerText && answerText !== msg.content) msg.content = answerText
              streamedDelta = false
            } else {
              appendAssistant(answerText)
            }
            // 终态 message 携带的推理过程优先绑定到当前助手消息，作为权威推理记录，
            // 即使随后从服务端重载，也已有完整推理过程，避免被未落库完成的快照覆盖丢失。
            const serverTools = data && typeof data === 'object' && Array.isArray(data.toolEvents)
              ? filterVisibleToolEvents(data.toolEvents)
              : []
            if (serverTools.length) {
              const msg = ensureAssistant()
              msg.toolEvents = serverTools
              this.toolEvents = serverTools
              this.snapshotCurrentSession()
            }
            // 终态 message 携带完整推理过程时以其为准，避免逐字累积与服务端聚合不一致。
            const serverReasoning = data && typeof data === 'object' ? data.reasoning : ''
            if (serverReasoning) {
              const msg = ensureAssistant()
              msg.reasoning = serverReasoning
              this.snapshotCurrentSession()
            }
          },
          auth_required: data => {
            this.handleBossAuthRequired(data, { message: text, resumeId, selectedJob }, assistantId)
          },
          error: data => {
            if (errorAppended) return
            const errorText = data?.message || data || '请求失败，请稍后重试。'
            errorAppended = true
            appendAssistant(`请求失败：${errorText}`)
          },
          done: data => {
            doneReceived = true
            finishRequest()
            // 失败态（例如换一批触发 auth_required）不立刻用服务端旧快照覆盖内存，
            // 避免把当前卡片上的错误过程或已保留岗位闪回成上一轮状态。
            if (data?.ok !== false) this.syncCurrentMessagesFromServer().catch(() => {})
            this.loadSessions().catch(() => {})
          },
          onEvent: (event, data) => {
            if (isStreamStale()) return
            if (event === 'message_delta') {
              const delta = typeof data === 'string' ? data : (data?.delta ?? data?.text)
              appendAssistantDelta(delta)
            }
            if (event === 'reasoning_delta') {
              const delta = typeof data === 'string' ? data : (data?.delta ?? data?.text)
              appendReasoningDelta(delta)
            }
            if (event === 'job_cards') {
              const rows = Array.isArray(data) ? data : []
              // 仅在有结果时更新岗位列表。空的 job_cards 多为本轮无新增或尾随的清理事件，
              // 若直接覆盖会把已展示的岗位卡片清空，因此空数组按"无更新"处理，保留既有列表。
              if (rows.length) {
                this.$patch({ lastJobCardsEvent: rows })
                this.markBossLoggedIn({ status: 'logged_in', source: 'job_cards' })
                this.authRequired = null
                this.pendingAuthRequest = null
                const msg = ensureAssistant()
                msg.jobCards = rows
                if (this.toolEvents.length) msg.toolEvents = [...this.toolEvents]
                this.snapshotCurrentSession()
              }
            }
            if (event === 'resume_match') this.$patch({ lastResumeMatchEvent: data })
            if (event === 'memory_context') this.$patch({ lastMemoryContextEvent: Array.isArray(data) ? data : [] })
            if (event === 'personal_context') this.$patch({ lastPersonalContextEvent: data && typeof data === 'object' ? data : null })
            if (event === 'tool_status') this.upsertToolEvent(data, assistantId)
          },
        })
        this.serviceError = ''
      } catch (error) {
        if (!isAbortError(error)) {
          try { this.abortController?.abort() } catch (_) {}
          const messageText = formatSendError(error)
          this.serviceError = messageText
          if (!errorAppended) {
            errorAppended = true
            appendAssistant(`请求失败：${messageText}`)
          }
        }
      } finally {
        finishRequest()
        // 流正常返回但既没有 done 也没有 error 事件，说明连接被中途断开（服务端崩溃/网络掐断）。
        // 此时不能静默收尾让用户误以为回答完整：有部分内容则补一句中断提示，无产出则明确报错。
        if (!doneReceived && !errorAppended && !streamSignal.aborted) {
          const partial = assistantCreated ? this.messages.find(item => item.id === assistantId) : null
          if (partial && String(partial.content || '').trim()) {
            partial.content = `${partial.content}\n\n（连接中断，回答可能不完整，请重试。）`
          } else if (!partial || (!partial.jobCards?.length && !partial.toolEvents?.length)) {
            this.serviceError = '连接中断，请稍后重试。'
            errorAppended = true
            appendAssistant('请求失败：连接中断，请稍后重试。')
          }
        }
        this.abortController = null
        const msg = assistantCreated ? this.messages.find(item => item.id === assistantId) : null
        if (msg && !String(msg.content || '').trim() && !msg.jobCards?.length && !msg.toolEvents?.length) {
          this.messages = this.messages.filter(item => item.id !== assistantId)
        }
        return true
      }
    },
    stop() {
      const controller = this.abortController
      if (!this.loading && !controller) return
      try { controller?.abort() } catch (_) {}
      this.abortController = null
      this.loading = false
      this.inFlightRequestKey = ''
      this.toolEvents = this.toolEvents.map(item => item.status === 'running' ? { ...item, status: 'cancelled', detail: '已停止' } : item)
      const lastAssistant = [...this.messages].reverse().find(item => item.role === 'assistant')
      if (lastAssistant) {
        lastAssistant.pending = false
        if (!lastAssistant.content && !lastAssistant.jobCards?.length) lastAssistant.content = '已停止当前请求。'
      }
    },
    markBossLoggedIn(raw = null) {
      this.bossAuthStatus = { checkedAt: Date.now(), authenticated: true, raw }
      this.authRequired = null
    },
    markBossLoggedOut(raw = null) {
      this.bossAuthStatus = { checkedAt: Date.now(), authenticated: false, raw }
    },
    async refreshBossAuthStatus() {
      if (this.bossAuthCheckPromise) return this.bossAuthCheckPromise
      this.bossAuthCheckPromise = getBossLoginStatus(this.sessionId, null)
        .then(status => {
          if (isBossAuthenticated(status)) this.markBossLoggedIn(status)
          else this.markBossLoggedOut(status)
          return this.bossAuthStatus
        })
        .catch(error => {
          this.markBossLoggedOut({ status: 'error', error: error?.message || String(error || '') })
          return this.bossAuthStatus
        })
        .finally(() => { this.bossAuthCheckPromise = null })
      return this.bossAuthCheckPromise
    },
    async handleBossAuthRequired(data, pending, assistantId) {
      if (data && data.authRequired === false) {
        this.authRequired = null
        this.pendingAuthRequest = null
        return
      }
      const hasResults = Array.isArray(this.lastJobCardsEvent) && this.lastJobCardsEvent.length > 0
      const existing = this.messages.find(item => item.id === assistantId)
      // 仅当已交付真实岗位结果时才抑制登录引导：此时本次请求已产出价值，尾随的
      // 二次 4001（如详情补全）不应用登录墙覆盖结果。不能因为助手消息里已有部分
      // 流式文本（理解/前言）就吞掉登录引导，否则用户会卡死且看不到扫码弹窗。
      if (hasResults) {
        this.authRequired = null
        this.pendingAuthRequest = null
        return
      }
      this.pendingAuthRequest = { ...pending, assistantId }
      this.serviceError = '正在确认 Boss 登录状态，请稍候。'
      const status = await this.refreshBossAuthStatus()
      if (status?.authenticated) {
        this.serviceError = ''
        await this.resumeAfterAuth()
        return
      }
      this.markBossLoggedOut(data)
      this.serviceError = ''
      this.authRequired = data
      let msg = existing
      if (!msg) {
        msg = { id: assistantId, role: 'assistant', content: '', pending: false, toolEvents: [...this.toolEvents], jobCards: [] }
        this.messages.push(msg)
      }
      msg.pending = false
      msg.toolEvents = [...this.toolEvents]
      msg.content = data?.message || 'Boss 直聘需要重新扫码登录。请在弹窗中完成登录后，我会从当前进度继续处理刚才的请求。'
      this.snapshotCurrentSession()
    },
    async resumeAfterAuth() {
      this.markBossLoggedIn()
      const pending = this.pendingAuthRequest
      this.authRequired = null
      this.pendingAuthRequest = null
      if (!pending?.message) return
      // 等待触发本次登录引导的原请求收尾；正常情况下 auth_required 后很快收到 done。
      const startedAt = Date.now()
      while ((this.loading || this.inFlightRequestKey) && Date.now() - startedAt < 5000) {
        await new Promise(resolve => window.setTimeout(resolve, 50))
      }
      // 仍未收尾则强制清理陈旧的在途请求，避免 send 因 loading/inFlightRequestKey 被静默拒绝，
      // 导致用户扫码后请求丢失、卡在登录态确认。
      if (this.loading || this.inFlightRequestKey) this.stop()
      await this.send(pending.message, pending.resumeId, { replay: true, resumeAfterAuth: true, selectedJob: pending.selectedJob, assistantId: pending.assistantId })
    },
    upsertToolEvent(data, assistantId) {
      if (!data || !data.id) return
      const now = Date.now()
      const idx = this.toolEvents.findIndex(item => item.id === data.id)
      const previous = idx >= 0 ? this.toolEvents[idx] : null
      if (data.id === 'sse_connect') return
      const normalized = {
        ...normalizeToolEvent(data),
        startedAt: previous?.startedAt || now,
        updatedAt: now,
      }
      if (idx >= 0) this.toolEvents.splice(idx, 1, { ...previous, ...normalized })
      else this.toolEvents.push(normalized)
      // 首个工具事件即创建助手消息占位，过程面板始终挂在助手消息上，流式与完成态共用同一 DOM 不闪烁。
      let msg = this.messages.find(item => item.id === assistantId)
      if (!msg) {
        msg = {
          id: assistantId,
          role: 'assistant',
          content: '',
          reasoning: '',
          pending: false,
          toolEvents: [],
          jobCards: [],
        }
        this.messages.push(msg)
      }
      msg.toolEvents = [...this.toolEvents]
    },
  },
})
