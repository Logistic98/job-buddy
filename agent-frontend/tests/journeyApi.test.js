import { describe, it, expect, vi, beforeEach } from 'vitest'
import { analyzeJourneyProgress, getJourneyTarget, createJourneyRecord } from '../src/api/journey'

function mockFetchResponse({ ok = true, status = 200, body = { code: 0, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('journey API uses PostgreSQL backend only', () => {
  it('reports backend error when analysis endpoint is missing', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(analyzeJourneyProgress({})).rejects.toThrow('面试进展分析失败')
  })

  it('returns analysis data on success', async () => {
    const data = { summary: '进展良好' }
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 0, message: 'success', data } }))
    await expect(analyzeJourneyProgress({})).resolves.toEqual(data)
  })

  it('propagates business error message from backend', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 1, message: '模型服务超时', data: null } }))
    await expect(analyzeJourneyProgress({})).rejects.toThrow('模型服务超时')
  })

  it('does not fall back locally when target endpoint is missing', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(getJourneyTarget()).rejects.toThrow('求职目标加载失败')
  })

  it('does not persist records locally when create endpoint is missing', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(createJourneyRecord({ company: '测试公司' })).rejects.toThrow('求职记录保存失败')
  })
})
