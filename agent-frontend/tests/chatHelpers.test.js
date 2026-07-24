import { describe, it, expect } from 'vitest'
import {
  normalizeMessageText,
  requestKey,
  isAbortError,
  formatSendError,
  isBossAuthenticated,
  activeToolSummary,
  normalizeToolEvent,
  isMemoryNoiseEvent,
  filterVisibleToolEvents,
  normalizeAssistantMarkdown,
  selectReasoningHighlights,
  selectToolEventHighlights,
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
  it('extracts nested structured errors without rendering object text', () => {
    expect(formatSendError({ error: { message: '工具 resume_match 执行超时（125 秒）' } })).toBe(
      '工具 resume_match 执行超时（125 秒）',
    )
    expect(formatSendError({ code: 'RUNTIME_TIMEOUT' })).toBe('请求处理失败（错误码：RUNTIME_TIMEOUT）')
  })
  it('defaults when empty', () => {
    expect(formatSendError(null)).toBe('请求失败，请稍后重试。')
    expect(formatSendError({})).toBe('请求失败，请稍后重试。')
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

describe('activeToolSummary', () => {
  it('keeps the runtime detail free of duplicated elapsed time', () => {
    expect(activeToolSummary({ detail: '已收到请求，正在理解你的问题并准备作答。' })).toBe(
      '已收到请求，正在理解你的问题并准备作答。',
    )
    expect(activeToolSummary()).toBe('请求已提交，正在初始化会话和服务链路，请稍候。')
  })
})

describe('assistant presentation helpers', () => {
  it('repairs historical job search events that used the qualified count as the display count', () => {
    const event = normalizeToolEvent({
      id: 'job_search',
      title: '岗位搜索完成',
      status: 'success',
      summary: '找到 8 个符合画像和简历的岗位。',
      detail: { count: 8, candidateCount: 23, qualifiedCount: 8 },
    })

    expect(event.detail).toBe('累计检索到 23 个候选岗位。')
    expect(selectToolEventHighlights(event)).toContainEqual({ label: '候选岗位', value: '23 个' })
  })

  it('normalizes repeated prose punctuation and linkifies bare URLs', () => {
    const output = normalizeAssistantMarkdown('实践经验。。；主要差距，，。详情：https://example.com/jobs。')
    expect(output).toBe('实践经验；主要差距。详情：[https://example.com/jobs](https://example.com/jobs)。')
  })

  it('keeps punctuation inside fenced code, inline code and markdown links unchanged', () => {
    const input = '正文。。\n```text\n原样。。\n```\n`内联。。` [说明。。](https://example.com/a..b)'
    const output = normalizeAssistantMarkdown(input)
    expect(output).toContain('正文。')
    expect(output).toContain('原样。。')
    expect(output).toContain('`内联。。`')
    expect(output).toContain('[说明。。](https://example.com/a..b)')
  })

  it('selects readable resume match details without exposing raw payload', () => {
    const highlights = selectToolEventHighlights({
      id: 'resume_match',
      payload: {
        count: 1,
        top: {
          score: 86,
          score_confidence: 'high',
          recommendation: '推荐',
          hits: ['具备 Java 与 Agent 工程经验'],
          gaps: ['缺少证券行业背景'],
          rawResponse: 'should-not-render',
        },
      },
    })
    expect(highlights).toEqual([
      { label: '匹配评分', value: '86/100' },
      { label: '投递建议', value: '推荐' },
      { label: '置信度', value: '高' },
      { label: '关键依据', value: '具备 Java 与 Agent 工程经验' },
    ])
    expect(JSON.stringify(highlights)).not.toContain('rawResponse')
  })

  it('summarizes the strict recommendation quality funnel', () => {
    const highlights = selectToolEventHighlights({
      id: 'recommendation_quality_gate',
      payload: {
        candidateCount: 10,
        qualifiedCount: 3,
        minimumScore: 70,
        rejectionReasons: { 未达到最低匹配分: 4, 匹配置信度低: 2, 投递建议为不建议: 1 },
      },
    })
    expect(highlights).toEqual([
      { label: '候选岗位', value: '10 个' },
      { label: '通过门槛', value: '3 个' },
      { label: '最低匹配分', value: '70 分' },
      { label: '主要剔除原因', value: '未达到最低匹配分 4 个；匹配置信度低 2 个；投递建议为不建议 1 个' },
    ])
  })

  it('selects high-signal reasoning sentences with a bounded count', () => {
    const highlights = selectReasoningHighlights(
      '先读取上下文。目标是判断当前简历与岗位的匹配度。。依据是 Java、RAG 和 Agent 项目经验。普通补充说明。主要风险是缺少证券行业背景。下一步建议补强金融场景案例。',
      3,
    )
    expect(highlights).toHaveLength(3)
    expect(highlights.join('')).not.toContain('。。')
    expect(highlights.some((item) => item.includes('目标'))).toBe(true)
    expect(highlights.some((item) => item.includes('风险'))).toBe(true)
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
    const events = [{ id: 'sse_connect' }, { id: 'memory_search' }, { id: 'boss_browser', title: 'Boss' }]
    const visible = filterVisibleToolEvents(events)
    expect(visible).toHaveLength(1)
    expect(visible[0].name).toBe('Boss')
  })
  it('filterVisibleToolEvents tolerates non-array input', () => {
    expect(filterVisibleToolEvents(null)).toEqual([])
  })
})
