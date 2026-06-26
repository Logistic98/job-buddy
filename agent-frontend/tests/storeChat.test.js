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

  it('newSession clears conversation state', () => {
    const store = useChatStore()
    store.sessionId = 's1'
    store.messages = [{ id: 'm1', role: 'user', content: 'hi' }]
    store.toolEvents = [{ id: 't1', status: 'running' }]
    store.serviceError = 'boom'
    store.newSession()
    expect(store.sessionId).toBe('')
    expect(store.messages).toEqual([])
    expect(store.toolEvents).toEqual([])
    expect(store.serviceError).toBe('')
  })

  it('openSession loads server rows into messages', async () => {
    listSessionMessages.mockResolvedValue([
      { role: 'user', content: '你好' },
      { role: 'assistant', content: '你好，有什么可以帮你？' },
    ])
    const store = useChatStore()
    const ok = await store.openSession('s1')
    expect(ok).toBe(true)
    expect(store.sessionId).toBe('s1')
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].content).toContain('帮你')
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
    const roles = store.messages.map(m => m.role)
    expect(roles).toEqual(['user', 'assistant'])
    expect(store.messages[0].content).toBe('在吗')
    expect(store.messages[1].content).toBe('你好，世界')
  })

  it('blocks duplicate submits within the dedupe window', async () => {
    streamChat.mockImplementation(async (payload, handlers) => { handlers.done?.() })
    const store = useChatStore()
    await store.send('重复消息')
    const ok = await store.send('重复消息')
    expect(ok).toBe(false)
    expect(streamChat).toHaveBeenCalledTimes(1)
  })

  it('surfaces stream errors as an assistant message', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.error?.({ message: '模型超时' })
      handlers.done?.()
    })
    const store = useChatStore()
    await store.send('触发错误')
    const assistant = store.messages.find(m => m.role === 'assistant')
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
      { id: 'a1', role: 'assistant', content: '', jobCards: [{ securityId: 'old-job' }], toolEvents: [{ id: 'old', status: 'success' }] },
    ]
    store.lastJobCardsEvent = [{ securityId: 'old-job' }]

    const ok = await store.send('换一批', 'r1', { replay: true, flipJobs: true, assistantId: 'a1' })

    expect(ok).toBe(true)
    expect(streamChat).toHaveBeenCalledWith(expect.objectContaining({ flipJobs: true, resumeAfterAuth: false }), expect.any(Object))
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].id).toBe('a1')
    expect(store.messages[1].jobCards).toEqual([{ securityId: 'new-job', jobName: '新岗位' }])
    expect(store.messages[1].toolEvents.map(item => item.id)).toEqual(['job_flip'])
  })

  it('keeps existing job cards when a flip attempt reports auth_required', async () => {
    streamChat.mockImplementation(async (payload, handlers) => {
      handlers.onEvent?.('tool_status', { id: 'job_search', status: 'error', name: '需要登录 Boss 直聘', detail: '登录态不完整' })
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
    expect(store.messages[1].toolEvents.map(item => item.id)).toEqual(['job_search'])
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
    const assistant = store.messages.find(m => m.id === 'a1')
    expect(assistant.toolEvents).toHaveLength(1)
  })

  it('stop cancels running tool events and clears loading', () => {
    const store = useChatStore()
    store.loading = true
    store.abortController = new AbortController()
    store.toolEvents = [{ id: 't1', status: 'running' }]
    store.messages = [{ id: 'a1', role: 'assistant', content: '', jobCards: [] }]
    store.stop()
    expect(store.loading).toBe(false)
    expect(store.toolEvents[0].status).toBe('cancelled')
    expect(store.messages[0].content).toBe('已停止当前请求。')
  })
})
