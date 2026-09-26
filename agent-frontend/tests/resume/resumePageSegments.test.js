import { expect, it } from 'vitest'
import { collectPageSegments, renderPageSegments, groupSegmentsIntoPages } from '../../src/utils/resumePagination'

it('splits multi-item lists while preserving geometry, ordering, and forced page breaks', () => {
  const root = document.createElement('div')
  root.innerHTML =
    '<h2 class="resume-page-break-before">Title</h2><div class="r-list r-list-ol"><div class="r-li">One</div><div class="r-li">Two</div></div><div class="r-list"><div class="r-li">Three</div><div class="r-li">Four</div></div><p>Last</p>'
  const nodes = [root.children[0], ...root.children[1].children, ...root.children[2].children, root.children[3]]
  nodes.forEach((node, index) => {
    node.getBoundingClientRect = () => ({ top: 100 + index * 20, bottom: 120 + index * 20 })
  })
  const segments = collectPageSegments(root, { top: 100 })
  expect(segments.map(({ top, bottom }) => [top, bottom])).toEqual([
    [0, 20],
    [20, 40],
    [40, 60],
    [60, 80],
    [80, 100],
    [100, 120],
  ])
  expect(segments[0].forced).toBe(true)
  expect(segments.slice(1, 5).map((item) => item.listTag)).toEqual(['ol', 'ol', 'ul', 'ul'])
  const rendered = document.createElement('div')
  rendered.innerHTML = renderPageSegments(segments)
  expect(rendered.querySelectorAll('.r-list')).toHaveLength(2)
  expect(rendered.querySelector('.r-list-ol').textContent).toBe('One\nTwo')
  expect(rendered.lastElementChild.textContent).toBe('Last')
  expect(groupSegmentsIntoPages([], 100)).toEqual([])
})

it('keeps one-item lists atomic and carries an orphan heading to the next page', () => {
  const root = document.createElement('div')
  root.innerHTML = '<div class="r-list"><div class="r-li">Only</div></div><h2>Heading</h2><p>Body</p>'
  Array.from(root.children).forEach((node, index) => {
    node.getBoundingClientRect = () => ({ top: index * 30, bottom: (index + 1) * 30 })
  })
  const segments = collectPageSegments(root, { top: 0 })
  expect(segments[0].type).toBe('node')
  expect(groupSegmentsIntoPages(segments, 60).map((page) => page.map((item) => item.node.textContent))).toEqual([
    ['Only'],
    ['Heading', 'Body'],
  ])
  segments[2].forced = true
  expect(groupSegmentsIntoPages(segments, 100).map((page) => page.map((item) => item.node.textContent))).toEqual([
    ['Only', 'Heading'],
    ['Body'],
  ])
})
