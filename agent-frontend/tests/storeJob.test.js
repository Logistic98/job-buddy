import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/jobs', () => ({
  listFavoriteJobs: vi.fn(async () => []),
  saveFavoriteJob: vi.fn(async () => []),
  deleteFavoriteJob: vi.fn(async () => []),
  analyzeFavoriteJob: vi.fn(async () => ({})),
  startFavoriteAnalysisTask: vi.fn(async () => ({})),
  latestFavoriteAnalysisTask: vi.fn(async () => null),
  getAnalysisTask: vi.fn(async () => null),
  streamAnalysisTask: vi.fn(async () => {}),
  fetchJobDetail: vi.fn(async () => ({})),
}))

import {
  deleteFavoriteJob,
  fetchJobDetail,
  listFavoriteJobs,
  saveFavoriteJob,
  startFavoriteAnalysisTask,
  streamAnalysisTask,
} from '../src/api/jobs'
import { useJobStore } from '../src/stores/job'

describe('job store - favorites', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('addFavorite synchronizes the PostgreSQL-backed server state', async () => {
    const store = useJobStore()
    saveFavoriteJob.mockImplementation(async (item) => [item])
    await store.addFavorite({ securityId: 'sec-1', jobName: 'Java开发' })
    expect(store.favorites).toHaveLength(1)
    expect(store.isFavorite({ securityId: 'sec-1' })).toBe(true)
    expect(saveFavoriteJob).toHaveBeenCalledTimes(1)
  })

  it('addFavorite rolls back and rethrows when JD collection or save fails', async () => {
    const store = useJobStore()
    saveFavoriteJob.mockRejectedValue(new Error('职位描述采集失败'))

    await expect(store.addFavorite({ securityId: 'sec-1', jobName: 'Java开发' })).rejects.toThrow('职位描述采集失败')

    expect(store.favorites).toEqual([])
    expect(store.favoriteError).toBe('职位描述采集失败')
    expect(store.detailError({ securityId: 'sec-1' })).toBe('职位描述采集失败')
  })

  it('removeFavorite keeps the row visible until the server confirms deletion', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: 'Java开发' }]
    let resolveDelete
    deleteFavoriteJob.mockReturnValue(
      new Promise((resolve) => {
        resolveDelete = resolve
      }),
    )

    const removing = store.removeFavorite({ favoriteKey: 'sec-1' })

    expect(store.favorites).toHaveLength(1)
    expect(store.isRemovingFavorite({ favoriteKey: 'sec-1' })).toBe(true)
    resolveDelete([])
    await removing
    expect(store.favorites).toEqual([])
    expect(store.isRemovingFavorite({ favoriteKey: 'sec-1' })).toBe(false)
  })

  it('removeFavorite keeps the row and rethrows when the server delete fails', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: 'Java开发' }]
    deleteFavoriteJob.mockRejectedValue(new Error('网络异常'))

    await expect(store.removeFavorite({ favoriteKey: 'sec-1' })).rejects.toThrow('网络异常')

    expect(store.favorites).toHaveLength(1)
    expect(store.favoriteError).toBe('网络异常')
    expect(store.isRemovingFavorite({ favoriteKey: 'sec-1' })).toBe(false)
  })

  it('removeFavorite ignores duplicate submissions for the same job', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: 'Java开发' }]
    let resolveDelete
    deleteFavoriteJob.mockReturnValue(
      new Promise((resolve) => {
        resolveDelete = resolve
      }),
    )

    const first = store.removeFavorite({ favoriteKey: 'sec-1' })
    const second = await store.removeFavorite({ favoriteKey: 'sec-1' })

    expect(second).toBe(false)
    expect(deleteFavoriteJob).toHaveBeenCalledTimes(1)
    resolveDelete([])
    await first
  })

  it('does not let an older favorites load restore a deleted job', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: 'Java开发' }]
    let resolveLoad
    listFavoriteJobs.mockReturnValue(
      new Promise((resolve) => {
        resolveLoad = resolve
      }),
    )
    deleteFavoriteJob.mockResolvedValue([])

    const loading = store.loadFavorites()
    await store.removeFavorite({ favoriteKey: 'sec-1' })
    resolveLoad([{ favoriteKey: 'sec-1', jobName: '迟到响应岗位' }])
    await loading

    expect(store.favorites).toEqual([])
  })

  it('toggleFavorite adds then removes the same job', async () => {
    const store = useJobStore()
    saveFavoriteJob.mockImplementation(async (item) => [item])
    deleteFavoriteJob.mockResolvedValue([])
    await store.toggleFavorite({ securityId: 'sec-1' })
    expect(store.favoriteCount).toBe(1)
    await store.toggleFavorite({ securityId: 'sec-1' })
    expect(store.favoriteCount).toBe(0)
  })

  it('drops a late favorites response after an authentication change', async () => {
    let resolveLoad
    listFavoriteJobs.mockReturnValue(
      new Promise((resolve) => {
        resolveLoad = resolve
      }),
    )
    const store = useJobStore()
    const loading = store.loadFavorites()
    store.disposeForAuthChange()
    resolveLoad([{ favoriteKey: 'account-a', jobName: '账号 A 岗位' }])
    await loading
    expect(store.favorites).toEqual([])
    expect(store.favoriteLoading).toBe(false)
  })

  it('loadFavorites uses only the server response and never migrates browser data', async () => {
    listFavoriteJobs.mockResolvedValue([{ favoriteKey: 'server-1', jobName: '服务端岗位' }])
    const store = useJobStore()
    await store.loadFavorites()
    expect(saveFavoriteJob).not.toHaveBeenCalled()
    expect(store.favorites).toEqual([{ favoriteKey: 'server-1', jobName: '服务端岗位' }])
    expect(store.favoriteLoading).toBe(false)
  })

  it('analyzeFavorite creates a background task and applies an immediately available terminal result', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: '原标题', jobDescription: '负责 Agent 平台研发' }]
    startFavoriteAnalysisTask.mockResolvedValue({
      taskId: 'task-1',
      resourceKey: 'sec-1',
      status: 'succeeded',
      stage: 'completed',
      result: { favoriteKey: 'sec-1', jobName: '新标题', analysis: { match: { score: 88 } } },
    })

    const task = await store.analyzeFavorite(store.favorites[0], 'resume-1')

    expect(startFavoriteAnalysisTask).toHaveBeenCalledWith(
      {
        favoriteKey: 'sec-1',
        jobName: '原标题',
        jobDescription: '负责 Agent 平台研发',
      },
      'resume-1',
    )
    expect(task.taskId).toBe('task-1')
    expect(store.favorites[0].jobName).toBe('新标题')
    expect(store.isAnalyzingFavorite({ favoriteKey: 'sec-1' })).toBe(false)
  })

  it('drops a late account-A favorites response after disposeForAuthChange', async () => {
    let resolveLoad
    listFavoriteJobs.mockReturnValue(
      new Promise((resolve) => {
        resolveLoad = resolve
      }),
    )
    const store = useJobStore()

    const loading = store.loadFavorites()
    store.disposeForAuthChange()
    resolveLoad([{ favoriteKey: 'account-a-job', jobName: '账号 A 岗位' }])
    await loading

    expect(store.favorites).toEqual([])
    expect(store.favoriteLoading).toBe(false)
  })

  it('aborts an account-A favorite analysis stream during authentication change', async () => {
    let streamSignal
    startFavoriteAnalysisTask.mockResolvedValue({
      taskId: 'account-a-task',
      resourceKey: 'sec-1',
      status: 'running',
      stage: 'analyzing',
      result: {},
      partialResult: {},
    })
    streamAnalysisTask.mockImplementation((_taskId, _handlers, signal) => {
      streamSignal = signal
      return new Promise(() => {})
    })
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: '账号 A 岗位' }]

    await store.analyzeFavorite(store.favorites[0])
    store.disposeForAuthChange()

    expect(streamSignal.aborted).toBe(true)
    expect(store.favoriteAnalysisTasks).toEqual({})
  })

  it('renders a running task partial result before the final result exists', () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: '岗位' }]

    store.applyFavoriteAnalysisTask({
      taskId: 'task-partial',
      resourceKey: 'sec-1',
      status: 'running',
      stage: 'partial_overview',
      result: {},
      partialResult: {
        favoriteKey: 'sec-1',
        jobName: '岗位',
        analysis: { match: { score: 78, reasoning: '首组结论' } },
      },
    })

    expect(store.isAnalyzingFavorite({ favoriteKey: 'sec-1' })).toBe(true)
    expect(store.favorites[0].analysis.match.score).toBe(78)
    expect(store.favorites[0].analysis.match.reasoning).toBe('首组结论')
  })

  it('loadJobDetail requires securityId or url before calling the API', async () => {
    const store = useJobStore()
    const result = await store.loadJobDetail({ jobName: '无ID岗位' })
    expect(result).toBeNull()
    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(store.detailError({ jobName: '无ID岗位' })).toContain('securityId')
  })

  it('loadJobDetail merges and persists detail for an imported favorite', async () => {
    const store = useJobStore()
    store.jobs = [{ securityId: 'sec-1', jobName: '岗位' }]
    store.favorites = [{ favoriteKey: 'sec-1', securityId: 'sec-1', jobName: '岗位' }]
    fetchJobDetail.mockResolvedValue({ jobDescription: '职责描述' })
    saveFavoriteJob.mockImplementation(async (item) => [item])

    await store.loadJobDetail({ favoriteKey: 'sec-1', securityId: 'sec-1', jobName: '岗位' })

    expect(store.jobs[0].jobDescription).toBe('职责描述')
    expect(store.favorites[0].jobDescription).toBe('职责描述')
    expect(saveFavoriteJob).toHaveBeenCalledWith({
      favoriteKey: 'sec-1',
      securityId: 'sec-1',
      jobName: '岗位',
      jobDescription: '职责描述',
    })
    expect(store.detailError({ securityId: 'sec-1' })).toBe('')
  })

  it('loadJobDetail records an error message on failure without throwing', async () => {
    const store = useJobStore()
    fetchJobDetail.mockRejectedValue(new Error('获取失败'))
    const result = await store.loadJobDetail({ securityId: 'sec-1' })
    expect(result).toBeNull()
    expect(store.detailError({ securityId: 'sec-1' })).toBe('获取失败')
    expect(store.isLoadingDetail({ securityId: 'sec-1' })).toBe(false)
  })

  it('loadJobDetail rethrows auth-required errors for the caller to handle', async () => {
    const store = useJobStore()
    const authError = Object.assign(new Error('需要登录'), { authRequired: true })
    fetchJobDetail.mockRejectedValue(authError)
    await expect(store.loadJobDetail({ securityId: 'sec-1' })).rejects.toThrow('需要登录')
  })
})
