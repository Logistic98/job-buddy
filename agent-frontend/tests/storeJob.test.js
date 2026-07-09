import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/jobs', () => ({
  listFavoriteJobs: vi.fn(async () => []),
  saveFavoriteJob: vi.fn(async () => []),
  deleteFavoriteJob: vi.fn(async () => []),
  analyzeFavoriteJob: vi.fn(async () => ({})),
  fetchJobDetail: vi.fn(async () => ({})),
}))

import { analyzeFavoriteJob, deleteFavoriteJob, fetchJobDetail, listFavoriteJobs, saveFavoriteJob } from '../src/api/jobs'
import { useJobStore } from '../src/stores/job'

const FAVORITES_KEY = 'job-buddy.favorite-jobs'

describe('job store - favorites', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('addFavorite stores the job locally and syncs to the server', async () => {
    const store = useJobStore()
    saveFavoriteJob.mockImplementation(async item => [item])
    await store.addFavorite({ securityId: 'sec-1', jobName: 'Java开发' })
    expect(store.favorites).toHaveLength(1)
    expect(store.isFavorite({ securityId: 'sec-1' })).toBe(true)
    expect(JSON.parse(localStorage.getItem(FAVORITES_KEY))).toHaveLength(1)
    expect(saveFavoriteJob).toHaveBeenCalledTimes(1)
  })

  it('removeFavorite rolls back when the server delete fails', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: 'Java开发' }]
    deleteFavoriteJob.mockRejectedValue(new Error('网络异常'))
    await store.removeFavorite({ favoriteKey: 'sec-1' })
    expect(store.favorites).toHaveLength(1)
    expect(store.favoriteError).toBe('网络异常')
  })

  it('toggleFavorite adds then removes the same job', async () => {
    const store = useJobStore()
    saveFavoriteJob.mockImplementation(async item => [item])
    deleteFavoriteJob.mockResolvedValue([])
    await store.toggleFavorite({ securityId: 'sec-1' })
    expect(store.favoriteCount).toBe(1)
    await store.toggleFavorite({ securityId: 'sec-1' })
    expect(store.favoriteCount).toBe(0)
  })

  it('loadFavorites migrates local rows to the server when it is empty', async () => {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify([{ securityId: 'local-1', jobName: '本地岗位' }]))
    listFavoriteJobs.mockResolvedValue([])
    saveFavoriteJob.mockImplementation(async item => [item])
    const store = useJobStore()
    await store.loadFavorites()
    expect(saveFavoriteJob).toHaveBeenCalledTimes(1)
    expect(store.favorites).toHaveLength(1)
    expect(store.favoriteLoading).toBe(false)
  })

  it('analyzeFavorite replaces the analyzed row and clears in-flight key', async () => {
    const store = useJobStore()
    store.favorites = [{ favoriteKey: 'sec-1', jobName: '旧标题' }]
    analyzeFavoriteJob.mockResolvedValue({ favoriteKey: 'sec-1', jobName: '新标题', analysis: 'ok' })
    const updated = await store.analyzeFavorite({ favoriteKey: 'sec-1' }, 'resume-1')
    expect(updated.analysis).toBe('ok')
    expect(store.favorites[0].jobName).toBe('新标题')
    expect(store.isAnalyzingFavorite({ favoriteKey: 'sec-1' })).toBe(false)
  })

  it('loadJobDetail requires securityId or url before calling the API', async () => {
    const store = useJobStore()
    const result = await store.loadJobDetail({ jobName: '无ID岗位' })
    expect(result).toBeNull()
    expect(fetchJobDetail).not.toHaveBeenCalled()
    expect(store.detailError({ jobName: '无ID岗位' })).toContain('securityId')
  })

  it('loadJobDetail merges detail into jobs and favorites', async () => {
    const store = useJobStore()
    store.jobs = [{ securityId: 'sec-1', jobName: '岗位' }]
    store.favorites = [{ securityId: 'sec-1', jobName: '岗位' }]
    fetchJobDetail.mockResolvedValue({ jobDescription: '职责描述' })
    await store.loadJobDetail({ securityId: 'sec-1' })
    expect(store.jobs[0].jobDescription).toBe('职责描述')
    expect(store.favorites[0].jobDescription).toBe('职责描述')
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
