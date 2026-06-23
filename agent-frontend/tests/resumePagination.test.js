import { describe, it, expect } from 'vitest'
import { isHeadingSegment, groupSegmentsIntoPages } from '../src/utils/resumePagination'

const node = (tag, top, bottom, forced = false) => ({ type: 'node', node: { tagName: tag }, top, bottom, forced })

describe('isHeadingSegment', () => {
  it('matches H2-H4 node segments only', () => {
    expect(isHeadingSegment(node('H2', 0, 10))).toBe(true)
    expect(isHeadingSegment(node('H4', 0, 10))).toBe(true)
    expect(isHeadingSegment(node('P', 0, 10))).toBe(false)
    expect(isHeadingSegment({ type: 'listItem' })).toBe(false)
    expect(isHeadingSegment(null)).toBe(false)
  })
})

describe('groupSegmentsIntoPages', () => {
  it('returns empty for no blocks', () => {
    expect(groupSegmentsIntoPages([], 100)).toEqual([])
  })

  it('keeps blocks within usable height on one page', () => {
    const blocks = [node('P', 0, 20), node('P', 20, 40), node('P', 40, 60)]
    const groups = groupSegmentsIntoPages(blocks, 100)
    expect(groups).toHaveLength(1)
    expect(groups[0]).toHaveLength(3)
  })

  it('breaks to a new page when usable height is exceeded', () => {
    const blocks = [node('P', 0, 60), node('P', 60, 120)]
    const groups = groupSegmentsIntoPages(blocks, 100)
    expect(groups).toHaveLength(2)
  })

  it('respects a forced page break', () => {
    const blocks = [node('P', 0, 20), node('P', 20, 40, true)]
    const groups = groupSegmentsIntoPages(blocks, 1000)
    expect(groups).toHaveLength(2)
  })

  it('carries a trailing heading to the next page instead of orphaning it', () => {
    const blocks = [node('P', 0, 50), node('H3', 50, 70), node('P', 70, 140)]
    const groups = groupSegmentsIntoPages(blocks, 100)
    expect(groups).toHaveLength(2)
    expect(groups[1][0].node.tagName).toBe('H3')
    expect(groups[1]).toHaveLength(2)
  })
})
