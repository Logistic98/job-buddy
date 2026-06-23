import { describe, it, expect, vi } from 'vitest'
import { imageUrlToDataUrl } from '../src/utils/imageData'

describe('imageUrlToDataUrl', () => {
  it('returns empty string for nullish input', async () => {
    expect(await imageUrlToDataUrl('')).toBe('')
    expect(await imageUrlToDataUrl(null)).toBe('')
  })

  it('passes through existing data URLs without fetching', async () => {
    const fetchImpl = vi.fn()
    const dataUrl = 'data:image/png;base64,AAAA'
    expect(await imageUrlToDataUrl(dataUrl, fetchImpl)).toBe(dataUrl)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('returns original url when response is not ok', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false })
    expect(await imageUrlToDataUrl('http://x/a.png', fetchImpl)).toBe('http://x/a.png')
  })

  it('returns original url when fetch throws', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('boom'))
    expect(await imageUrlToDataUrl('http://x/a.png', fetchImpl)).toBe('http://x/a.png')
  })

  it('converts a fetched blob into a data url', async () => {
    const blob = new Blob(['hello'], { type: 'text/plain' })
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) })
    const result = await imageUrlToDataUrl('http://x/a.png', fetchImpl)
    expect(result).toMatch(/^data:/)
  })
})
