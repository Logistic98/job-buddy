import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../src/api/interview', () => ({
  getQuestionMeta: vi.fn(async () => ({})),
}))

import { getQuestionMeta } from '../src/api/interview'
import { useQuestionMeta } from '../src/composables/useQuestionMeta'

describe('useQuestionMeta', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('provides static fallbacks before meta is loaded', () => {
    const meta = useQuestionMeta()
    expect(meta.bankTypeOptions.value.map(item => item.value)).toEqual(['leetcode', 'baguwen'])
    expect(meta.difficulties.value).toEqual(['简单', '中等', '困难'])
    expect(meta.questionTypes.value).toContain('编程题')
    expect(meta.categories.value).toEqual([])
  })

  it('loads meta from the API and prefers server values', async () => {
    getQuestionMeta.mockResolvedValue({
      bankTypeOptions: [{ value: 'leetcode', label: 'raw' }],
      categories: ['数据库', '并发'],
      difficulties: ['简单'],
      questionTypes: ['单选'],
    })
    const meta = useQuestionMeta()
    await meta.loadQuestionMeta('leetcode')
    expect(meta.categories.value).toEqual(['并发', '数据库'])
    expect(meta.difficulties.value).toEqual(['简单'])
    expect(meta.questionTypes.value).toEqual(['单选'])
    expect(meta.bankTypeOptions.value[0].label).toBe('算法题库')
  })

  it('keeps fallbacks when the meta API fails', async () => {
    getQuestionMeta.mockRejectedValue(new Error('boom'))
    const meta = useQuestionMeta()
    await meta.loadQuestionMeta('baguwen')
    expect(meta.difficulties.value).toEqual(['简单', '中等', '困难'])
  })

  it('derives categories from the fallback source when server list is empty', async () => {
    getQuestionMeta.mockResolvedValue({ categories: [] })
    const meta = useQuestionMeta(() => ['网络', '算法'])
    await meta.loadQuestionMeta('leetcode')
    expect(meta.categories.value).toEqual(['算法', '网络'])
  })

  it('bankTypeLabel resolves display names with graceful fallback', () => {
    const meta = useQuestionMeta()
    expect(meta.bankTypeLabel('leetcode')).toBe('算法题库')
    expect(meta.bankTypeLabel('baguwen')).toBe('问答题库')
    expect(meta.bankTypeLabel('unknown-type')).toBe('unknown-type')
    expect(meta.bankTypeLabel('')).toBe('题库')
  })
})
