// Pure snapshot/normalization transforms for the chat store. Extracted so the store keeps only
// state orchestration and effects, while these data-shaping rules stay side-effect free and unit
// testable. None of these functions touch the store instance or perform I/O.

import { filterVisibleToolEvents } from './chatHelpers'

const clone = (value) => JSON.parse(JSON.stringify(value))

function normalizeTurnId(row) {
  const raw = row?.turnId ?? row?.turn_id ?? row?.metadata?.turnId ?? row?.metadata?.turn_id
  return typeof raw === 'string' ? raw.trim() : ''
}

function normalizeSessionRow(sessionId, row, idx, turnId) {
  return {
    id: turnId ? (row.role === 'user' ? turnId : `${turnId}_${row.role || 'message'}`) : `${sessionId}_${idx}`,
    ...(turnId ? { turnId } : {}),
    role: row.role,
    content: row.content,
    reasoning: typeof row.reasoning === 'string' ? row.reasoning : '',
    jobCards: Array.isArray(row.jobCards) ? row.jobCards : [],
    toolEvents: filterVisibleToolEvents(Array.isArray(row.toolEvents) ? row.toolEvents : []),
  }
}

function mergeDuplicateRow(target, incoming) {
  if (!String(target.content || '').trim() && String(incoming.content || '').trim()) target.content = incoming.content
  if (!String(target.reasoning || '').trim() && String(incoming.reasoning || '').trim())
    target.reasoning = incoming.reasoning
  if (!target.jobCards?.length && incoming.jobCards?.length) target.jobCards = incoming.jobCards
  if (!target.toolEvents?.length && incoming.toolEvents?.length) target.toolEvents = incoming.toolEvents
}

export function normalizeSessionRows(sessionId, rows = []) {
  if (!rows.length) return []
  const normalized = []
  const turnRows = new Map()
  let lastUnansweredUser = null
  for (const [idx, row] of rows.entries()) {
    const turnId = normalizeTurnId(row)
    const item = normalizeSessionRow(sessionId, row, idx, turnId)
    if (
      item.role === 'user' &&
      lastUnansweredUser &&
      lastUnansweredUser.role === 'user' &&
      !lastUnansweredUser.turnId &&
      !item.turnId &&
      String(lastUnansweredUser.content || '') === String(item.content || '')
    ) {
      // 仅兼容没有 turnId 的历史重复数据。非空 turnId 是用户动作的权威身份，即使文本相同且
      // 连续出现也必须分别保留，避免把跨标签或用户主动重复提交误判为同一轮。
      mergeDuplicateRow(lastUnansweredUser, item)
      continue
    }
    if (turnId) {
      // 同一 turn 的重复持久化行按角色合并；user 与 assistant 即使共享 turnId 也不能互相吞并。
      const turnKey = `${item.role || ''}:${turnId}`
      const existing = turnRows.get(turnKey)
      if (existing) {
        mergeDuplicateRow(existing, item)
        continue
      }
      turnRows.set(turnKey, item)
    }
    normalized.push(item)
    if (item.role === 'assistant') lastUnansweredUser = null
    else if (item.role === 'user') lastUnansweredUser = item
  }
  return normalized
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

function isCompatibleServerPrefix(serverMessages, previousMessages) {
  if (!serverMessages.length || serverMessages.length >= previousMessages.length) return false
  return serverMessages.every((item, idx) => {
    const previous = previousMessages[idx]
    if (!previous || item.role !== previous.role) return false
    const serverContent = String(item.content || '').trim()
    const previousContent = String(previous.content || '').trim()
    return !serverContent || !previousContent || serverContent === previousContent
  })
}

export function buildSnapshotFromRows(sessionId, rows = [], previous = null) {
  const previousMessages = previous?.messages || []
  const serverMessages = normalizeSessionRows(sessionId, rows)
  // 历史接口可能与异步落库竞争：空结果不能抹掉已经展示过的会话；服务端仅返回本地消息前缀时，
  // 以服务端字段校正前缀并保留尚未落库的本地尾部。待后续完整响应到达后会自然替换为服务端记录。
  if (!serverMessages.length && previousMessages.length) return clone(previous)
  const messages = backfillFromPrevious(serverMessages, previousMessages)
  if (isCompatibleServerPrefix(messages, previousMessages)) {
    messages.push(...clone(previousMessages.slice(messages.length)))
  }
  const lastWithJobs = [...messages].reverse().find((item) => item.jobCards?.length)
  const lastMatch =
    [...rows].reverse().find((item) => item.resumeMatch)?.resumeMatch || previous?.lastResumeMatchEvent || null
  const lastWithTools = [...messages].reverse().find((item) => item.toolEvents?.length)
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
  return [...messages].reverse().find((item) => item.jobCards?.length)?.jobCards || []
}

export function lastToolEvents(messages = []) {
  return [...messages].reverse().find((item) => item.toolEvents?.length)?.toolEvents || []
}

export function lastResumeMatch(rows = []) {
  return [...rows].reverse().find((item) => item.resumeMatch)?.resumeMatch || null
}
