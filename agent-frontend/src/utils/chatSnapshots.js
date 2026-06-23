// Pure snapshot/normalization transforms for the chat store. Extracted so the store keeps only
// state orchestration and effects, while these data-shaping rules stay side-effect free and unit
// testable. None of these functions touch the store instance or perform I/O.

import { filterVisibleToolEvents } from './chatHelpers'

const clone = value => JSON.parse(JSON.stringify(value))

export function normalizeSessionRows(sessionId, rows = []) {
  return rows.length
    ? rows.map((m, idx) => ({
      id: `${sessionId}_${idx}`,
      role: m.role,
      content: m.content,
      reasoning: typeof m.reasoning === 'string' ? m.reasoning : '',
      jobCards: Array.isArray(m.jobCards) ? m.jobCards : [],
      toolEvents: filterVisibleToolEvents(Array.isArray(m.toolEvents) ? m.toolEvents : []),
    }))
    : []
}

// 服务端行缺过程字段时（落库未完成或历史数据 metadata 为空），按同位置同角色用既有快照兜底，
// 避免强制重载覆盖快照后，会话切换回来推理过程/工具事件消失。
export function backfillFromPrevious(messages, previousMessages = []) {
  if (!previousMessages.length) return messages
  messages.forEach((item, idx) => {
    const old = previousMessages[idx]
    if (!old || old.role !== item.role) return
    if (!String(item.reasoning || '').trim() && String(old.reasoning || '').trim()) item.reasoning = old.reasoning
    if (!item.toolEvents?.length && old.toolEvents?.length) item.toolEvents = old.toolEvents
    if (!item.jobCards?.length && old.jobCards?.length) item.jobCards = old.jobCards
  })
  return messages
}

export function buildSnapshotFromRows(sessionId, rows = [], previous = null) {
  const messages = backfillFromPrevious(normalizeSessionRows(sessionId, rows), previous?.messages || [])
  const lastWithJobs = [...messages].reverse().find(item => item.jobCards?.length)
  const lastMatch = [...rows].reverse().find(item => item.resumeMatch)?.resumeMatch || previous?.lastResumeMatchEvent || null
  const lastWithTools = [...messages].reverse().find(item => item.toolEvents?.length)
  return {
    rows: clone(rows || []),
    messages: clone(messages || []),
    toolEvents: clone(lastWithTools?.toolEvents || []),
    lastJobCardsEvent: clone(lastWithJobs?.jobCards || []),
    lastResumeMatchEvent: lastMatch ? clone(lastMatch) : null,
    lastPersonalContextEvent: previous?.lastPersonalContextEvent || null,
  }
}

export function buildSnapshotFromMessages(state = {}) {
  return {
    messages: clone(state.messages || []),
    toolEvents: clone(filterVisibleToolEvents(state.toolEvents || [])),
    lastJobCardsEvent: clone(state.lastJobCardsEvent || []),
    lastResumeMatchEvent: state.lastResumeMatchEvent ? clone(state.lastResumeMatchEvent) : null,
    lastPersonalContextEvent: state.lastPersonalContextEvent ? clone(state.lastPersonalContextEvent) : null,
  }
}

export function lastJobCards(messages = []) {
  return [...messages].reverse().find(item => item.jobCards?.length)?.jobCards || []
}

export function lastToolEvents(messages = []) {
  return [...messages].reverse().find(item => item.toolEvents?.length)?.toolEvents || []
}

export function lastResumeMatch(rows = []) {
  return [...rows].reverse().find(item => item.resumeMatch)?.resumeMatch || null
}
