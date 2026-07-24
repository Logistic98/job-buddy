import { describe, expect, it } from 'vitest'
import {
  backfillFromPrevious,
  buildSnapshotFromMessages,
  buildSnapshotFromRows,
  lastJobCards,
  lastResumeMatch,
  lastToolEvents,
  normalizeSessionRows,
} from '../src/utils/chatSnapshots'

describe('normalizeSessionRows', () => {
  it('maps rows to stable ids and defaults missing fields', () => {
    const rows = [
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: 'yo', reasoning: 'r' },
    ]
    const out = normalizeSessionRows('s1', rows)
    expect(out.map((m) => m.id)).toEqual(['s1_0', 's1_1'])
    expect(out[0].reasoning).toBe('')
    expect(out[0].jobCards).toEqual([])
    expect(out[1].reasoning).toBe('r')
  })
  it('uses server turn ids and merges duplicate rows from the same turn', () => {
    const rows = [
      { turnId: 'turn-1', role: 'user', content: '筛选岗位' },
      { turnId: 'turn-1', role: 'user', content: '筛选岗位' },
    ]

    const out = normalizeSessionRows('s1', rows)

    expect(out).toHaveLength(1)
    expect(out[0].id).toBe('turn-1')
    expect(out[0].turnId).toBe('turn-1')
  })
  it('folds only consecutive legacy duplicate user rows before an assistant reply', () => {
    const rows = [
      { role: 'user', content: '重复问题' },
      { role: 'user', content: '重复问题' },
      { role: 'assistant', content: '第一次回答' },
      { role: 'user', content: '重复问题' },
    ]

    const out = normalizeSessionRows('s1', rows)

    expect(out.map((item) => item.content)).toEqual(['重复问题', '第一次回答', '重复问题'])
  })
  it('keeps identical user text with different turn ids after an assistant reply', () => {
    const out = normalizeSessionRows('s1', [
      { turnId: 'turn-a', role: 'user', content: '再查一次' },
      { turnId: 'turn-answer', role: 'assistant', content: '第一次结果' },
      { turnId: 'turn-b', role: 'user', content: '再查一次' },
    ])

    expect(out).toHaveLength(3)
  })
  it('keeps consecutive identical user text when both rows have different turn ids', () => {
    const out = normalizeSessionRows('s1', [
      { turnId: 'turn-a', role: 'user', content: '再查一次' },
      { turnId: 'turn-b', role: 'user', content: '再查一次' },
    ])

    expect(out.map((item) => item.turnId)).toEqual(['turn-a', 'turn-b'])
  })
  it('returns empty array for empty input', () => {
    expect(normalizeSessionRows('s1', [])).toEqual([])
  })
  it('drops memory-noise tool events', () => {
    const rows = [{ role: 'assistant', content: 'a', toolEvents: [{ id: 'memory_read' }, { id: 'boss' }] }]
    expect(normalizeSessionRows('s', rows)[0].toolEvents.map((t) => t.id)).toEqual(['boss'])
  })
})

describe('backfillFromPrevious', () => {
  it('fills missing reasoning/tools/jobs from previous same-role messages', () => {
    const current = [{ role: 'assistant', content: 'a', reasoning: '', toolEvents: [], jobCards: [] }]
    const previous = [
      { role: 'assistant', content: 'a', reasoning: 'kept', toolEvents: [{ id: 't' }], jobCards: [{ id: 'j' }] },
    ]
    const out = backfillFromPrevious(current, previous)
    expect(out[0].reasoning).toBe('kept')
    expect(out[0].toolEvents).toHaveLength(1)
    expect(out[0].jobCards).toHaveLength(1)
  })
  it('does not backfill when roles differ', () => {
    const current = [{ role: 'user', content: 'a', reasoning: '', toolEvents: [], jobCards: [] }]
    const previous = [{ role: 'assistant', content: 'a', reasoning: 'kept' }]
    expect(backfillFromPrevious(current, previous)[0].reasoning).toBe('')
  })
})

describe('buildSnapshotFromRows', () => {
  it('derives last job cards, tools and resume match', () => {
    const rows = [
      { role: 'user', content: 'q' },
      {
        role: 'assistant',
        content: 'a',
        jobCards: [{ id: 'j1' }],
        toolEvents: [{ id: 'boss' }],
        resumeMatch: { score: 9 },
      },
    ]
    const snap = buildSnapshotFromRows('s1', rows)
    expect(snap.lastJobCardsEvent).toEqual([{ id: 'j1' }])
    expect(snap.toolEvents.map((t) => t.id)).toEqual(['boss'])
    expect(snap.lastResumeMatchEvent).toEqual({ score: 9 })
    expect(snap.messages).toHaveLength(2)
  })
  it('returns deep clones independent from the input', () => {
    const rows = [{ role: 'assistant', content: 'a', jobCards: [{ id: 'j1' }] }]
    const snap = buildSnapshotFromRows('s1', rows)
    rows[0].jobCards[0].id = 'mutated'
    expect(snap.lastJobCardsEvent[0].id).toBe('j1')
  })
  it('keeps a non-empty local snapshot when the server history is temporarily empty', () => {
    const previous = buildSnapshotFromMessages({
      messages: [{ id: 'local-user', role: 'user', content: '刚刚发送的问题' }],
      toolEvents: [],
      lastJobCardsEvent: [],
    })
    const snap = buildSnapshotFromRows('s1', [], previous)
    expect(snap.messages).toEqual(previous.messages)
    expect(snap).not.toBe(previous)
  })
  it('keeps the local tail when the server only returns a persisted prefix', () => {
    const previous = buildSnapshotFromMessages({
      messages: [
        { id: 'local-user', role: 'user', content: '问题' },
        { id: 'local-assistant', role: 'assistant', content: '生成中的回答' },
      ],
      toolEvents: [],
      lastJobCardsEvent: [],
    })
    const snap = buildSnapshotFromRows('s1', [{ role: 'user', content: '问题' }], previous)
    expect(snap.messages.map((item) => item.content)).toEqual(['问题', '生成中的回答'])
  })
})

describe('buildSnapshotFromMessages', () => {
  it('snapshots the live state and filters tool noise', () => {
    const snap = buildSnapshotFromMessages({
      messages: [{ id: 'm', role: 'assistant', content: 'a' }],
      toolEvents: [{ id: 'memory_x' }, { id: 'boss' }],
      lastJobCardsEvent: [{ id: 'j' }],
      lastResumeMatchEvent: { score: 1 },
      lastPersonalContextEvent: null,
    })
    expect(snap.messages).toHaveLength(1)
    expect(snap.toolEvents.map((t) => t.id)).toEqual(['boss'])
    expect(snap.lastResumeMatchEvent).toEqual({ score: 1 })
  })
})

describe('derived selectors', () => {
  it('lastJobCards / lastToolEvents pick the most recent non-empty', () => {
    const messages = [
      { jobCards: [{ id: 'old' }], toolEvents: [{ id: 'old-t' }] },
      { jobCards: [{ id: 'new' }], toolEvents: [] },
    ]
    expect(lastJobCards(messages)).toEqual([{ id: 'new' }])
    expect(lastToolEvents(messages)).toEqual([{ id: 'old-t' }])
  })
  it('lastResumeMatch reads the latest resumeMatch row', () => {
    expect(lastResumeMatch([{ resumeMatch: { a: 1 } }, { resumeMatch: { a: 2 } }])).toEqual({ a: 2 })
    expect(lastResumeMatch([{}])).toBeNull()
  })
})
