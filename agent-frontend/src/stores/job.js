import { defineStore } from 'pinia'
import {
  deleteFavoriteJob,
  fetchJobDetail,
  getAnalysisTask,
  latestFavoriteAnalysisTask,
  listFavoriteJobs,
  saveFavoriteJob,
  startFavoriteAnalysisTask,
  streamAnalysisTask,
} from '../api/jobs'
import { isAnalysisTaskRunning } from '../api/analysisTasks'

const favoriteTaskSubscriptions = new Map()
const FAVORITE_TASK_POLL_DELAY_MS = Number.parseInt(
  String(import.meta.env.VITE_ANALYSIS_TASK_POLL_DELAY_MS || '1500'),
  10,
)

function jobKey(item) {
  return String(
    item?.favoriteKey ||
      item?.securityId ||
      item?.id ||
      item?.jobId ||
      item?.encryptJobId ||
      `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`,
  )
}

function jobDescription(item) {
  return String(
    item?.jobDescription ||
      item?.description ||
      item?.postDescription ||
      item?.jobDesc ||
      item?.jobSecText ||
      item?.detailText ||
      '',
  ).trim()
}

export const useJobStore = defineStore('job', {
  state: () => ({
    jobs: [],
    match: null,
    favorites: [],
    favoriteLoading: false,
    favoriteError: '',
    favoriteMutationVersion: 0,
    favoriteMutationPendingCount: 0,
    removingFavoriteKeys: [],
    favoriteAnalysisTasks: {},
    detailLoadingKeys: [],
    detailErrors: {},
    lifecycleRevision: 0,
  }),
  getters: {
    favoriteCount: (state) => state.favorites.length,
  },
  actions: {
    disposeForAuthChange() {
      const nextRevision = this.lifecycleRevision + 1
      for (const subscription of favoriteTaskSubscriptions.values()) {
        try {
          subscription.controller?.abort()
        } catch (_) {}
        if (subscription.timer) window.clearTimeout(subscription.timer)
      }
      favoriteTaskSubscriptions.clear()
      this.$reset()
      this.lifecycleRevision = nextRevision
    },
    setJobs(jobs) {
      this.jobs = Array.isArray(jobs) ? jobs : []
    },
    setMatch(match) {
      this.match = match || null
    },
    isFavorite(item) {
      const key = jobKey(item)
      return this.favorites.some((row) => jobKey(row) === key)
    },
    async loadFavorites() {
      const revision = this.lifecycleRevision
      const mutationVersion = this.favoriteMutationVersion
      this.favoriteLoading = true
      try {
        const rows = await listFavoriteJobs()
        if (revision !== this.lifecycleRevision) return []
        if (mutationVersion === this.favoriteMutationVersion && this.favoriteMutationPendingCount === 0) {
          this.favorites = Array.isArray(rows) ? rows : []
          this.favoriteError = ''
          for (const item of this.favorites) this.restoreFavoriteAnalysis(item).catch(() => {})
        }
      } catch (error) {
        if (revision !== this.lifecycleRevision) return []
        if (mutationVersion === this.favoriteMutationVersion) {
          this.favoriteError = error?.message || '加载岗位收藏失败'
        }
      } finally {
        if (revision === this.lifecycleRevision) this.favoriteLoading = false
      }
    },
    async addFavorite(item) {
      if (!item) return
      const revision = this.lifecycleRevision
      const key = jobKey(item)
      const before = [...this.favorites]
      const existing = this.favorites.findIndex((row) => jobKey(row) === key)
      const merged = {
        ...(existing >= 0 ? this.favorites[existing] : {}),
        ...item,
        favoriteKey: key,
        favoritedAt: existing >= 0 ? this.favorites[existing].favoritedAt : new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      if (existing >= 0) this.favorites.splice(existing, 1, merged)
      else this.favorites.unshift(merged)
      this.favoriteMutationVersion += 1
      this.favoriteMutationPendingCount += 1
      try {
        const saved = await saveFavoriteJob(merged)
        if (revision !== this.lifecycleRevision) return null
        this.favorites = saved
        this.favoriteError = ''
        this.setDetailError(item, '')
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.favorites = before
        this.favoriteError = error?.message || '保存岗位收藏失败'
        this.setDetailError(item, this.favoriteError)
        throw error
      } finally {
        if (revision === this.lifecycleRevision) {
          this.favoriteMutationPendingCount = Math.max(0, this.favoriteMutationPendingCount - 1)
          this.favoriteMutationVersion += 1
        }
      }
    },
    isRemovingFavorite(item) {
      return this.removingFavoriteKeys.includes(jobKey(item))
    },
    async removeFavorite(item) {
      const revision = this.lifecycleRevision
      const key = jobKey(item)
      if (!key || this.removingFavoriteKeys.includes(key)) return false
      this.removingFavoriteKeys.push(key)
      this.favoriteMutationVersion += 1
      this.favoriteMutationPendingCount += 1
      try {
        const rows = await deleteFavoriteJob(key)
        if (revision !== this.lifecycleRevision) return false
        this.favorites = Array.isArray(rows) ? rows : this.favorites.filter((row) => jobKey(row) !== key)
        this.favoriteError = ''
        return true
      } catch (error) {
        if (revision !== this.lifecycleRevision) return false
        this.favoriteError = error?.message || '移出岗位收藏失败'
        throw error
      } finally {
        if (revision === this.lifecycleRevision) {
          this.removingFavoriteKeys = this.removingFavoriteKeys.filter((itemKey) => itemKey !== key)
          this.favoriteMutationPendingCount = Math.max(0, this.favoriteMutationPendingCount - 1)
          this.favoriteMutationVersion += 1
        }
      }
    },
    async toggleFavorite(item) {
      if (this.isFavorite(item)) await this.removeFavorite(item)
      else await this.addFavorite(item)
    },
    favoriteAnalysisTask(item) {
      return this.favoriteAnalysisTasks[jobKey(item)] || null
    },
    favoriteAnalysisError(item) {
      return this.favoriteAnalysisTask(item)?.errorMessage || ''
    },
    isAnalyzingFavorite(item) {
      return isAnalysisTaskRunning(this.favoriteAnalysisTask(item))
    },
    applyFavoriteAnalysisTask(task) {
      if (!task?.resourceKey) return
      this.favoriteAnalysisTasks = { ...this.favoriteAnalysisTasks, [task.resourceKey]: task }
      const visibleResult =
        task.status === 'succeeded' && task.result && Object.keys(task.result).length ? task.result : task.partialResult
      if (visibleResult && Object.keys(visibleResult).length) {
        const idx = this.favorites.findIndex((row) => jobKey(row) === task.resourceKey)
        if (idx >= 0) this.favorites.splice(idx, 1, visibleResult)
        else this.favorites.unshift(visibleResult)
      }
      if (task.status === 'failed') this.favoriteError = task.errorMessage || '岗位分析失败'
    },
    async restoreFavoriteAnalysis(item, expectedRevision = this.lifecycleRevision) {
      const key = jobKey(item)
      if (!key) return null
      const task = await latestFavoriteAnalysisTask(key)
      if (expectedRevision !== this.lifecycleRevision || !task) return null
      this.applyFavoriteAnalysisTask(task)
      if (isAnalysisTaskRunning(task)) this.watchFavoriteAnalysisTask(task.taskId)
      return task
    },
    async analyzeFavorite(item, resumeId = '') {
      const key = jobKey(item)
      if (!key || this.isAnalyzingFavorite(item)) return this.favoriteAnalysisTask(item)
      const revision = this.lifecycleRevision
      this.favoriteError = ''
      try {
        const task = await startFavoriteAnalysisTask({ ...item, favoriteKey: key }, resumeId)
        if (revision !== this.lifecycleRevision) return null
        this.applyFavoriteAnalysisTask(task)
        this.watchFavoriteAnalysisTask(task.taskId)
        return task
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.favoriteError = error?.message || '岗位分析失败'
        throw error
      }
    },
    watchFavoriteAnalysisTask(taskId) {
      if (!taskId || favoriteTaskSubscriptions.has(taskId)) return
      const revision = this.lifecycleRevision
      const subscription = { controller: new AbortController(), timer: null }
      favoriteTaskSubscriptions.set(taskId, subscription)
      const isCurrent = () =>
        revision === this.lifecycleRevision && favoriteTaskSubscriptions.get(taskId) === subscription
      const apply = (task) => {
        if (isCurrent()) this.applyFavoriteAnalysisTask(task)
      }
      streamAnalysisTask(
        taskId,
        { snapshot: apply, progress: apply, partial_result: apply, result: apply, error: apply },
        subscription.controller.signal,
      )
        .catch(async (error) => {
          if (!isCurrent() || error?.name === 'AbortError') return
          try {
            const task = await getAnalysisTask(taskId)
            if (!isCurrent()) return
            apply(task)
            if (isAnalysisTaskRunning(task)) {
              const delay =
                Number.isFinite(FAVORITE_TASK_POLL_DELAY_MS) && FAVORITE_TASK_POLL_DELAY_MS > 0
                  ? FAVORITE_TASK_POLL_DELAY_MS
                  : 1500
              subscription.timer = window.setTimeout(() => {
                if (!isCurrent()) return
                favoriteTaskSubscriptions.delete(taskId)
                this.watchFavoriteAnalysisTask(taskId)
              }, delay)
            }
          } catch (_) {
            // 订阅网络错误不等同于服务端任务失败，保留当前任务状态供页面下次恢复。
          }
        })
        .finally(() => {
          if (!isCurrent()) return
          const task = Object.values(this.favoriteAnalysisTasks).find((item) => item?.taskId === taskId)
          if (!isAnalysisTaskRunning(task) || !subscription.timer) favoriteTaskSubscriptions.delete(taskId)
        })
    },
    isLoadingDetail(item) {
      return this.detailLoadingKeys.includes(jobKey(item))
    },
    detailError(item) {
      return this.detailErrors[jobKey(item)] || ''
    },
    setDetailError(item, message) {
      const key = jobKey(item)
      if (!key) return
      this.detailErrors = { ...this.detailErrors, [key]: message || '' }
    },
    applyJobDetail(item, detail) {
      if (!detail || typeof detail !== 'object') return
      const key = jobKey(item)
      const merge = (list) => {
        const idx = list.findIndex((row) => jobKey(row) === key)
        if (idx >= 0) list.splice(idx, 1, { ...list[idx], ...detail })
      }
      merge(this.jobs)
      merge(this.favorites)
    },
    async loadJobDetail(item, url = '') {
      const revision = this.lifecycleRevision
      const key = jobKey(item)
      if (!key || this.detailLoadingKeys.includes(key)) return null
      const securityId = item?.securityId || item?.security_id || item?.encryptJobId || item?.encrypt_job_id || ''
      if (!securityId && !url) {
        this.setDetailError(item, '缺少 Boss 原岗位链接或 securityId，无法安全加载职位描述。')
        return null
      }
      this.detailLoadingKeys.push(key)
      this.detailErrors = { ...this.detailErrors, [key]: '' }
      try {
        const detail = await fetchJobDetail(securityId, url)
        if (revision !== this.lifecycleRevision) return null
        const completed = { ...item, ...(detail || {}), favoriteKey: key }
        const wasFavorite = this.isFavorite(item)
        this.applyJobDetail(item, detail)
        if (!jobDescription(completed)) {
          this.detailErrors = { ...this.detailErrors, [key]: '未获取到职位描述，请稍后重试或打开 Boss 原岗位查看。' }
        } else if (wasFavorite) {
          this.favoriteMutationVersion += 1
          this.favoriteMutationPendingCount += 1
          try {
            const saved = await saveFavoriteJob(completed)
            if (revision !== this.lifecycleRevision) return null
            this.favorites = Array.isArray(saved) ? saved : this.favorites
          } catch (persistError) {
            if (revision !== this.lifecycleRevision) return null
            this.detailErrors = {
              ...this.detailErrors,
              [key]: `职位描述已加载，但保存失败：${persistError?.message || '请稍后重试'}`,
            }
          } finally {
            if (revision === this.lifecycleRevision) {
              this.favoriteMutationPendingCount = Math.max(0, this.favoriteMutationPendingCount - 1)
              this.favoriteMutationVersion += 1
            }
          }
        }
        return detail
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        if (error?.authRequired) throw error
        this.detailErrors = { ...this.detailErrors, [key]: error?.message || '获取岗位详情失败' }
        return null
      } finally {
        if (revision === this.lifecycleRevision)
          this.detailLoadingKeys = this.detailLoadingKeys.filter((itemKey) => itemKey !== key)
      }
    },
  },
})
