import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { analyzeFavoriteJob } from '../src/api/jobs'

function mockFetchResponse({ ok = true, status = 200, body = { code: 0, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.unstubAllGlobals()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('analyzeFavoriteJob', () => {
  it('uses body based analyze endpoint with jobKey in payload', async () => {
    const fetchMock = mockFetchResponse({ body: { code: 0, message: 'success', data: { favoriteKey: 'job/1' } } })
    vi.stubGlobal('fetch', fetchMock)

    await expect(analyzeFavoriteJob('job/1', 'resume_1')).resolves.toEqual({ favoriteKey: 'job/1' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/jobs/favorites/analyze')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jobKey: 'job/1', resumeId: 'resume_1' }),
    })
  })

  it('falls back to path based endpoint when body endpoint returns 404/405', async () => {
    const okResponse = {
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ code: 0, message: 'success', data: { favoriteKey: 'job/1' } })),
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 405, text: () => Promise.resolve('') })
      .mockResolvedValueOnce(okResponse)
    vi.stubGlobal('fetch', fetchMock)

    await expect(analyzeFavoriteJob('job/1', 'resume_1')).resolves.toEqual({ favoriteKey: 'job/1' })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/jobs/favorites/analyze')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/jobs/favorites/job%2F1/analyze')
  })
})
