import { describe, it, expect } from 'vitest'
import {
  normalizeMessageText,
  requestKey,
  isAbortError,
  formatSendError,
  isBossAuthenticated,
  normalizeToolEvent,
  isMemoryNoiseEvent,
  filterVisibleToolEvents,
} from '../src/utils/chatHelpers'

describe('normalizeMessageText', () => {
  it('collapses whitespace and trims', () => {
    expect(normalizeMessageText('  hello   world \n ')).toBe('hello world')
  })
  it('handles nullish input', () => {
    expect(normalizeMessageText(null)).toBe('')
    expect(normalizeMessageText(undefined)).toBe('')
  })
})

describe('requestKey', () => {
  it('builds a stable key from parts', () => {
    expect(requestKey('s1', 'r1', '  hi  ')).toBe('s1::r1::hi::')
  })
  it('falls back to placeholders and reads selected job identity', () => {
    const key = requestKey(null, null, 'x', { securityId: 'sec-9' })
    expect(key).toBe('new::none::x::sec-9')
  })
  it('treats whitespace-different messages as the same key', () => {
    expect(requestKey('s', 'r', 'a b')).toBe(requestKey('s', 'r', 'a   b'))
  })
})

describe('isAbortError', () => {
  it('detects AbortError by name and message', () => {
    expect(isAbortError({ name: 'AbortError' })).toBe(true)
    expect(isAbortError({ message: 'the operation was aborted' })).toBe(true)
    expect(isAbortError({ message: 'boom' })).toBe(false)
  })
})

describe('formatSendError', () => {
  it('maps network failures to a friendly message', () => {
    expect(formatSendError({ message: 'Failed to fetch' })).toContain('服务暂时不可用')
    expect(formatSendError({ message: 'Load failed' })).toContain('服务暂时不可用')
  })
  it('passes through other messages', () => {
    expect(formatSendError({ message: '限流' })).toBe('限流')
  })
  it('defaults when empty', () => {
    expect(formatSendError(null)).toBe('请求失败，请稍后重试。')
  })
})

describe('isBossAuthenticated', () => {
  it('reads top-level and nested data flags', () => {
    expect(isBossAuthenticated({ authenticated: true })).toBe(true)
    expect(isBossAuthenticated({ data: { status: 'logged_in' } })).toBe(true)
    expect(isBossAuthenticated({ status: 'auth_required' })).toBe(false)
    expect(isBossAuthenticated(null)).toBe(false)
  })
})

describe('tool event helpers', () => {
  it('normalizeToolEvent fills name and detail fallbacks', () => {
    const out = normalizeToolEvent({ id: 'x', title: 'T', summary: 'S' })
    expect(out.name).toBe('T')
    expect(out.detail).toBe('S')
  })
  it('isMemoryNoiseEvent matches by id/name only', () => {
    expect(isMemoryNoiseEvent({ id: 'memory_search' })).toBe(true)
    expect(isMemoryNoiseEvent({ name: '记忆读取' })).toBe(true)
    expect(isMemoryNoiseEvent({ summary: '包含memory字样的摘要' })).toBe(false)
  })
  it('filterVisibleToolEvents drops connect and memory noise', () => {
    const events = [
      { id: 'sse_connect' },
      { id: 'memory_search' },
      { id: 'boss_browser', title: 'Boss' },
    ]
    const visible = filterVisibleToolEvents(events)
    expect(visible).toHaveLength(1)
    expect(visible[0].name).toBe('Boss')
  })
  it('filterVisibleToolEvents tolerates non-array input', () => {
    expect(filterVisibleToolEvents(null)).toEqual([])
  })
})
