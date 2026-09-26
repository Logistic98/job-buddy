import { afterEach, expect, it, vi } from 'vitest'
import {
  getPdfTextLayouts,
  getPdfLinkLayouts,
  addPdfLinks,
  getPdfPhotoLayouts,
  pinPdfPhotoFrames,
  registerPdfTextFont,
} from '../../src/utils/resumePdf'

afterEach(() => vi.restoreAllMocks())

it('converts visible glyph rectangles to page relative text while filtering hidden content', () => {
  const page = document.createElement('div')
  page.innerHTML =
    '<p style="font-size:16px">AB\nC&nbsp;D</p><span aria-hidden="true">hidden</span><script>ignored</script>   '
  page.getBoundingClientRect = () => ({ left: 10, top: 20, width: 200, height: 300 })
  Object.defineProperty(page, 'offsetWidth', { value: 100 })
  vi.spyOn(document, 'createRange').mockImplementation(() => {
    let offset
    return {
      setStart: (_node, value) => {
        offset = value
      },
      setEnd: () => {},
      getClientRects: () =>
        offset === 5
          ? []
          : [{ left: 10 + offset * 10, right: 20 + offset * 10, top: 20, bottom: 40, width: 10, height: 20 }],
    }
  })
  const [layout] = getPdfTextLayouts([page])
  expect(layout).toHaveLength(1)
  expect(layout[0]).toEqual({ text: 'AB C ', leftRatio: 0, topRatio: 0, widthRatio: 0.25, fontSizePt: 24 })
})

it('converts links to PDF millimeters and ignores empty anchors', () => {
  const page = document.createElement('div')
  page.innerHTML = '<a href="https://example.test">Profile</a><a href="">Empty</a>'
  page.getBoundingClientRect = () => ({ left: 10, top: 20, width: 200, height: 300 })
  for (const link of page.children) link.getBoundingClientRect = () => ({ left: 30, top: 50, width: 100, height: 30 })
  const [layout] = getPdfLinkLayouts([page])
  expect(layout).toEqual([
    { href: 'https://example.test', leftRatio: 0.1, topRatio: 0.1, widthRatio: 0.5, heightRatio: 0.1 },
  ])
  const pdf = { link: vi.fn() }
  addPdfLinks(pdf, layout)
  expect(pdf.link).toHaveBeenCalledWith(21, 29.700000000000003, 105, 29.700000000000003, {
    url: 'https://example.test',
  })
})

it('pins managed photos to unscaled page coordinates without duplicating empty frames', () => {
  const page = document.createElement('div')
  page.innerHTML =
    '<div data-managed-resume-photo="true"><img class="resume-photo" src="https://example.test/photo.png" alt="Photo"></div><div data-managed-resume-photo="true"></div>'
  page.getBoundingClientRect = () => ({ left: 10, top: 20, width: 200, height: 300 })
  Object.defineProperty(page, 'offsetWidth', { value: 100 })
  for (const frame of page.children) frame.getBoundingClientRect = () => ({ left: 30, top: 40, width: 60, height: 80 })
  const [layout] = getPdfPhotoLayouts([page])
  expect(layout[0]).toEqual({ left: 10, top: 10, width: 30, height: 40, src: 'https://example.test/photo.png' })
  pinPdfPhotoFrames(page, layout)
  const overlays = page.querySelectorAll('.resume-photo-pdf-overlay')
  expect(overlays).toHaveLength(1)
  expect(overlays[0].style.left).toBe('10px')
  expect(overlays[0].style.width).toBe('30px')
  expect(overlays[0].getAttribute('draggable')).toBe('false')
  expect(page.children[0].style.visibility).toBe('hidden')
})

it('font registration rejects failed downloads and caches successful bytes', async () => {
  const pdf = { addFileToVFS: vi.fn(), addFont: vi.fn(), setFont: vi.fn() }
  const fetch = vi
    .fn()
    .mockResolvedValueOnce({ ok: false, status: 503 })
    .mockResolvedValue({ ok: true, arrayBuffer: async () => new Uint8Array([65, 66]).buffer })
  await expect(registerPdfTextFont(pdf, fetch)).rejects.toThrow('503')
  await registerPdfTextFont(pdf, fetch)
  await registerPdfTextFont(pdf, fetch)
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(pdf.addFileToVFS).toHaveBeenCalledWith('JobBuddyResumeSans.ttf', 'QUI=')
})
