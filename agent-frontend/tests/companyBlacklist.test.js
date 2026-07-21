import { describe, expect, it } from 'vitest'
import {
  buildBlacklistScopes,
  createBlacklistItem,
  filterBlacklistItems,
  findBlacklistDuplicate,
  normalizeBlacklistName,
} from '../src/composables/useCompanyBlacklist'

const items = [
  { id: 'a', name: '东软集团', type: 'company', enabled: true, reason: '常见外包/驻场供应商' },
  { id: 'b', name: '中科软', type: 'company', enabled: false, reason: '常见外包/驻场供应商' },
  { id: 'c', name: '外包', type: 'keyword', enabled: true, reason: '手动关键词屏蔽' },
]

describe('normalizeBlacklistName', () => {
  it('trims, lowercases and removes inner whitespace', () => {
    expect(normalizeBlacklistName('  Dong Ruan ')).toBe('dongruan')
    expect(normalizeBlacklistName('东软　集团')).toBe('东软集团')
    expect(normalizeBlacklistName(null)).toBe('')
  })
})

describe('findBlacklistDuplicate', () => {
  it('matches same type ignoring case and whitespace', () => {
    expect(findBlacklistDuplicate(items, 'company', ' 东软 集团 ')?.id).toBe('a')
  })

  it('does not match across types', () => {
    expect(findBlacklistDuplicate(items, 'keyword', '东软集团')).toBeNull()
  })

  it('returns null for empty name', () => {
    expect(findBlacklistDuplicate(items, 'company', '  ')).toBeNull()
  })
})

describe('createBlacklistItem', () => {
  it('creates enabled manual item with trimmed name and type reason', () => {
    const item = createBlacklistItem('keyword', ' 驻场 ', 'blk_test')
    expect(item).toEqual({
      id: 'blk_test',
      name: '驻场',
      type: 'keyword',
      enabled: true,
      source: 'manual',
      reason: '手动关键词屏蔽',
    })
    expect(createBlacklistItem('company', 'x', 'id2').reason).toBe('手动公司屏蔽')
  })
})

describe('filterBlacklistItems', () => {
  it('returns all items by default', () => {
    expect(filterBlacklistItems(items, { keyword: '', scope: 'all' })).toHaveLength(3)
  })

  it('filters by type scope', () => {
    expect(filterBlacklistItems(items, { scope: 'company' }).map((i) => i.id)).toEqual(['a', 'b'])
    expect(filterBlacklistItems(items, { scope: 'keyword' }).map((i) => i.id)).toEqual(['c'])
  })

  it('filters disabled scope', () => {
    expect(filterBlacklistItems(items, { scope: 'disabled' }).map((i) => i.id)).toEqual(['b'])
  })

  it('searches name and reason case-insensitively', () => {
    expect(filterBlacklistItems(items, { keyword: '中科', scope: 'all' }).map((i) => i.id)).toEqual(['b'])
    expect(filterBlacklistItems(items, { keyword: '驻场', scope: 'all' }).map((i) => i.id)).toEqual(['a', 'b'])
  })

  it('handles missing input safely', () => {
    expect(filterBlacklistItems(null, null)).toEqual([])
  })
})

describe('buildBlacklistScopes', () => {
  it('counts totals per scope', () => {
    const scopes = buildBlacklistScopes(items)
    expect(scopes).toEqual([
      { key: 'all', label: '全部', count: 3 },
      { key: 'company', label: '公司', count: 2 },
      { key: 'keyword', label: '关键词', count: 1 },
      { key: 'disabled', label: '已停用', count: 1 },
    ])
  })
})
