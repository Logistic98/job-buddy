// 简历 PDF 导出的 DOM 与几何辅助函数，使 Unicode 文本层、链接热区和照片定位可独立测试。

export const PDF_TEXT_FONT_FAMILY = 'JobBuddyResumeSans'
export const PDF_TEXT_FONT_URL = '/fonts/job-buddy-resume-sans.ttf'
// 约 230 DPI 的页面栅格能在常见缩放与打印场景保持文字边缘清晰；压缩只用于去除
// JPEG 的冗余数据，不以明显降低简历可读性换取更小体积。
export const PDF_RASTER_SCALE = 2.4
export const PDF_JPEG_QUALITY = 0.92
const PDF_PAGE_WIDTH_MM = 210
const PDF_PAGE_HEIGHT_MM = 297
const PDF_TEXT_SCALE_MIN = 0.5
const PDF_TEXT_SCALE_MAX = 2
const PDF_POINTS_TO_MM = 25.4 / 72
// 浏览器页面字体与嵌入的 Noto 子集字体具有不同的 ascent。PDF 选区使用嵌入字体的
// 字形框，因此需要略微放大并按字号下移，才能与栅格图层中的实际字形上下边界重合。
const PDF_TEXT_FONT_SIZE_SCALE = 1.12
const PDF_TEXT_VERTICAL_OFFSET_EM = 0.8
let cachedPdfTextFontBase64 = ''

function bytesToBase64(bytes) {
  let binary = ''
  const chunkSize = 8192
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return globalThis.btoa(binary)
}

export async function registerPdfTextFont(pdf, fetchImpl = globalThis.fetch) {
  if (!cachedPdfTextFontBase64) {
    const response = await fetchImpl(PDF_TEXT_FONT_URL)
    if (!response?.ok) throw new Error(`PDF 文本字体加载失败（${response?.status || '未知状态'}）`)
    cachedPdfTextFontBase64 = bytesToBase64(new Uint8Array(await response.arrayBuffer()))
  }
  pdf.addFileToVFS('JobBuddyResumeSans.ttf', cachedPdfTextFontBase64)
  pdf.addFont('JobBuddyResumeSans.ttf', PDF_TEXT_FONT_FAMILY, 'normal')
  pdf.setFont(PDF_TEXT_FONT_FAMILY, 'normal')
}

export function mergePdfTextGlyphs(glyphs) {
  const runs = []
  glyphs.forEach((glyph) => {
    const previous = runs.at(-1)
    const sameLine = previous && Math.abs(previous.top - glyph.top) <= 1.5
    const sameSize = previous && Math.abs(previous.fontSizePt - glyph.fontSizePt) <= 0.2
    const sameSource = previous && previous.sourceId === glyph.sourceId
    const nearby = previous && glyph.left - previous.right <= Math.max(4, glyph.height * 0.8)
    if (sameLine && sameSize && sameSource && nearby) {
      const gap = glyph.left - previous.right
      const needsSpace = gap > glyph.height * 0.18 && !/\s$/.test(previous.text) && !/^\s/.test(glyph.text)
      previous.text += `${needsSpace ? ' ' : ''}${glyph.text}`
      previous.right = Math.max(previous.right, glyph.right)
      previous.bottom = Math.max(previous.bottom, glyph.bottom)
      return
    }
    runs.push({ ...glyph })
  })
  return runs
}

export function getPdfTextLayouts(pageEls) {
  return pageEls.map((pageEl) => {
    const pageRect = pageEl.getBoundingClientRect()
    const displayScale = pageEl.offsetWidth ? pageRect.width / pageEl.offsetWidth : 1
    const glyphs = []
    const nodeFilter = pageEl.ownerDocument.defaultView.NodeFilter
    const walker = pageEl.ownerDocument.createTreeWalker(pageEl, nodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = node.parentElement
        if (!String(node.nodeValue || '').trim()) return nodeFilter.FILTER_REJECT
        // 列表符号保留在视觉图层中，但不重复写入文本层，避免 ATS 将符号拆成独立段落。
        if (!parent || parent.closest('svg, script, style, .r-li-marker, [aria-hidden="true"]')) {
          return nodeFilter.FILTER_REJECT
        }
        return nodeFilter.FILTER_ACCEPT
      },
    })
    let node = walker.nextNode()
    let sourceId = 0
    while (node) {
      sourceId += 1
      const style = pageEl.ownerDocument.defaultView.getComputedStyle(node.parentElement)
      const fontSizePt = Number.parseFloat(style.fontSize || '12') * displayScale * 0.75
      const value = String(node.nodeValue || '')
      for (let index = 0; index < value.length; index++) {
        if (/[\r\n]/.test(value[index])) continue
        const range = pageEl.ownerDocument.createRange()
        range.setStart(node, index)
        range.setEnd(node, index + 1)
        const rect = Array.from(range.getClientRects()).find((item) => item.width > 0 && item.height > 0)
        if (!rect) continue
        glyphs.push({
          text: value[index] === '\u00a0' ? ' ' : value[index],
          left: rect.left - pageRect.left,
          right: rect.right - pageRect.left,
          top: rect.top - pageRect.top,
          bottom: rect.bottom - pageRect.top,
          height: rect.height,
          fontSizePt,
          sourceId,
        })
      }
      node = walker.nextNode()
    }
    return mergePdfTextGlyphs(glyphs).map((run) => ({
      text: run.text,
      leftRatio: run.left / pageRect.width,
      topRatio: run.top / pageRect.height,
      widthRatio: (run.right - run.left) / pageRect.width,
      fontSizePt: run.fontSizePt,
    }))
  })
}

