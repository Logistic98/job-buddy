import { describe, it, expect, vi, beforeEach } from 'vitest'
import { analyzeJourneyProgress, getJourneyTarget, createJourneyRecord, listJourneyRecords } from '../src/api/journey'

function mockFetchResponse({ ok = true, status = 200, body = { code: 0, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('analyzeJourneyProgress', () => {
  it('throws explicit error when backend endpoint is missing (404)', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(analyzeJourneyProgress({})).rejects.toThrow('面试进展分析服务不可用')
  })

  it('returns data on success without local fallback', async () => {
    const data = { summary: '进展良好' }
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 0, message: 'success', data } }))
    await expect(analyzeJourneyProgress({})).resolves.toEqual(data)
  })

  it('propagates business error message from backend', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 1, message: '模型服务超时', data: null } }))
    await expect(analyzeJourneyProgress({})).rejects.toThrow('模型服务超时')
  })
})

describe('journey local fallback (kept for CRUD)', () => {
  it('getJourneyTarget falls back to local default on 404', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    const target = await getJourneyTarget()
    expect(target.localOnly).toBe(true)
    expect(target.targetId).toBe('target_local')
  })

  it('createJourneyRecord stores record locally on 404 and list filters it', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    const saved = await createJourneyRecord({ company: '测试公司', positionName: 'AI工程师' })
    expect(saved.localOnly).toBe(true)
    const rows = await listJourneyRecords({ keyword: '测试公司' })
    expect(rows).toHaveLength(1)
    const empty = await listJourneyRecords({ keyword: '不存在的公司' })
    expect(empty).toHaveLength(0)
  })
})
