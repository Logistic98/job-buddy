import { describe, expect, it } from 'vitest'
import { journeyResultClass } from '../src/utils/journeyResult'

describe('journey result styles', () => {
  it.each([
    ['通过', 'journey-result-passed'],
    ['未通过', 'journey-result-failed'],
    ['待反馈', 'journey-result-pending'],
    ['跟进中', 'journey-result-progress'],
    ['已放弃', 'journey-result-abandoned'],
  ])('maps %s to %s', (result, expected) => {
    expect(journeyResultClass(result)).toBe(expected)
  })

  it.each([undefined, null, '', '待定', '未知结果'])('uses a neutral style for %s', (result) => {
    expect(journeyResultClass(result)).toBe('journey-result-neutral')
  })
})