export function pdfTextHorizontalScale(targetWidth, naturalWidth) {
  if (!(targetWidth > 0) || !(naturalWidth > 0)) return 1
  return Math.min(PDF_TEXT_SCALE_MAX, Math.max(PDF_TEXT_SCALE_MIN, targetWidth / naturalWidth))
}

export function pdfTextTopPosition(topRatio, fontSizePt) {
  return topRatio * PDF_PAGE_HEIGHT_MM + fontSizePt * PDF_TEXT_VERTICAL_OFFSET_EM * PDF_POINTS_TO_MM
}

export function addPdfTextLayer(pdf, layouts) {
  pdf.setFont(PDF_TEXT_FONT_FAMILY, 'normal')
  layouts.forEach((item) => {
    if (!item.text) return
    const selectionFontSizePt = Math.max(1, item.fontSizePt * PDF_TEXT_FONT_SIZE_SCALE)
    pdf.setFontSize(selectionFontSizePt)
    const targetWidth = item.widthRatio * PDF_PAGE_WIDTH_MM
    const horizontalScale = pdfTextHorizontalScale(targetWidth, pdf.getTextWidth(item.text))
    const top = pdfTextTopPosition(item.topRatio, selectionFontSizePt)
    pdf.text(item.text, item.leftRatio * PDF_PAGE_WIDTH_MM, top, {
      renderingMode: 'invisible',
      baseline: 'top',
      horizontalScale,
    })
  })
}

export function pdfViewerFitWidthUrl(value) {
  const url = String(value || '').trim()
  if (!url) return ''
  const base = url.split('#', 1)[0]
  return `${base}#view=FitH&zoom=page-width&toolbar=0&navpanes=0&scrollbar=0`
}

export function photoResizeDelta(handle, dx, dy) {
  let delta = 0
  let count = 0
  if (handle.includes('e')) {
    delta += dx
    count += 1
  }
  if (handle.includes('w')) {
    delta -= dx
    count += 1
  }
  if (handle.includes('s')) {
    delta += dy
    count += 1
  }
  if (handle.includes('n')) {
    delta -= dy
    count += 1
  }
  return count ? delta / count / 90 : 0
}

export function getPdfLinkLayouts(pageEls) {
  return pageEls.map((pageEl) => {
    const pageRect = pageEl.getBoundingClientRect()
    return Array.from(pageEl.querySelectorAll('a[href]'))
      .map((link) => {
        const rect = link.getBoundingClientRect()
        return {
          href: link.getAttribute('href') || '',
          leftRatio: (rect.left - pageRect.left) / pageRect.width,
          topRatio: (rect.top - pageRect.top) / pageRect.height,
          widthRatio: rect.width / pageRect.width,
          heightRatio: rect.height / pageRect.height,
        }
      })
      .filter((item) => item.href)
  })
}

export function addPdfLinks(pdf, layouts) {
  layouts.forEach((item) => {
    pdf.link(item.leftRatio * 210, item.topRatio * 297, item.widthRatio * 210, item.heightRatio * 297, {
      url: item.href,
    })
  })
}

export function getPdfPhotoLayouts(pageEls) {
  return pageEls.map((pageEl) => {
    const pageRect = pageEl.getBoundingClientRect()
    const ratio = pageRect.width ? pageEl.offsetWidth / pageRect.width : 1
    return Array.from(pageEl.querySelectorAll('[data-managed-resume-photo="true"]')).map((frame) => {
      const rect = frame.getBoundingClientRect()
      const img = frame.querySelector('img.resume-photo')
      return {
        left: (rect.left - pageRect.left) * ratio,
        top: (rect.top - pageRect.top) * ratio,
        width: rect.width * ratio,
        height: rect.height * ratio,
        src: img?.src || '',
      }
    })
  })
}

export function pinPdfPhotoFrames(clone, layouts) {
  const frames = Array.from(clone.querySelectorAll('[data-managed-resume-photo="true"]'))
  frames.forEach((frame, index) => {
    const layout = layouts[index]
    const img = frame.querySelector('img.resume-photo')
    if (!layout || !img) return
    frame.style.visibility = 'hidden'
    const overlay = document.createElement('img')
    overlay.className = 'resume-photo resume-photo-pdf-overlay'
    overlay.alt = img.alt || '证件照'
    overlay.src = layout.src || img.src
    overlay.setAttribute('draggable', 'false')
    overlay.style.position = 'absolute'
    overlay.style.left = `${layout.left}px`
    overlay.style.top = `${layout.top}px`
    overlay.style.width = `${layout.width}px`
    overlay.style.height = `${layout.height}px`
    overlay.style.objectFit = 'cover'
    overlay.style.borderRadius = '2px'
    overlay.style.zIndex = '2'
    clone.appendChild(overlay)
  })
}
