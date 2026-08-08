import { describe, it, expect, vi } from 'vitest'
import {
  addPdfLinks,
  addPdfTextLayer,
  mergePdfTextGlyphs,
  PDF_JPEG_QUALITY,
  PDF_RASTER_SCALE,
  PDF_TEXT_FONT_FAMILY,
  pdfTextHorizontalScale,
  pdfTextTopPosition,
  pdfViewerFitWidthUrl,
  photoResizeDelta,
  registerPdfTextFont,
} from '../src/utils/resumePdf'

describe('PDF visual quality', () => {
  it('keeps the page raster and JPEG quality above the readable export baseline', () => {
    expect(PDF_RASTER_SCALE).toBeGreaterThanOrEqual(2.4)
    expect(PDF_JPEG_QUALITY).toBeGreaterThanOrEqual(0.9)
  })
})

describe('PDF Unicode text layer', () => {
  it('merges adjacent glyphs into DOM-order text runs and preserves visible gaps', () => {
    const glyphs = [
      { text: '胡', left: 10, right: 20, top: 10, bottom: 22, height: 12, fontSizePt: 9 },
      { text: '军', left: 20, right: 30, top: 10, bottom: 22, height: 12, fontSizePt: 9 },
      { text: 'Java', left: 34, right: 55, top: 10, bottom: 22, height: 12, fontSizePt: 9 },
      { text: '项目', left: 10, right: 30, top: 30, bottom: 42, height: 12, fontSizePt: 9 },
    ]

    expect(mergePdfTextGlyphs(glyphs).map((item) => item.text)).toEqual(['胡军 Java', '项目'])
  })

  it('keeps distinct DOM text nodes as independently calibrated runs', () => {
    const glyphs = [
      { text: '项', left: 10, right: 20, top: 10, bottom: 22, height: 12, fontSizePt: 9, sourceId: 1 },
      { text: '目', left: 20, right: 30, top: 10, bottom: 22, height: 12, fontSizePt: 9, sourceId: 1 },
      { text: '经', left: 30, right: 40, top: 10, bottom: 22, height: 12, fontSizePt: 9, sourceId: 2 },
      { text: '验', left: 40, right: 50, top: 10, bottom: 22, height: 12, fontSizePt: 9, sourceId: 2 },
    ]

    expect(mergePdfTextGlyphs(glyphs).map((item) => item.text)).toEqual(['项目', '经验'])
  })

  it('writes positioned Chinese text with the embedded font and invisible rendering mode', () => {
    const pdf = { setFont: vi.fn(), setFontSize: vi.fn(), getTextWidth: vi.fn(() => 30), text: vi.fn() }
    addPdfTextLayer(pdf, [{ text: '胡军 Java', leftRatio: 0.1, topRatio: 0.2, widthRatio: 0.2, fontSizePt: 9 }])

    expect(pdf.setFont).toHaveBeenCalledWith(PDF_TEXT_FONT_FAMILY, 'normal')
    expect(pdf.setFontSize).toHaveBeenCalledWith(10.080000000000002)
    expect(pdf.getTextWidth).toHaveBeenCalledWith('胡军 Java')
    const [, x, y, options] = pdf.text.mock.calls[0]
    expect(x).toBe(21)
    expect(y).toBeCloseTo(62.2448)
    expect(options).toEqual({ renderingMode: 'invisible', baseline: 'top', horizontalScale: 1.4 })
  })

  it('fits text-layer runs to their measured DOM width without pathological scaling', () => {
    expect(pdfTextHorizontalScale(42, 30)).toBe(1.4)
    expect(pdfTextHorizontalScale(1, 30)).toBe(0.5)
    expect(pdfTextHorizontalScale(90, 30)).toBe(2)
    expect(pdfTextHorizontalScale(0, 30)).toBe(1)
  })

  it('moves the embedded-font selection box down to the visible browser-font baseline', () => {
    expect(pdfTextTopPosition(0.2, 10.08)).toBeCloseTo(62.2448)
    expect(pdfTextTopPosition(0.2, 13.44) - pdfTextTopPosition(0.2, 10.08)).toBeCloseTo(0.9482667)
  })

  it('registers the downloadable TrueType font in jsPDF', async () => {
    const pdf = { addFileToVFS: vi.fn(), addFont: vi.fn(), setFont: vi.fn() }
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      arrayBuffer: async () => Uint8Array.from([0, 1, 2, 3]).buffer,
    })

    await registerPdfTextFont(pdf, fetchImpl)

    expect(fetchImpl).toHaveBeenCalledWith('/fonts/job-buddy-resume-sans.ttf')
    expect(pdf.addFileToVFS).toHaveBeenCalledWith('JobBuddyResumeSans.ttf', 'AAECAw==')
    expect(pdf.addFont).toHaveBeenCalledWith('JobBuddyResumeSans.ttf', PDF_TEXT_FONT_FAMILY, 'normal')
    expect(pdf.setFont).toHaveBeenCalledWith(PDF_TEXT_FONT_FAMILY, 'normal')
  })
})

describe('pdfViewerFitWidthUrl', () => {
  it('preserves resource query parameters and appends fit-width options', () => {
    expect(pdfViewerFitWidthUrl('/api/resume/r1/preview?inline=true')).toBe(
      '/api/resume/r1/preview?inline=true#view=FitH&zoom=page-width&toolbar=0&navpanes=0&scrollbar=0',
    )
  })

  it('replaces an existing fragment and handles an empty URL', () => {
    expect(pdfViewerFitWidthUrl('/resume.pdf#page=2')).toBe(
      '/resume.pdf#view=FitH&zoom=page-width&toolbar=0&navpanes=0&scrollbar=0',
    )
    expect(pdfViewerFitWidthUrl('')).toBe('')
  })
})

describe('photoResizeDelta', () => {
  it('returns zero when handle has no direction', () => {
    expect(photoResizeDelta('', 90, 90)).toBe(0)
  })

  it('grows on the east edge proportional to dx / 90', () => {
    expect(photoResizeDelta('e', 90, 0)).toBeCloseTo(1)
  })

  it('shrinks on the west edge', () => {
    expect(photoResizeDelta('w', 90, 0)).toBeCloseTo(-1)
  })

  it('averages two axes for corner handles', () => {
    expect(photoResizeDelta('se', 90, 90)).toBeCloseTo(1)
  })
})

describe('addPdfLinks', () => {
  it('maps layout ratios into A4 millimetre link rectangles', () => {
    const pdf = { link: vi.fn() }
    addPdfLinks(pdf, [{ leftRatio: 0.5, topRatio: 0.5, widthRatio: 0.1, heightRatio: 0.1, href: 'https://x' }])
    const [x, y, w, h, opts] = pdf.link.mock.calls[0]
    expect(x).toBeCloseTo(105)
    expect(y).toBeCloseTo(148.5)
    expect(w).toBeCloseTo(21)
    expect(h).toBeCloseTo(29.7)
    expect(opts).toEqual({ url: 'https://x' })
  })

  it('does nothing for an empty layout list', () => {
    const pdf = { link: vi.fn() }
    addPdfLinks(pdf, [])
    expect(pdf.link).not.toHaveBeenCalled()
  })
})
