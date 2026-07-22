import { beforeEach, describe, expect, it, vi } from 'vitest'
import { extractInterviewDocument } from '../src/api/interview'

function mockFetchResponse({ ok = true, status = 200, body = { code: 200, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('interview document API', () => {
  it('uploads reference material with multipart form data', async () => {
    const data = {
      fileName: 'reference.docx',
      text: '上海 Java 大模型应用开发岗，月薪40-50k',
      characterCount: 28,
      truncated: false,
    }
    const fetch = mockFetchResponse({ body: { code: 200, message: 'success', data } })
    vi.stubGlobal('fetch', fetch)
    const file = new File(['word-content'], 'reference.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })

    await expect(extractInterviewDocument(file)).resolves.toEqual(data)
    expect(fetch).toHaveBeenCalledWith('/api/interview/documents/extract', expect.any(Object))
    const [, options] = fetch.mock.calls[0]
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
  })

  it('propagates a readable extraction error', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchResponse({
        ok: false,
        status: 400,
        body: { code: 400, message: 'PDF 文件格式无效或已损坏', data: null },
      }),
    )
    const file = new File(['broken'], 'broken.pdf', { type: 'application/pdf' })

    await expect(extractInterviewDocument(file)).rejects.toThrow('PDF 文件格式无效或已损坏')
  })
})
