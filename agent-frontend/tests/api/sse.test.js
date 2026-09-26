import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamSse } from '../../src/api/sse'

function streamResponse(chunks) {
  let index = 0
  return {
    ok: true,
    status: 200,
    body: {
      getReader() {
        return {
          read: vi.fn(async () => {
            if (index >= chunks.length) return { done: true, value: undefined }
            return { done: false, value: new globalThis.TextEncoder().encode(chunks[index++]) }
          }),
          cancel: vi.fn(async () => {}),
        }
      },
    },
  }
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('streamSse', () => {
  it('keeps the stream alive on heartbeat and completes after done', async () => {
    const heartbeat = vi.fn()
    const done = vi.fn()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        streamResponse([
          'event: heartbeat\ndata: {"timestamp":"2026-07-23T04:08:00Z"}\n\n',
          'event: done\ndata: {"ok":true}\n\n',
        ]),
      ),
    )

    await streamSse(
      '/chat/stream',
      { method: 'POST', requireDone: true, reconnect: false, heartbeatTimeoutMs: 30 },
      { heartbeat, done },
    )

    expect(heartbeat).toHaveBeenCalledOnce()
    expect(done).toHaveBeenCalledWith({ ok: true })
  })

  it('reports an internal heartbeat timeout instead of treating it as a user abort', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url, options) => ({
        ok: true,
        status: 200,
        body: {
          getReader() {
            return {
              read: () =>
                new Promise((_resolve, reject) => {
                  options.signal.addEventListener(
                    'abort',
                    () => reject(new DOMException('The operation was aborted', 'AbortError')),
                    { once: true },
                  )
                }),
              cancel: vi.fn(async () => {}),
            }
          },
        },
      })),
    )

    const request = streamSse('/chat/stream', { requireDone: true, reconnect: false, heartbeatTimeoutMs: 20 }, {})
    const rejected = expect(request).rejects.toMatchObject({ name: 'TimeoutError', message: 'SSE 心跳超时' })

    await vi.advanceTimersByTimeAsync(41)
    await rejected
  })
})

describe('streamSse transport boundaries', () => {
  it.each([
    ['{"message":"unavailable"}', 'unavailable'],
    ['{"detail":"overloaded"}', 'overloaded'],
    ['{"reason":"busy"}', '{"reason":"busy"}'],
    ['', 'SSE 请求失败: HTTP 503'],
    ['not json', 'SSE 请求失败: HTTP 503'],
  ])('preserves server diagnostics for %s', async (body, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, text: async () => body }))
    await expect(streamSse('/stream', { reconnect: false })).rejects.toThrow(message)
  })

  it('dispatches a terminal frame without a trailing delimiter and tolerates cancel failure', async () => {
    const response = streamResponse(['event: done\ndata: {"ok":true}'])
    const reader = response.body.getReader()
    reader.cancel.mockRejectedValue(new Error('already closed'))
    response.body.getReader = () => reader
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const done = vi.fn()
    await streamSse('/stream', { requireDone: true, heartbeatTimeoutMs: 0 }, { done })
    expect(done).toHaveBeenCalledWith({ ok: true })
    expect(reader.cancel).toHaveBeenCalledOnce()
  })

  it('rejects an incomplete stream when a terminal event is required', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse(['data: partial\n\n'])))
    await expect(streamSse('/stream', { requireDone: true, reconnect: false })).rejects.toThrow('未收到 done')
  })

  it('retries with capped exponential backoff and retains request headers', async () => {
    vi.useFakeTimers()
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new Error('first'))
      .mockRejectedValueOnce(new Error('second'))
      .mockResolvedValue(streamResponse(['data: [DONE]\n\n']))
    vi.stubGlobal('fetch', fetch)
    const onRetry = vi.fn()
    const controller = new AbortController()
    const request = streamSse(
      '/stream',
      {
        reconnect: true,
        maxRetries: 2,
        baseDelayMs: 10,
        maxDelayMs: 15,
        requireDone: true,
        signal: controller.signal,
        headers: { 'X-Test': 'value' },
      },
      { onRetry },
    )
    await vi.advanceTimersByTimeAsync(25)
    await request
    expect(onRetry.mock.calls.map(([attempt, info]) => [attempt, info.waitMs])).toEqual([
      [1, 10],
      [2, 15],
    ])
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls[2][1].headers).toEqual({ Accept: 'text/event-stream', 'X-Test': 'value' })
  })

  it('stops retrying at the configured limit', async () => {
    const fetch = vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', fetch)
    await expect(streamSse('/stream', { reconnect: true, maxRetries: 2, baseDelayMs: 0 })).rejects.toThrow('offline')
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it.each([false, true])('cancels retry backoff when parent aborts, pre-aborted=%s', async (immediate) => {
    vi.useFakeTimers()
    const controller = new AbortController()
    if (immediate) controller.abort('cancelled')
    const fetch = vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', fetch)
    const request = streamSse('/stream', { reconnect: true, baseDelayMs: 100 }, { signal: controller.signal })
    const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    if (!immediate) controller.abort('cancelled')
    await rejected
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('propagates a user cancellation without reconnecting', async () => {
    const controller = new AbortController()
    const cancelled = new DOMException('user cancelled', 'AbortError')
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, { signal }) =>
          new Promise((_resolve, reject) => {
            signal.addEventListener('abort', () => reject(cancelled), { once: true })
          }),
      ),
    )
    const request = streamSse('/stream', { reconnect: true, signal: controller.signal })
    const rejected = expect(request).rejects.toBe(cancelled)
    controller.abort(cancelled)
    await rejected
    expect(fetch).toHaveBeenCalledOnce()
  })
})
