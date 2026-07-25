import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadResume } from '../src/api/resume'

function mockFetchResponse(data) {
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    text: () => Promise.resolve(JSON.stringify({ code: 200, message: 'success', data })),
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('resume API', () => {
  it('sends an ASCII-encoded original name beside the multipart file', async () => {
    const uploaded = {
      resumeId: 'resume-1',
      suffix: 'pdf',
      originalName: '示例候选人-Java开发-求职简历.pdf',
    }
    const fetch = mockFetchResponse(uploaded)
    vi.stubGlobal('fetch', fetch)
    const file = new File(['pdf-content'], uploaded.originalName, { type: 'application/pdf' })

    await expect(uploadResume(file, 'session-1')).resolves.toEqual(uploaded)

    const [, options] = fetch.mock.calls[0]
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.body.get('originalNameEncoded')).toBe(encodeURIComponent(uploaded.originalName))
    expect(options.body.get('sessionId')).toBe('session-1')
  })
})
