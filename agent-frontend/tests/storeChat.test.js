import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/chat', () => ({
  listSessions: vi.fn(async () => []),
  listSessionMessages: vi.fn(async () => []),
  deleteSession: vi.fn(async () => ({})),
  streamChat: vi.fn(async () => {}),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: vi.fn(async () => ({ status: 'logged_in' })),
}))

import { listSessionMessages, listSessions, streamChat } from '../src/api/chat'
import { getBossLoginStatus } from '../src/api/boss'
import { useChatStore } from '../src/stores/chat'

describe('chat store - session lifecycle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listSessions.mockResolvedValue([])
    listSessionMessages.mockResolvedValue([])
  })

  it('initializes and resets job card state as an empty array', () => {
    const store = useChatStore()

    expect(store.lastJobCardsEvent).toEqual([])
    store.lastJobCardsEvent = [{ securityId: 'stale-job' }]
    store.$reset()

    expect(store.lastJobCardsEvent).toEqual([])
  })

  it('drops a late account-A session list after disposeForAuthChange', async () => {
    let resolveSessions
    listSessions.mockReturnValue(
      new Promise((resolve) => {
        resolveSessions = resolve
      }),
    )
    const store = useChatStore()

    const loading = store.loadSessions()
    store.disposeForAuthChange()
    resolveSessions([{ sessionId: 'account-a-session', title: '账号 A 会话' }])
    await loading

    expect(store.sessions).toEqual([])
    expect(store.serviceError).toBe('')
  })

  it('newSession clears conversation state', () => {
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [{ id: 'm1', role: 'user', content: 'hi' }]
    store.toolEvents = [{ id: 't1', status: 'running' }]
    store.lastJobCardsEvent = [{ securityId: 'stale-job' }]
    store.serviceError = 'boom'
    store.newSession()
    expect(store.sessionId).toBe('')
    expect(store.messages).toEqual([])
    expect(store.toolEvents).toEqual([])
    expect(store.lastJobCardsEvent).toEqual([])
    expect(store.serviceError).toBe('')
  })

  it('openSession restores server rows and derived job card state', async () => {
    listSessionMessages.mockResolvedValue([
      { role: 'user', content: '你好' },
      { role: 'assistant', content: '你好，有什么可以帮你？', jobCards: [{ securityId: 'restored-job' }] },
    ])
    const store = useChatStore()
    const ok = await store.openSession('s1')
    expect(ok).toBe(true)
    expect(store.sessionId).toBe('s1')
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].content).toContain('帮你')
    expect(store.lastJobCardsEvent).toEqual([{ securityId: 'restored-job' }])
  })

  it('keeps local conversation content across repeated switches while persistence is delayed', async () => {
    listSessionMessages.mockImplementation(async (sessionId) =>
      sessionId === 's2' ? [{ role: 'user', content: '另一会话' }] : [],
    )
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [{ id: 'local-user', role: 'user', content: '不能丢失的问题' }]
    store.snapshotCurrentSession()

    await store.openSession('s2')
    await store.openSession('s1')
    await store.openSession('s2')
    await store.openSession('s1')

    expect(store.sessionId).toBe('s1')
    expect(store.messages.map((item) => item.content)).toEqual(['不能丢失的问题'])
  })

  it('does not let a stale prefetch response overwrite an explicit session refresh', async () => {
    let resolvePrefetch
    listSessionMessages
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolvePrefetch = resolve
          }),
      )
      .mockResolvedValueOnce([
        { role: 'user', content: '已持久化问题' },
        { role: 'assistant', content: '已持久化回答' },
      ])
    const store = useChatStore()

    const prefetch = store.loadSessionMessagesCached('s1')
    const opened = await store.openSession('s1')
    resolvePrefetch([])
    await prefetch

    expect(opened).toBe(true)
    expect(listSessionMessages).toHaveBeenCalledTimes(2)
    expect(store.messages.map((item) => item.content)).toEqual(['已持久化问题', '已持久化回答'])
    expect(store.sessionSnapshots.s1.messages).toHaveLength(2)
  })

  it('switches away without aborting and resumes the active request projection when returning', async () => {
    const controller = new AbortController()
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [{ id: 'u1', role: 'user', content: '后台问题' }]
    store.activeSessionRequests.s1 = { controller, key: 'request-s1' }
    store.applyCurrentRequestProjection('s1')
    listSessionMessages.mockResolvedValue([{ role: 'user', content: '其他会话' }])

    await store.openSession('s2')
    expect(controller.signal.aborted).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.sessionId).toBe('s2')

    await store.openSession('s1')
    expect(controller.signal.aborted).toBe(false)
    expect(store.loading).toBe(true)
    expect(store.abortController?.signal.aborted).toBe(false)
    expect(store.messages.map((item) => item.content)).toEqual(['后台问题'])
  })

  it('removeSession drops the snapshot and resets when active', async () => {
    const store = useChatStore()
    store.sessionId = 's1'
    store.sessionSnapshots = { s1: { messages: [{ id: 'x', role: 'user', content: 'hi' }] } }
    await store.removeSession('s1')
    expect(store.sessionSnapshots.s1).toBeUndefined()
    expect(store.sessionId).toBe('')
  })
})

