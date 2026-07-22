import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamSse } from '../src/api/sse'

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
