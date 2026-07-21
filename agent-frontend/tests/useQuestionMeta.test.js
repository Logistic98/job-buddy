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

  it('starts empty before the backend metadata is loaded', () => {
    const meta = useQuestionMeta()
    expect(meta.bankTypeOptions.value).toEqual([])
    expect(meta.difficulties.value).toEqual([])
    expect(meta.questionTypes.value).toEqual([])
    expect(meta.categories.value).toEqual([])
  })

  it('loads the complete metadata contract from the API', async () => {
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

  it('propagates metadata API failures', async () => {
    getQuestionMeta.mockRejectedValue(new Error('boom'))
    const meta = useQuestionMeta()
    await expect(meta.loadQuestionMeta('qa')).rejects.toThrow('boom')
    expect(meta.difficulties.value).toEqual([])
  })

  it('keeps server-provided empty collections empty', async () => {
    getQuestionMeta.mockResolvedValue({
      bankTypeOptions: [],
      categories: [],
      difficulties: [],
      questionTypes: [],
    })
    const meta = useQuestionMeta()
    await meta.loadQuestionMeta('leetcode')
    expect(meta.categories.value).toEqual([])
  })

  it('bankTypeLabel resolves display names for known values', () => {
    const meta = useQuestionMeta()
    expect(meta.bankTypeLabel('leetcode')).toBe('算法题库')
    expect(meta.bankTypeLabel('qa')).toBe('问答题库')
    expect(meta.bankTypeLabel('unknown-type')).toBe('unknown-type')
    expect(meta.bankTypeLabel('')).toBe('题库')
  })
})
