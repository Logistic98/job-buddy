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
const createTurnId = () => `turn_${crypto.randomUUID().replace(/-/g, '')}`

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: '',
    sessions: [],
    messages: [],
    loading: false,
    intent: null,
    trace: [],
    authRequired: null,
    lastJobCardsEvent: [],
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
    activeSessionRequests: {},
    sessionSnapshots: {},
    sessionMessageRequests: {},
    pendingSessionEntries: {},
    switchingSessionId: '',
    lastSubmitKey: '',
    lastSubmitAt: 0,
    lifecycleRevision: 0,
  }),
  actions: {
    disposeForAuthChange() {
      const nextRevision = this.lifecycleRevision + 1
      Object.values(this.activeSessionRequests).forEach((request) => {
        try {
          request?.controller?.abort()
        } catch (_) {}
      })
      this.$reset()
      this.lifecycleRevision = nextRevision
    },
    newSession() {
      this.snapshotCurrentSession()
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
      // 新建会话只切换当前工作区，已有会话继续在后台生成，不再隐式取消请求。
      this.applyCurrentRequestProjection('')
    },
    applyCurrentRequestProjection(sessionId = this.sessionId) {
      const request = sessionId ? this.activeSessionRequests[sessionId] : null
      this.loading = !!request
      this.abortController = request?.controller || null
      this.inFlightRequestKey = request?.key || ''
    },
    clearSessionRequest(sessionId, requestKeyValue) {
      const request = this.activeSessionRequests[sessionId]
      // Pinia/Vue 可能代理 AbortController，不能依赖控制器对象引用相等；请求键才是稳定身份。
      if (request && (!requestKeyValue || request.key === requestKeyValue)) delete this.activeSessionRequests[sessionId]
      if (this.sessionId === sessionId) this.applyCurrentRequestProjection(sessionId)
    },
    /** 把后台流式请求累积的增量合并进目标会话快照：切走期间的增量不丢失，切回立即可见。 */
    mergeRequestAccIntoSnapshot(sessionId, acc) {
      if (!sessionId || !acc || !acc.assistantId) return
      const hasPayload =
        String(acc.content || '').trim() ||
        String(acc.reasoning || '').trim() ||
        acc.toolEvents.length ||
        acc.jobCards.length
      if (!hasPayload) return
      const snapshot = this.sessionSnapshots[sessionId]
      const messages = snapshot?.messages ? JSON.parse(JSON.stringify(snapshot.messages)) : []
      let msg = messages.find((item) => item.id === acc.assistantId)
      if (!msg) {
        msg = {
          id: acc.assistantId,
          role: 'assistant',
          content: '',
          reasoning: '',
          pending: false,
          toolEvents: [],
          jobCards: [],
        }
        messages.push(msg)
      }
      if (String(acc.content || '').trim()) msg.content = acc.content
      if (String(acc.reasoning || '').trim()) msg.reasoning = acc.reasoning
      const visibleTools = filterVisibleToolEvents(acc.toolEvents)
      if (visibleTools.length) msg.toolEvents = JSON.parse(JSON.stringify(visibleTools))
      if (acc.jobCards.length) msg.jobCards = JSON.parse(JSON.stringify(acc.jobCards))
      msg.pending = false
      this.sessionSnapshots[sessionId] = {
        rows: snapshot?.rows || [],
        messages,
        toolEvents: visibleTools.length ? JSON.parse(JSON.stringify(visibleTools)) : snapshot?.toolEvents || [],
        lastJobCardsEvent: acc.jobCards.length
          ? JSON.parse(JSON.stringify(acc.jobCards))
          : snapshot?.lastJobCardsEvent || [],
        lastResumeMatchEvent: snapshot?.lastResumeMatchEvent || null,
        lastPersonalContextEvent: snapshot?.lastPersonalContextEvent || null,
      }
    },
    /** 请求缓冲内按事件 ID 去重累积工具事件，供后台会话切回时重建过程面板。 */
    accumulateToolEvent(acc, data) {
      if (!acc || !data || !data.id || data.id === 'sse_connect') return
      const now = Date.now()
      const idx = acc.toolEvents.findIndex((item) => item.id === data.id)
      const previous = idx >= 0 ? acc.toolEvents[idx] : null
      const normalized = { ...normalizeToolEvent(data), startedAt: previous?.startedAt || now, updatedAt: now }
      if (idx >= 0) acc.toolEvents.splice(idx, 1, { ...previous, ...normalized })
      else acc.toolEvents.push(normalized)
    },
    async loadSessions() {
      const revision = this.lifecycleRevision
      try {
        const serverSessions = await listSessions()
        if (revision !== this.lifecycleRevision) return []
        const serverSessionIds = new Set(serverSessions.map((item) => item?.sessionId).filter(Boolean))
        for (const sessionId of serverSessionIds) delete this.pendingSessionEntries[sessionId]
        const pendingSessions = Object.values(this.pendingSessionEntries)
          .filter((item) => item?.sessionId && !serverSessionIds.has(item.sessionId))
          .sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')))
        // 新会话提交后，服务端列表查询可能早于首次落库返回。待确认项必须继续保留在列表顶部，
        // 直到服务端真正返回该 sessionId，避免历史记录先出现又被并发刷新覆盖。
        this.sessions = [...pendingSessions, ...serverSessions]
        this.serviceError = ''
        this.prefetchRecentSessionMessages()
      } catch (error) {
        if (revision !== this.lifecycleRevision) return []
        this.serviceError = formatSendError(error)
        throw error
      }
    },
    upsertSessionImmediately(sessionId, title = '') {
      const normalizedSessionId = String(sessionId || '').trim()
      if (!normalizedSessionId) return
      const existing = this.sessions.find((item) => item?.sessionId === normalizedSessionId)
      const updatedAt = new Date().toISOString()
      const next = existing
        ? { ...existing, updatedAt }
        : {
            sessionId: normalizedSessionId,
            title: String(title || '').trim() || '新会话',
            updatedAt,
          }
      if (!existing || this.pendingSessionEntries[normalizedSessionId]) {
        this.pendingSessionEntries[normalizedSessionId] = next
      }
      this.sessions = [next, ...this.sessions.filter((item) => item?.sessionId !== normalizedSessionId)]
    },
    async openSession(sessionId) {
      if (!sessionId) return false
      if (this.loading && this.sessionId === sessionId) return false
      this.snapshotCurrentSession()
      // 切换会话不再中止原会话的 SSE。后台请求按 sessionId 独立保存，回调也只允许更新所属会话。
      this.sessionId = sessionId
      this.applyCurrentRequestProjection(sessionId)
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
      // 目标会话仍在流式生成时直接展示本地快照并恢复 loading。此时强制请求服务端历史只会拿到
      // 尚未落库的过期前缀，既没有必要，也可能让正在展示的增量短暂回退；终态 done 会负责服务端校准。
      const activeRequest = this.activeSessionRequests[sessionId]
      if (activeRequest) {
        // 离开期间的流式增量累积在请求缓冲里；切回时先把缓冲合并进快照再展示，
        // 避免后台会话的增量丢失导致内容缺口或拿不到结果。
        if (activeRequest.acc) {
          this.mergeRequestAccIntoSnapshot(sessionId, activeRequest.acc)
          this.applyCachedSessionSnapshot(sessionId)
        }
        this.serviceError = ''
        this.switchingSessionId = ''
        return true
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
      if (!options.force && this.sessionSnapshots[sessionId]?.messages?.length)
        return this.sessionSnapshots[sessionId].rows || []
      if (!options.force && this.sessionMessageRequests[sessionId]) return this.sessionMessageRequests[sessionId]
      // 主动打开会话必须发起独立刷新，不能复用预加载阶段可能早于消息落库的较早请求。
      // 通过请求身份判断，只允许最新请求更新快照和清理请求槽，避免较早请求迟到后覆盖较新的完整结果。
      const revision = this.lifecycleRevision
      let request
      request = listSessionMessages(sessionId)
        .then((rows) => {
          if (revision !== this.lifecycleRevision) return []
          const normalizedRows = Array.isArray(rows) ? rows : []
          if (this.sessionMessageRequests[sessionId] === request) this.cacheSessionRows(sessionId, normalizedRows)
          return normalizedRows
        })
        .finally(() => {
          if (revision === this.lifecycleRevision && this.sessionMessageRequests[sessionId] === request)
            delete this.sessionMessageRequests[sessionId]
        })
      this.sessionMessageRequests[sessionId] = request
      return request
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
        // 此时终态 message 事件已把权威答案写入内存，若用尚未落库完成的服务端较早值覆盖非空内存，
        // 会把刚刚流式产出的答案抹掉，因此非空内存以内存为准。
        if (content.trim() && !String(current.content || '').trim()) current.content = content
        // 服务端缺失的字段不回写，保留内存里已有的推理过程/工具事件/岗位卡片，避免落库延迟导致过程消失。
        if (String(item.reasoning || '').trim() && item.reasoning !== current.reasoning)
          current.reasoning = item.reasoning
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
      this.lastResumeMatchEvent = snapshot.lastResumeMatchEvent
        ? JSON.parse(JSON.stringify(snapshot.lastResumeMatchEvent))
        : null
      this.lastPersonalContextEvent = snapshot.lastPersonalContextEvent
        ? JSON.parse(JSON.stringify(snapshot.lastPersonalContextEvent))
        : null
    },
    restoreSessionDerivedState(rows = []) {
      this.lastJobCardsEvent = lastJobCards(this.messages)
      this.lastResumeMatchEvent = lastResumeMatch(rows)
      this.toolEvents = lastToolEvents(this.messages)
    },
    async syncSessionMessagesFromServer(sessionId) {
      if (!sessionId) return
      if (this.sessionId === sessionId) {
        await this.syncCurrentMessagesFromServer()
        return
      }
      const rows = await this.loadSessionMessagesCached(sessionId, { force: true })
      // loadSessionMessagesCached 已把完整结果写入目标会话快照；若等待期间用户切回，则立即应用。
      if (this.sessionId === sessionId && Array.isArray(rows) && rows.length) {
        this.applySessionRows(sessionId, rows)
        this.applySessionSnapshot(sessionId)
      }
    },
    async syncCurrentMessagesFromServer() {
      if (!this.sessionId) return
      const sessionId = this.sessionId
      // 重载前先留存当前内存里最后一条助手消息的推理过程，作为服务端缺失时的兜底，避免重载覆盖丢失。
      const memoryLastAssistant = [...this.messages].reverse().find((item) => item.role === 'assistant')
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
      const serverLastAssistant = [...this.messages].reverse().find((item) => item.role === 'assistant')
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
      const hasServerJobs = this.messages.some((item) => item.jobCards?.length)
      const hasServerTools = this.messages.some((item) => item.toolEvents?.length) || this.toolEvents.length
      if (
        !hasServerJobs &&
        Array.isArray(snapshot.messages) &&
        snapshot.messages.some((item) => item.jobCards?.length)
      ) {
        this.messages = snapshot.messages
      }
      if (!hasServerTools && Array.isArray(snapshot.toolEvents) && snapshot.toolEvents.length) {
        this.toolEvents = filterVisibleToolEvents(snapshot.toolEvents)
        const lastAssistant = [...this.messages].reverse().find((item) => item.role === 'assistant')
        if (lastAssistant && !lastAssistant.toolEvents?.length) lastAssistant.toolEvents = [...snapshot.toolEvents]
      }
      if ((!this.lastJobCardsEvent || !this.lastJobCardsEvent.length) && snapshot.lastJobCardsEvent?.length) {
        this.lastJobCardsEvent = snapshot.lastJobCardsEvent
      }
      if (!this.lastResumeMatchEvent && snapshot.lastResumeMatchEvent)
        this.lastResumeMatchEvent = snapshot.lastResumeMatchEvent
      if (!this.lastPersonalContextEvent && snapshot.lastPersonalContextEvent)
        this.lastPersonalContextEvent = snapshot.lastPersonalContextEvent
    },
    async removeSession(sessionId) {
      const activeRequest = this.activeSessionRequests[sessionId]
      try {
        activeRequest?.controller?.abort()
      } catch (_) {}
      this.clearSessionRequest(sessionId, activeRequest?.key)
      await deleteSession(sessionId)
      const wasCurrent = this.sessionId === sessionId
      if (wasCurrent) this.sessionId = ''
      delete this.sessionSnapshots[sessionId]
      delete this.pendingSessionEntries[sessionId]
      if (wasCurrent) this.newSession()
      await this.loadSessions()
    },
    async send(message, resumeId, options = {}) {
      const text = normalizeMessageText(message)
      if (!text) return false
      const selectedJob = options.selectedJob && typeof options.selectedJob === 'object' ? options.selectedJob : null
      const requestSessionId = this.sessionId || `sess_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`
      const key = requestKey(requestSessionId, resumeId, text, selectedJob)
      const now = Date.now()
      if (this.activeSessionRequests[requestSessionId]) {
        this.serviceError = '当前会话的请求仍在处理中，请等待返回结果后再发送。'
        return false
      }
      if (!options.replay && this.lastSubmitKey === key && now - this.lastSubmitAt < duplicateSubmitWindowMs) {
        return false
      }
      // turnId 是一次用户提交在乐观消息、登录续跑与服务端持久化之间的稳定身份。
      // replay 必须显式复用原 turnId；普通新提交则在通过重复提交拦截后创建新身份。
      const turnId = String(options.turnId || '').trim() || createTurnId()
      this.loading = true
      this.inFlightRequestKey = key
      this.lastSubmitKey = key
      this.lastSubmitAt = now
      this.serviceError = ''
      this.authRequired = null
      this.pendingAuthRequest = null
      if (!this.sessionId) {
        // 新对话不等待网络或 SSE 首包：发送动作发生时立即分配会话 ID 并写入历史列表，
        // 后端直接复用该 ID，随后由 loadSessions 在首次落库可见后完成服务端确认。
        this.sessionId = requestSessionId
        this.upsertSessionImmediately(requestSessionId, text)
      }
      if (!options.replay) {
        const authReplayKey = `${text || ''}::${resumeId || ''}::${selectedJob ? key : ''}`
        this.bossAuthReplayKeys = this.bossAuthReplayKeys.filter((item) => item !== authReplayKey)
        this.messages.push({ id: turnId, turnId, role: 'user', content: text })
        this.snapshotCurrentSession()
      }
      const fallbackFlipAssistantId = options.flipJobs
        ? [...this.messages].reverse().find((item) => item.role === 'assistant' && item.jobCards?.length)?.id
        : ''
      const reusableAssistantId =
        options.assistantId && (options.resumeAfterAuth || options.flipJobs)
          ? options.assistantId
          : fallbackFlipAssistantId || ''
      const reused = reusableAssistantId ? this.messages.find((item) => item.id === reusableAssistantId) : null
      if (options.resumeAfterAuth && reused) {
        // 续跑复用同一条助手消息（同一个过程框）：清掉登录墙文案与上一轮残留工具事件，
        // 让续跑后的搜索过程在同一个框里干净地接着展示，而不是又新开一个过程框。
        reused.content = ''
        reused.reasoning = ''
        reused.toolEvents = []
        reused.pending = false
        this.toolEvents = []
      } else if (options.flipJobs && reused) {
        // 换一批复用当前岗位卡片所在的助手消息：保留原岗位直到新 job_cards 到达，
        // 只重置本轮工具过程，避免卡片区域闪空或追加新的助手消息。
        reused.toolEvents = []
        reused.pending = false
        this.toolEvents = []
      } else {
        this.toolEvents = []
        if (!options.flipJobs) this.lastJobCardsEvent = []
      }
      const requestRevision = this.lifecycleRevision
      const requestController = new AbortController()
      // 请求级缓冲：流式增量无论会话当前是否展示都先累积到这里；可见时同步镜像到消息列表，
      // 用户切走再切回时用缓冲重建，保证后台会话不丢增量、不丢终态结果。
      const requestAcc = { assistantId: '', content: '', reasoning: '', toolEvents: [], jobCards: [] }
      this.activeSessionRequests[requestSessionId] = {
        controller: requestController,
        key,
        turnId,
        acc: requestAcc,
      }
      this.applyCurrentRequestProjection(requestSessionId)
      const streamSignal = requestController.signal
      // 请求生命周期固定绑定创建时的 sessionId。切换会话后 SSE 继续读取并由后端完成持久化，
      // 但后台会话的增量不得写入当前页面；用户切回后，后续增量与终态全文继续正常展示。
      const isStreamStale = () => streamSignal.aborted || requestRevision !== this.lifecycleRevision
      const isRequestVisible = () => this.sessionId === requestSessionId
      const assistantId = reusableAssistantId || crypto.randomUUID()
      requestAcc.assistantId = assistantId
      // 续跑/换一批复用同一条助手消息时，视为已存在，避免收尾逻辑误判为空消息或漏清 pending。
      let assistantCreated = !!(reusableAssistantId && this.messages.some((item) => item.id === assistantId))
      let errorAppended = false
      let doneReceived = false
      const ensureAssistant = () => {
        if (!isRequestVisible()) return null
        let msg = this.messages.find((item) => item.id === assistantId)
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
          if (!msg.jobCards?.length && Array.isArray(this.lastJobCardsEvent) && this.lastJobCardsEvent.length)
            msg.jobCards = [...this.lastJobCardsEvent]
        }
        return msg
      }
      const appendAssistant = (text) => {
        if (!text || isStreamStale()) return
        requestAcc.content = requestAcc.content ? `${requestAcc.content}\n${text}` : text
        const msg = ensureAssistant()
        if (!msg) return
        msg.pending = false
        msg.content = requestAcc.content
      }
      let streamedDelta = false
      const appendAssistantDelta = (delta) => {
        if (!delta || isStreamStale()) return
        requestAcc.content += delta
        streamedDelta = true
        const msg = ensureAssistant()
        if (!msg) return
        msg.pending = false
        msg.content = requestAcc.content
      }
      const appendReasoningDelta = (delta) => {
        if (!delta || isStreamStale()) return
        requestAcc.reasoning += delta
        const msg = ensureAssistant()
        if (!msg) return
        msg.pending = false
        msg.reasoning = requestAcc.reasoning
      }
      const finishRequest = () => {
        this.clearSessionRequest(requestSessionId, key)
        if (!isRequestVisible()) return
        const msg = assistantCreated ? this.messages.find((item) => item.id === assistantId) : null
        if (msg?.pending) msg.pending = false
      }
      let requestCompleted = true
      try {
        await streamChat(
          {
            message: text,
            sessionId: requestSessionId,
            turnId,
            resumeId,
            resumeAfterAuth: !!options.resumeAfterAuth,
            flipJobs: !!options.flipJobs,
            selectedJob,
          },
          {
            signal: requestController.signal,
            session: (data) => {
              const assignedSessionId = String(data?.sessionId || '').trim()
              if (!assignedSessionId || isStreamStale()) return
              // 前端已经为请求分配并传递 sessionId，后端应原样复用。后台会话收到首包时不能抢占当前页面。
              if (assignedSessionId !== requestSessionId || !isRequestVisible()) return
              this.sessionId = assignedSessionId
              // 后端会在 SSE 首包返回新会话 ID。此时立即乐观写入历史列表，不能等待 Agent 执行完成后的 done 事件，
              // 否则长任务运行期间左侧历史记录始终缺少当前会话。done 后仍会通过 loadSessions 以服务端数据校准。
              this.upsertSessionImmediately(assignedSessionId, text)
              this.snapshotCurrentSession()
            },
            intent: (data) => {
              if (!isStreamStale() && isRequestVisible()) this.intent = data
            },
            trace: (data) => {
              if (!isStreamStale() && isRequestVisible()) this.trace = data
            },
            message: (data) => {
              if (isStreamStale()) return
              const answerText = typeof data === 'string' ? data : (data.content ?? data.text)
              if (answerText) {
                if (streamedDelta) {
                  // 终态全文与逐字累积一致时不重新赋值，避免 Markdown 全量重渲染闪烁。
                  if (answerText !== requestAcc.content) requestAcc.content = answerText
                  streamedDelta = false
                } else {
                  requestAcc.content = requestAcc.content ? `${requestAcc.content}\n${answerText}` : answerText
                }
              }
              // 终态 message 携带的工具事件与推理过程写入请求缓冲作为权威记录，
              // 即使随后从服务端重载，也已有完整过程，避免被未落库完成的快照覆盖丢失。
              const serverTools =
                data && typeof data === 'object' && Array.isArray(data.toolEvents)
                  ? filterVisibleToolEvents(data.toolEvents)
                  : []
              if (serverTools.length) requestAcc.toolEvents = serverTools
              const serverReasoning = data && typeof data === 'object' ? String(data.reasoning || '') : ''
              if (serverReasoning) requestAcc.reasoning = serverReasoning
              if (!isRequestVisible()) {
                // 后台会话的终态答案立即合并进快照，切回即可见，不依赖服务端落库进度。
                this.mergeRequestAccIntoSnapshot(requestSessionId, requestAcc)
                return
              }
              const msg = ensureAssistant()
              if (!msg) return
              msg.pending = false
              if (msg.content !== requestAcc.content) msg.content = requestAcc.content
              if (serverTools.length) {
                msg.toolEvents = serverTools
                this.toolEvents = serverTools
              }
              if (serverReasoning) msg.reasoning = serverReasoning
              this.snapshotCurrentSession()
            },
            auth_required: (data) => {
              if (!isStreamStale() && isRequestVisible())
                this.handleBossAuthRequired(data, { message: text, resumeId, selectedJob, turnId }, assistantId)
            },
            error: (data) => {
              if (errorAppended) return
              const errorText = formatSendError(data)
              errorAppended = true
              appendAssistant(`请求失败：${errorText}`)
              if (!isRequestVisible()) this.mergeRequestAccIntoSnapshot(requestSessionId, requestAcc)
            },
            done: (data) => {
              if (isStreamStale()) return
              doneReceived = true
              if (!isRequestVisible()) this.mergeRequestAccIntoSnapshot(requestSessionId, requestAcc)
              finishRequest()
              // 后台会话完成后也要强制刷新其服务端快照。若用户已经切回该会话则立即应用，
              // 否则只更新 sessionSnapshots，确保下一次进入可以看到完整终态。
              if (data?.ok !== false) this.syncSessionMessagesFromServer(requestSessionId).catch(() => {})
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
                  requestAcc.jobCards = rows
                  this.markBossLoggedIn({ status: 'logged_in', source: 'job_cards' })
                  if (!isRequestVisible()) {
                    this.mergeRequestAccIntoSnapshot(requestSessionId, requestAcc)
                  } else {
                    this.$patch({ lastJobCardsEvent: rows })
                    this.authRequired = null
                    this.pendingAuthRequest = null
                    const msg = ensureAssistant()
                    if (!msg) return
                    msg.jobCards = rows
                    if (this.toolEvents.length) msg.toolEvents = [...this.toolEvents]
                    this.snapshotCurrentSession()
                  }
                }
              }
              if (event === 'resume_match' && isRequestVisible()) this.$patch({ lastResumeMatchEvent: data })
              if (event === 'memory_context' && isRequestVisible())
                this.$patch({ lastMemoryContextEvent: Array.isArray(data) ? data : [] })
              if (event === 'personal_context' && isRequestVisible())
                this.$patch({ lastPersonalContextEvent: data && typeof data === 'object' ? data : null })
              if (event === 'tool_status') {
                this.accumulateToolEvent(requestAcc, data)
                if (isRequestVisible()) this.upsertToolEvent(data, assistantId)
              }
            },
          },
        )
        if (!isStreamStale() && isRequestVisible()) this.serviceError = ''
      } catch (error) {
        if (!isStreamStale() && !isAbortError(error)) {
          try {
            requestController.abort()
          } catch (_) {}
          if (isRequestVisible()) {
            const messageText = formatSendError(error)
            this.serviceError = messageText
            if (!errorAppended) {
              errorAppended = true
              appendAssistant(`请求失败：${messageText}`)
            }
          }
        }
      } finally {
        finishRequest()
        requestCompleted = !isStreamStale()
        if (requestCompleted) {
          // 后台会话在异常收尾（未收到 done）时也要把已累积的增量并入快照，切回时保留部分产出。
          if (!isRequestVisible() && !doneReceived) this.mergeRequestAccIntoSnapshot(requestSessionId, requestAcc)
          if (isRequestVisible()) {
            // 流正常返回但既没有 done 也没有 error 事件，说明连接被中途断开（服务端崩溃/网络掐断）。
            // 此时不能静默收尾让用户误以为回答完整：有部分内容则补一句中断提示，无产出则明确报错。
            if (!doneReceived && !errorAppended && !streamSignal.aborted) {
              const partial = assistantCreated ? this.messages.find((item) => item.id === assistantId) : null
              if (partial && String(partial.content || '').trim()) {
                partial.content = `${partial.content}\n\n（连接中断，回答可能不完整，请重试。）`
              } else if (!partial || (!partial.jobCards?.length && !partial.toolEvents?.length)) {
                this.serviceError = '连接中断，请稍后重试。'
                errorAppended = true
                appendAssistant('请求失败：连接中断，请稍后重试。')
              }
            }
            const msg = assistantCreated ? this.messages.find((item) => item.id === assistantId) : null
            if (msg && !String(msg.content || '').trim() && !msg.jobCards?.length && !msg.toolEvents?.length) {
              this.messages = this.messages.filter((item) => item.id !== assistantId)
            }
          }
        }
      }
      return requestCompleted
    },
    stop() {
      const sessionId = this.sessionId
      const request = this.activeSessionRequests[sessionId]
      const controller = request?.controller
      if (!request) return
      try {
        controller?.abort()
      } catch (_) {}
      this.clearSessionRequest(sessionId, request.key)
      this.toolEvents = this.toolEvents.map((item) =>
        item.status === 'running' ? { ...item, status: 'cancelled', detail: '已停止' } : item,
      )
      const lastAssistant = [...this.messages].reverse().find((item) => item.role === 'assistant')
      if (lastAssistant) {
        lastAssistant.pending = false
        if (!lastAssistant.content && !lastAssistant.jobCards?.length) lastAssistant.content = '已停止当前请求。'
      }
      this.snapshotCurrentSession()
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
      const revision = this.lifecycleRevision
      let request
      request = getBossLoginStatus(this.sessionId, null)
        .then((status) => {
          if (revision !== this.lifecycleRevision) return { authenticated: false, stale: true }
          if (isBossAuthenticated(status)) this.markBossLoggedIn(status)
          else this.markBossLoggedOut(status)
          return this.bossAuthStatus
        })
        .catch((error) => {
          if (revision !== this.lifecycleRevision) return { authenticated: false, stale: true }
          this.markBossLoggedOut({ status: 'error', error: error?.message || String(error || '') })
          return this.bossAuthStatus
        })
        .finally(() => {
          if (revision === this.lifecycleRevision && this.bossAuthCheckPromise === request)
            this.bossAuthCheckPromise = null
        })
      this.bossAuthCheckPromise = request
      return request
    },
    async handleBossAuthRequired(data, pending, assistantId) {
      if (data && data.authRequired === false) {
        this.authRequired = null
        this.pendingAuthRequest = null
        return
      }
      const hasResults = Array.isArray(this.lastJobCardsEvent) && this.lastJobCardsEvent.length > 0
      const existing = this.messages.find((item) => item.id === assistantId)
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
        msg = {
          id: assistantId,
          role: 'assistant',
          content: '',
          pending: false,
          toolEvents: [...this.toolEvents],
          jobCards: [],
        }
        this.messages.push(msg)
      }
      msg.pending = false
      msg.toolEvents = [...this.toolEvents]
      msg.content =
        data?.message || 'Boss 直聘需要重新扫码登录。请在弹窗中完成登录后，我会从当前进度继续处理刚才的请求。'
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
        await new Promise((resolve) => window.setTimeout(resolve, 50))
      }
      // 仍未收尾则强制清理超时未收尾的在途请求，避免 send 因 loading/inFlightRequestKey 被静默拒绝，
      // 导致用户扫码后请求丢失、卡在登录态确认。
      if (this.loading || this.inFlightRequestKey) this.stop()
      await this.send(pending.message, pending.resumeId, {
        replay: true,
        resumeAfterAuth: true,
        selectedJob: pending.selectedJob,
        assistantId: pending.assistantId,
        turnId: pending.turnId,
      })
    },
    upsertToolEvent(data, assistantId) {
      if (!data || !data.id) return
      const now = Date.now()
      const idx = this.toolEvents.findIndex((item) => item.id === data.id)
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
      let msg = this.messages.find((item) => item.id === assistantId)
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
