import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createSmartExam, deleteExam, extractInterviewDocument } from '../src/api/interview'

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

describe('smart interview practice API', () => {
  it('posts natural-language requirements to the smart composition endpoint', async () => {
    const data = { examId: 'practice-smart-1', totalCount: 6 }
    const fetch = mockFetchResponse({ body: { code: 200, message: 'success', data } })
    vi.stubGlobal('fetch', fetch)

    await expect(createSmartExam({ requirements: '选择 6 道 Java 并发中等难度题，30 分钟考试模式' })).resolves.toEqual(
      data,
    )

    expect(fetch).toHaveBeenCalledWith(
      '/api/interview/practices/smart',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ requirements: '选择 6 道 Java 并发中等难度题，30 分钟考试模式' }),
      }),
    )
  })

  it('deletes a practice record through the owned practice endpoint', async () => {
    const fetch = mockFetchResponse({
      body: { code: 200, message: 'success', data: { deleted: true } },
    })
    vi.stubGlobal('fetch', fetch)

    await expect(deleteExam('practice 1')).resolves.toEqual({ deleted: true })
    expect(fetch).toHaveBeenCalledWith(
      '/api/interview/practices/practice%201',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})
