import { apiFetch, parseApiResponse } from './http'
import { streamSse } from './sse'

export async function getLatestAnalysisTask(taskType, resourceKey) {
  const params = new URLSearchParams({ taskType, resourceKey })
  const response = await apiFetch(`/analysis-tasks/latest?${params.toString()}`, { cache: 'no-store' })
  return parseApiResponse(response, '分析任务状态加载失败')
}

export async function getAnalysisTask(taskId) {
  const response = await apiFetch(`/analysis-tasks/${encodeURIComponent(taskId)}`, { cache: 'no-store' })
  return parseApiResponse(response, '分析任务状态加载失败')
}

const STREAM_RECONNECT = String(import.meta.env.VITE_STREAM_RECONNECT || 'false').toLowerCase() === 'true'

export function streamAnalysisTask(taskId, handlers = {}, signal = undefined) {
  return streamSse(
    `/analysis-tasks/${encodeURIComponent(taskId)}/stream`,
    {
      method: 'GET',
      cache: 'no-store',
      requireDone: true,
      reconnect: STREAM_RECONNECT,
      signal,
    },
    handlers,
  )
}

export function isAnalysisTaskRunning(task) {
  return task?.status === 'queued' || task?.status === 'running'
}
