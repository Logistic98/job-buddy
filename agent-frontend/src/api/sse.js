import { apiFetch } from './http'

const DEFAULT_MAX_RETRIES = parseEnvInt(import.meta.env.VITE_STREAM_RETRY_MAX, 5)
const DEFAULT_BASE_DELAY_MS = parseEnvInt(import.meta.env.VITE_STREAM_RETRY_BASE_DELAY_MS, 500)
const DEFAULT_MAX_DELAY_MS = parseEnvInt(import.meta.env.VITE_STREAM_RETRY_MAX_DELAY_MS, 5000)
const DEFAULT_HEARTBEAT_TIMEOUT_MS = parseEnvInt(import.meta.env.VITE_STREAM_HEARTBEAT_TIMEOUT_MS, 30000)
const DEFAULT_RECONNECT = parseEnvBool(import.meta.env.VITE_STREAM_RECONNECT, false)

function parseEnvInt(raw, fallback) {
  const value = Number.parseInt(String(raw || ''), 10)
  return Number.isFinite(value) && value >= 0 ? value : fallback
}

function parseEnvBool(raw, fallback = false) {
  if (raw === undefined || raw === null || raw === '') return fallback
  return String(raw).toLowerCase() === 'true'
}

export async function streamSse(path, options = {}, handlers = {}) {
  const {
    reconnect = DEFAULT_RECONNECT,
    maxRetries = DEFAULT_MAX_RETRIES,
    baseDelayMs = DEFAULT_BASE_DELAY_MS,
    maxDelayMs = DEFAULT_MAX_DELAY_MS,
    heartbeatTimeoutMs = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    requireDone = false,
    ...fetchOptions
  } = options

  const parentSignal = options.signal || handlers.signal
  let attempt = 0

  while (true) {
    const { signal, abort, controller } = createAbortController(parentSignal)
    try {
      const doneReceived = await streamOnce(
        path,
        {
          ...fetchOptions,
          signal,
          headers: { Accept: 'text/event-stream', ...(fetchOptions.headers || {}) },
        },
        handlers,
        { heartbeatTimeoutMs, abort, requireDone },
      )
      if (!requireDone || doneReceived || parentSignal?.aborted) {
        return
      }
      throw new Error('SSE 连接已结束但未收到 done 事件')
    } catch (error) {
      // fetch/reader 在 AbortController 被 TimeoutError 终止时，部分浏览器仍只抛出普通 AbortError。
      // 优先恢复 signal.reason，才能区分内部心跳超时与用户主动取消，并向上层保留真实诊断。
      const streamError = normalizeStreamError(error, signal)
      const isAbort = isAbortError(streamError, signal)
      const shouldRetry = reconnect && attempt < maxRetries && !isAbort
      if (!shouldRetry) throw streamError

      const retryWait = Math.min(maxDelayMs, Math.floor(baseDelayMs * Math.pow(2, attempt)))
      attempt += 1
      try {
        handlers.onRetry?.(attempt, { waitMs: retryWait, error: streamError })
        await sleep(retryWait, parentSignal)
      } catch (sleepError) {
        throw sleepError
      }
      continue
    } finally {
      controller.abort(new DOMException('SSE loop ended', 'AbortError'))
    }
  }
}

async function streamOnce(path, fetchOptions, handlers, { heartbeatTimeoutMs, abort, requireDone }) {
  const response = await apiFetch(path, {
    ...fetchOptions,
    headers: fetchOptions.headers,
  })

  if (!response.ok || !response.body) {
    throw new Error((await readErrorBody(response)) || `SSE 请求失败: HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let lastEventAt = Date.now()
  let doneReceived = false
  const heartbeatTimer =
    heartbeatTimeoutMs > 0
      ? setInterval(
          () => {
            if (Date.now() - lastEventAt > heartbeatTimeoutMs) {
              try {
                handlers.onHeartbeatTimeout?.()
              } catch (_) {}
              abort(new DOMException('SSE 心跳超时', 'TimeoutError'))
            }
          },
          Math.min(heartbeatTimeoutMs, 2000),
        )
      : null

  const endHeartbeatLoop = () => {
    if (heartbeatTimer) clearInterval(heartbeatTimer)
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      lastEventAt = Date.now()
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        const result = dispatchSse(part, handlers)
        if (result.isDone) {
          doneReceived = true
          break
        }
      }
      if (doneReceived) break
    }
    if (!doneReceived && buffer.trim()) {
      const result = dispatchSse(buffer, handlers)
      if (result.isDone) doneReceived = true
    }
    return requireDone ? doneReceived : true
  } finally {
    endHeartbeatLoop()
    try {
      await reader.cancel()
    } catch (_) {}
  }
}

function createAbortController(parentSignal) {
  const controller = new AbortController()
  if (parentSignal) {
    if (parentSignal.aborted) {
      controller.abort(parentSignal.reason)
    } else {
      parentSignal.addEventListener(
        'abort',
        () => {
          controller.abort(parentSignal.reason)
        },
        { once: true },
      )
    }
  }
  return {
    signal: controller.signal,
    abort: (reason) => controller.abort(reason),
    controller,
  }
}

function normalizeStreamError(error, signal) {
  const reason = signal?.aborted ? signal.reason : null
  if (reason instanceof DOMException || reason instanceof Error) {
    if (reason.name === 'TimeoutError') return reason
  }
  if (String(reason || '').includes('SSE 心跳超时')) {
    return new DOMException('SSE 心跳超时', 'TimeoutError')
  }
  return error
}

function isAbortError(error, signal) {
  if (error?.name === 'TimeoutError') return false
  if (error instanceof DOMException || error instanceof Error) return error.name === 'AbortError'
  if (!signal?.aborted) return false

  const reason = signal.reason
  if (!reason) return true
  if (reason instanceof DOMException || reason instanceof Error) return reason.name === 'AbortError'
  return !String(reason).includes('SSE 心跳超时') && String(reason) !== 'TimeoutError'
}

function sleep(timeoutMs, signal) {
  if (timeoutMs <= 0) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const clear = () => {
      clearTimeout(timer)
      if (signal) signal.removeEventListener('abort', onAbort)
    }
    const timer = setTimeout(() => {
      clear()
      resolve()
    }, timeoutMs)
    if (!signal) return

    const onAbort = () => {
      clear()
      reject(signal.reason instanceof Error ? signal.reason : new DOMException('Aborted', 'AbortError'))
    }

    if (signal.aborted) {
      onAbort()
    } else {
      signal.addEventListener('abort', onAbort, { once: true })
    }
  })
}

async function readErrorBody(response) {
  try {
    const text = await response.text()
    if (!text) return ''
    const data = JSON.parse(text)
    return data.message || data.detail || text.slice(0, 200)
  } catch (_) {
    return `SSE 请求失败: HTTP ${response.status}`
  }
}

export function dispatchSse(raw, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) {
      let value = line.slice(5)
      if (value.startsWith(' ')) value = value.slice(1)
      dataLines.push(value)
    }
  }
  const text = dataLines.join('\n')
  let data = text
  try {
    data = JSON.parse(text)
  } catch (_) {}
  const isDone = event === 'done' || (event === 'message' && data === '[DONE]')
  try {
    handlers.onEvent?.(event, data)
    if (event === 'heartbeat' || event === 'ping') {
      handlers.onHeartbeat?.(data)
    }
  } catch (_) {}
  try {
    handlers[event]?.(data)
  } catch (_) {}
  return { event, data, isDone }
}
