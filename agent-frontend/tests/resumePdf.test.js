import { describe, it, expect, vi } from 'vitest'
import { photoResizeDelta, addPdfLinks, pdfViewerFitWidthUrl } from '../src/utils/resumePdf'

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
