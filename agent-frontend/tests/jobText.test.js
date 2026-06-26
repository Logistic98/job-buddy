import { describe, expect, it } from 'vitest'
import { compactJobSummaryText, firstJobDescriptionText, normalizeJobDescriptionText } from '../src/utils/jobText'

describe('jobText utilities', () => {
  it('removes blank lines while preserving meaningful JD line breaks', () => {
    const raw = '岗位职责：\n\n\n  1. 负责后端开发  \n\t\n2. 参与系统设计\r\n\r\n 任职要求：  '
    expect(normalizeJobDescriptionText(raw)).toBe('岗位职责：\n1. 负责后端开发\n2. 参与系统设计\n任职要求：')
  })

  it('compacts JD summaries for card previews', () => {
    expect(compactJobSummaryText('A\n\nB\nC', 4)).toBe('A B')
  })

  it('reads description fields by priority', () => {
    expect(firstJobDescriptionText({ description: 'desc', jobSecText: 'sec' })).toBe('desc')
    expect(firstJobDescriptionText({ jobSecText: 'sec' })).toBe('sec')
  })
})