describe('chat store - send', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listSessions.mockResolvedValue([])
    listSessionMessages.mockResolvedValue([])
  })

  it('drops late stream events after an authentication change', async () => {
    let handlers
    let finish
    streamChat.mockImplementation(
      (_payload, nextHandlers) =>
        new Promise((resolve) => {
          handlers = nextHandlers
          finish = resolve
        }),
    )
    const store = useChatStore()
    const sending = store.send('账号 A 的问题')
    expect(store.loading).toBe(true)

    store.disposeForAuthChange()
    handlers.onEvent?.('message_delta', { delta: '账号 A 的迟到回答' })
    handlers.message?.({ content: '账号 A 的最终回答' })
    handlers.done?.({ ok: true })
    finish()
    await expect(sending).resolves.toBe(false)

    expect(store.messages).toEqual([])
    expect(store.sessionSnapshots).toEqual({})
    expect(store.loading).toBe(false)
  })

  it('ignores empty messages', async () => {
    const store = useChatStore()
    const ok = await store.send('   ')
    expect(ok).toBe(false)
    expect(streamChat).not.toHaveBeenCalled()
  })

  it('streams a delta then settles the final assistant answer', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.session?.({ sessionId: 's1' })
      handlers.onEvent?.('message_delta', { delta: '你好' })
      handlers.message?.({ content: '你好，世界' })
      handlers.done?.()
    })
    const store = useChatStore()
    const ok = await store.send('在吗')
    expect(ok).toBe(true)
    expect(store.loading).toBe(false)
    const roles = store.messages.map((m) => m.role)
    expect(roles).toEqual(['user', 'assistant'])
    expect(store.messages[0].content).toBe('在吗')
    expect(store.messages[1].content).toBe('你好，世界')
  })

  it('adds a new session to history immediately without waiting for the session event', async () => {
    let finishStream
    let streamHandlers
    let streamPayload
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          streamPayload = payload
          streamHandlers = handlers
          finishStream = () => {
            handlers.done?.({ ok: true })
            resolve()
          }
        }),
    )
    const store = useChatStore()
    store.sessions = [{ sessionId: 's-old', title: '已有会话', updatedAt: '2026-07-19T00:00:00.000Z' }]

    const sendPromise = store.send('立即显示在历史记录中')

    expect(store.loading).toBe(true)
    expect(store.sessionId).toMatch(/^sess_[a-f0-9]{12}$/)
    expect(streamPayload.sessionId).toBe(store.sessionId)
    expect(store.sessions.map((item) => item.sessionId)).toEqual([store.sessionId, 's-old'])
    expect(store.sessions[0].title).toBe('立即显示在历史记录中')

    streamHandlers.session?.({ sessionId: store.sessionId })
    streamHandlers.session?.({ sessionId: store.sessionId })
    expect(store.sessions.filter((item) => item.sessionId === store.sessionId)).toHaveLength(1)

    finishStream()
    await sendPromise
  })

  it('keeps an optimistic session when a concurrent server refresh cannot see it yet', async () => {
    let finishStream
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          finishStream = () => {
            handlers.done?.({ ok: true })
            resolve()
          }
        }),
    )
    listSessions.mockResolvedValue([{ sessionId: 's-old', title: '已有会话', updatedAt: '2026-07-19T00:00:00.000Z' }])
    const store = useChatStore()

    const sendPromise = store.send('并发刷新也不能消失')
    const optimisticSessionId = store.sessionId
    await store.loadSessions()

    expect(store.sessions.map((item) => item.sessionId)).toEqual([optimisticSessionId, 's-old'])
    expect(store.sessions[0].title).toBe('并发刷新也不能消失')

    finishStream()
    await sendPromise
  })

  it('keeps streaming in the original session without polluting the visible session', async () => {
    let streamHandlers
    let finishStream
    let finalRows = [{ role: 'user', content: '后台生成问题' }]
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          streamHandlers = handlers
          finishStream = () => {
            finalRows = [
              { role: 'user', content: '后台生成问题' },
              { role: 'assistant', content: '后台生成完成' },
            ]
            handlers.message?.({ content: '后台生成完成' })
            handlers.done?.({ ok: true })
            resolve()
          }
        }),
    )
    listSessionMessages.mockImplementation(async (sessionId) =>
      sessionId === 's-other' ? [{ role: 'user', content: '当前可见会话' }] : finalRows,
    )
    const store = useChatStore()

    const sendPromise = store.send('后台生成问题')
    const backgroundSessionId = store.sessionId
    const backgroundController = store.activeSessionRequests[backgroundSessionId].controller
    await store.openSession('s-other')

    expect(backgroundController.signal.aborted).toBe(false)
    expect(store.loading).toBe(false)
    streamHandlers.onEvent?.('message_delta', { delta: '不能串到当前会话' })
    expect(store.messages.map((item) => item.content)).toEqual(['当前可见会话'])

    finishStream()
    await sendPromise
    await vi.waitFor(() => {
      expect(store.sessionSnapshots[backgroundSessionId].messages.map((item) => item.content)).toEqual([
        '后台生成问题',
        '后台生成完成',
      ])
    })
    expect(store.sessionId).toBe('s-other')
    expect(store.messages.map((item) => item.content)).toEqual(['当前可见会话'])

    await store.openSession(backgroundSessionId)
    expect(store.loading).toBe(false)
    expect(store.messages.map((item) => item.content)).toEqual(['后台生成问题', '后台生成完成'])
  })

  it('replays background deltas when switching back before the stream finishes', async () => {
    let streamHandlers
    let finishStream
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          streamHandlers = handlers
          finishStream = () => {
            handlers.done?.({ ok: true })
            resolve()
          }
        }),
    )
    listSessionMessages.mockImplementation(async (sessionId) =>
      sessionId === 's-other' ? [{ role: 'user', content: '当前可见会话' }] : [],
    )
    const store = useChatStore()

    const sendPromise = store.send('后台流式问题')
    const backgroundSessionId = store.sessionId
    streamHandlers.onEvent?.('message_delta', { delta: '第一段' })
    await store.openSession('s-other')

    streamHandlers.onEvent?.('message_delta', { delta: '，离开期间的第二段' })
    streamHandlers.onEvent?.('tool_status', { id: 'tool-bg', status: 'success', name: '后台工具' })
    expect(store.messages.map((item) => item.content)).toEqual(['当前可见会话'])

    await store.openSession(backgroundSessionId)
    expect(store.loading).toBe(true)
    const assistant = store.messages.find((item) => item.role === 'assistant')
    expect(assistant.content).toBe('第一段，离开期间的第二段')
    expect(assistant.toolEvents.map((item) => item.id)).toEqual(['tool-bg'])

    streamHandlers.onEvent?.('message_delta', { delta: '，回来后的第三段' })
    expect(store.messages.find((item) => item.role === 'assistant').content).toBe(
      '第一段，离开期间的第二段，回来后的第三段',
    )

    finishStream()
    await sendPromise
  })

  it('shows the final background answer on return even when server persistence lags', async () => {
    let finishStream
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          finishStream = () => {
            handlers.message?.({ content: '后台终态答案', reasoning: '后台推理过程' })
            handlers.done?.({ ok: true })
            resolve()
          }
        }),
    )
    // 服务端落库滞后：历史接口始终只返回用户消息，不包含助手答案。
    listSessionMessages.mockImplementation(async (sessionId) =>
      sessionId === 's-other'
        ? [{ role: 'user', content: '当前可见会话' }]
        : [{ role: 'user', content: '后台终态问题' }],
    )
    const store = useChatStore()

    const sendPromise = store.send('后台终态问题')
    const backgroundSessionId = store.sessionId
    await store.openSession('s-other')

    finishStream()
    await sendPromise
    await vi.waitFor(() => {
      const rows = store.sessionSnapshots[backgroundSessionId].messages
      expect(rows.map((item) => item.content)).toEqual(['后台终态问题', '后台终态答案'])
    })

    await store.openSession(backgroundSessionId)
    expect(store.messages.map((item) => item.content)).toEqual(['后台终态问题', '后台终态答案'])
    expect(store.messages[1].reasoning).toBe('后台推理过程')
  })

  it('allows different sessions to own concurrent requests', async () => {
    const streams = []
    streamChat.mockImplementation(
      (payload, handlers) =>
        new Promise((resolve) => {
          streams.push({ payload, handlers, resolve })
        }),
    )
    listSessionMessages.mockResolvedValue([{ role: 'user', content: '第二个会话' }])
    const store = useChatStore()

    const firstPromise = store.send('第一个后台请求')
    const firstSessionId = store.sessionId
    await store.openSession('s2')
    const secondPromise = store.send('第二个会话请求')

    expect(Object.keys(store.activeSessionRequests).sort()).toEqual([firstSessionId, 's2'].sort())
    expect(streams[0].handlers.signal.aborted).toBe(false)
    expect(streams[1].handlers.signal.aborted).toBe(false)

    streams[1].handlers.done?.({ ok: true })
    streams[1].resolve()
    await secondPromise
    await store.openSession(firstSessionId)
    expect(store.loading).toBe(true)

    streams[0].handlers.done?.({ ok: true })
    streams[0].resolve()
    await firstPromise
  })

  it('blocks duplicate submits within the dedupe window', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.done?.()
    })
    const store = useChatStore()
    await store.send('重复消息')
    const ok = await store.send('重复消息')
    expect(ok).toBe(false)
    expect(streamChat).toHaveBeenCalledTimes(1)
  })

  it('reuses the original turn id when continuing after Boss authentication', async () => {
    const payloads = []
    getBossLoginStatus.mockResolvedValue({ status: 'logged_out' })
    streamChat.mockImplementation(async (payload, handlers) => {
      payloads.push(payload)
      if (payloads.length === 1) {
        handlers.auth_required?.({ authRequired: true, message: '请扫码登录' })
        handlers.done?.({ ok: false })
      } else {
        handlers.message?.({ content: '续跑完成' })
        handlers.done?.({ ok: true })
      }
    })
    const store = useChatStore()

    await store.send('筛选岗位', 'resume-1')
    await vi.waitFor(() => expect(store.pendingAuthRequest?.turnId).toBe(payloads[0].turnId))
    const originalTurnId = payloads[0].turnId
    expect(store.messages.filter((item) => item.role === 'user')).toEqual([
      expect.objectContaining({ id: originalTurnId, turnId: originalTurnId, content: '筛选岗位' }),
    ])
    expect(store.sessionSnapshots[store.sessionId].messages[0].turnId).toBe(originalTurnId)

    await store.resumeAfterAuth()

    expect(payloads).toHaveLength(2)
    expect(payloads[1]).toEqual(
      expect.objectContaining({
        turnId: originalTurnId,
        resumeAfterAuth: true,
        message: '筛选岗位',
      }),
    )
    expect(store.messages.filter((item) => item.role === 'user')).toHaveLength(1)
  })

  it('surfaces stream errors as an assistant message', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.error?.({ message: '模型超时' })
      handlers.done?.()
    })
    const store = useChatStore()
    await store.send('触发错误')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant.content).toContain('模型超时')
  })

  it('reuses the current assistant message when flipping job batches', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.onEvent?.('tool_status', { id: 'job_flip', status: 'success', name: '换一批', detail: '第 2 批' })
      handlers.onEvent?.('job_cards', [{ securityId: 'new-job', jobName: '新岗位' }])
      handlers.done?.({ ok: true })
    })
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [
      { id: 'u1', role: 'user', content: '筛选岗位' },
      {
        id: 'a1',
        role: 'assistant',
        content: '',
        jobCards: [{ securityId: 'old-job' }],
        toolEvents: [{ id: 'old', status: 'success' }],
      },
    ]
    store.lastJobCardsEvent = [{ securityId: 'old-job' }]

    const ok = await store.send('换一批', 'r1', { replay: true, flipJobs: true, assistantId: 'a1' })

    expect(ok).toBe(true)
    expect(streamChat).toHaveBeenCalledWith(
      expect.objectContaining({ flipJobs: true, resumeAfterAuth: false }),
      expect.any(Object),
    )
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].id).toBe('a1')
    expect(store.messages[1].jobCards).toEqual([{ securityId: 'new-job', jobName: '新岗位' }])
    expect(store.messages[1].toolEvents.map((item) => item.id)).toEqual(['job_flip'])
  })

  it('keeps existing job cards when a flip attempt reports auth_required', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.onEvent?.('tool_status', {
        id: 'job_search',
        status: 'error',
        name: '需要登录 Boss 直聘',
        detail: '登录态不完整',
      })
      handlers.auth_required?.({ authRequired: true, message: '请重新登录' })
      handlers.done?.({ ok: false })
    })
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [
      { id: 'u1', role: 'user', content: '筛选岗位' },
      { id: 'a1', role: 'assistant', content: '', jobCards: [{ securityId: 'old-job' }], toolEvents: [] },
    ]
    store.lastJobCardsEvent = [{ securityId: 'old-job' }]

    const ok = await store.send('换一批', 'r1', { replay: true, flipJobs: true, assistantId: 'a1' })

    expect(ok).toBe(true)
    expect(getBossLoginStatus).not.toHaveBeenCalled()
    expect(listSessionMessages).not.toHaveBeenCalled()
    expect(store.authRequired).toBeNull()
    expect(store.pendingAuthRequest).toBeNull()
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].id).toBe('a1')
    expect(store.messages[1].content).toBe('')
    expect(store.messages[1].jobCards).toEqual([{ securityId: 'old-job' }])
    expect(store.messages[1].toolEvents.map((item) => item.id)).toEqual(['job_search'])
  })
})

