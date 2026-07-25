// 将测量后的简历内容划分为打印页片段，分页计算可脱离组件独立测试。

export function isHeadingSegment(segment) {
  return segment?.type === 'node' && /^H[2-4]$/.test(segment.node.tagName)
}

export function collectPageSegments(el, rootRect) {
  const segments = []
  Array.from(el.children).forEach((node) => {
    const isList = node.classList?.contains('r-list')
    const listItems = isList ? Array.from(node.children).filter((child) => child.classList?.contains('r-li')) : []
    if (isList && listItems.length > 1) {
      const listTag = node.classList.contains('r-list-ol') ? 'ol' : 'ul'
      listItems.forEach((item) => {
        const rect = item.getBoundingClientRect()
        segments.push({
          type: 'listItem',
          listTag,
          node: item,
          top: rect.top - rootRect.top,
          bottom: rect.bottom - rootRect.top,
          forced: false,
        })
      })
      return
    }
    const rect = node.getBoundingClientRect()
    segments.push({
      type: 'node',
      node,
      top: rect.top - rootRect.top,
      bottom: rect.bottom - rootRect.top,
      forced: node.classList?.contains('resume-page-break-before'),
    })
  })
  return segments
}

export function renderPageSegments(group) {
  const htmlParts = []
  let pendingList = null
  const flushList = () => {
    if (!pendingList) return
    htmlParts.push(`<div class="r-list r-list-${pendingList.tag}">${pendingList.items.join('\n')}</div>`)
    pendingList = null
  }
  group.forEach((segment) => {
    if (segment.type === 'listItem') {
      if (!pendingList || pendingList.tag !== segment.listTag) {
        flushList()
        pendingList = { tag: segment.listTag, items: [] }
      }
      pendingList.items.push(segment.node.outerHTML)
      return
    }
    flushList()
    htmlParts.push(segment.node.outerHTML)
  })
  flushList()
  return htmlParts.join('\n')
}

// 按可用高度分组有序片段，并将页尾标题与后续内容绑定，避免标题单独占据页尾。
export function groupSegmentsIntoPages(blocks, usable) {
  if (!blocks.length) return []
  const groups = []
  let current = []
  let startTop = 0
  for (const block of blocks) {
    if (!current.length) {
      current = [block]
      startTop = block.top
      continue
    }
    if (block.forced || block.bottom - startTop > usable) {
      const last = current[current.length - 1]
      let carry = null
      // 普通分页时将页尾孤立标题带到下一页；强制分页保持原始边界。
      if (!block.forced && current.length > 1 && isHeadingSegment(last)) {
        current.pop()
        carry = last
      }
      if (current.length) groups.push(current)
      current = carry ? [carry, block] : [block]
      startTop = carry ? carry.top : block.top
    } else {
      current.push(block)
    }
  }
  if (current.length) groups.push(current)
  return groups
}
