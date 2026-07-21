// Pure DOM/geometry helpers for the resume PDF export path. Extracted from ResumeWriter.vue so the
// layout math (link hot-zones, pinned photo frames, resize deltas) can be unit-tested without a
// full component mount, and the component keeps only the html2canvas/jsPDF orchestration.

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