describe('chat store - tool events and stop', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listSessions.mockResolvedValue([])
    listSessionMessages.mockResolvedValue([])
  })

  it('upsertToolEvent appends then updates by id and attaches to the assistant message', () => {
    const store = useChatStore()
    store.upsertToolEvent({ id: 'tool-1', status: 'running', name: 'boss_browser' }, 'a1')
    expect(store.toolEvents).toHaveLength(1)
    store.upsertToolEvent({ id: 'tool-1', status: 'success', name: 'boss_browser' }, 'a1')
    expect(store.toolEvents).toHaveLength(1)
    expect(store.toolEvents[0].status).toBe('success')
    const assistant = store.messages.find((m) => m.id === 'a1')
    expect(assistant.toolEvents).toHaveLength(1)
  })

  it('stop cancels only the current session request', () => {
    const store = useChatStore()
    const currentController = new AbortController()
    const backgroundController = new AbortController()
    store.sessionId = 's1'
    store.activeSessionRequests = {
      s1: { controller: currentController, key: 'current' },
      s2: { controller: backgroundController, key: 'background' },
    }
    store.applyCurrentRequestProjection('s1')
    store.toolEvents = [{ id: 't1', status: 'running' }]
    store.messages = [{ id: 'a1', role: 'assistant', content: '', jobCards: [] }]

    store.stop()

    expect(currentController.signal.aborted).toBe(true)
    expect(backgroundController.signal.aborted).toBe(false)
    expect(store.activeSessionRequests.s1).toBeUndefined()
    expect(store.activeSessionRequests.s2).toBeDefined()
    expect(store.loading).toBe(false)
    expect(store.toolEvents[0].status).toBe('cancelled')
    expect(store.messages[0].content).toBe('已停止当前请求。')
  })
})
