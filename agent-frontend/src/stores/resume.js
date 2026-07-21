import { defineStore } from 'pinia'
import {
  deleteResume,
  getAnalysisTask,
  getJobProfile,
  getResume,
  latestResumeAnalysisTask,
  listResumes,
  saveJobProfile,
  startResumeAnalysisTask,
  streamAnalysisTask,
  syncBossOnlineResume,
  updateResumeParsed,
  uploadResume,
} from '../api/resume'
import { isAnalysisTaskRunning } from '../api/analysisTasks'
import { getWorkspaceState, saveWorkspaceState } from '../api/workspace'

const CURRENT_RESUME_STATE = 'resume.current'
let loadRequest = null
const resumeTaskSubscriptions = new Map()
const RESUME_TASK_POLL_DELAY_MS = Number.parseInt(
  String(import.meta.env.VITE_ANALYSIS_TASK_POLL_DELAY_MS || '1500'),
  10,
)

function mergeResumeAnalysisResult(current, incoming) {
  if (!incoming || typeof incoming !== 'object') return current
  const currentParsed = current?.parsed || {}
  const incomingParsed = incoming.parsed || {}
  return {
    ...(current || {}),
    ...incoming,
    parsed: {
      ...currentParsed,
      ...incomingParsed,
      analysis: {
        ...(currentParsed.analysis || {}),
        ...(incomingParsed.analysis || {}),
      },
    },
  }
}

export const useResumeStore = defineStore('resume', {
  state: () => ({
    current: null,
    jobProfile: null,
    items: [],
    loaded: false,
    uploading: false,
    analysisTasks: {},
    syncingBoss: false,
    loading: false,
    error: '',
    lifecycleRevision: 0,
  }),
  actions: {
    disposeForAuthChange() {
      const nextRevision = this.lifecycleRevision + 1
      for (const subscription of resumeTaskSubscriptions.values()) {
        try {
          subscription.controller?.abort()
        } catch (_) {}
        if (subscription.timer) window.clearTimeout(subscription.timer)
      }
      resumeTaskSubscriptions.clear()
      loadRequest = null
      this.$reset()
      this.lifecycleRevision = nextRevision
    },
    async persistCurrent() {
      await saveWorkspaceState(CURRENT_RESUME_STATE, { resumeId: this.current?.resumeId || '' })
    },
    async load(force = false) {
      if (!force && this.loaded) return this.items
      const revision = this.lifecycleRevision
      if (loadRequest?.revision === revision) return loadRequest.promise
      this.loading = true
      let request
      request = (async () => {
        const [items, selection] = await Promise.all([listResumes(), getWorkspaceState(CURRENT_RESUME_STATE)])
        if (revision !== this.lifecycleRevision) return []
        this.items = Array.isArray(items)
          ? items.filter((item) => String(item?.suffix || '').toLowerCase() === 'pdf')
          : []
        const saved = selection?.resumeId ? this.items.find((item) => item.resumeId === selection.resumeId) : null
        this.current = saved || this.items[0] || null
        if (this.current && !saved) await this.persistCurrent()
        if (this.current?.resumeId) {
          await this.hydrateCurrent(this.current.resumeId, revision)
          if (revision !== this.lifecycleRevision) return []
          this.restoreAnalysis(this.current.resumeId, revision).catch(() => {})
        }
        this.loaded = true
        return this.items
      })()
      loadRequest = { revision, promise: request }
      try {
        return await request
      } finally {
        if (loadRequest?.promise === request) loadRequest = null
        if (revision === this.lifecycleRevision) this.loading = false
      }
    },
    async select(item) {
      const revision = this.lifecycleRevision
      this.current = item
      this.persistCurrent().catch((error) => {
        this.error = error?.message || '当前简历保存失败'
      })
      if (item?.resumeId) {
        await this.hydrateCurrent(item.resumeId, revision)
        if (revision === this.lifecycleRevision) this.restoreAnalysis(item.resumeId, revision).catch(() => {})
      }
    },
    async hydrateCurrent(resumeId, expectedRevision = this.lifecycleRevision) {
      try {
        const detail = await getResume(resumeId)
        if (expectedRevision !== this.lifecycleRevision) return null
        const index = this.items.findIndex((item) => item.resumeId === resumeId)
        if (index >= 0) this.items[index] = { ...this.items[index], ...detail }
        if (this.current?.resumeId === resumeId) this.current = index >= 0 ? this.items[index] : detail
        return detail
      } catch (_) {
        return this.current?.resumeId === resumeId ? this.current : null
      }
    },
    async saveParsed(resumeId, parsed) {
      const revision = this.lifecycleRevision
      this.error = ''
      try {
        const detail = await getResume(resumeId)
        if (revision !== this.lifecycleRevision) return null
        const mergedParsed = { ...(detail?.parsed || {}), ...(parsed || {}) }
        const updated = await updateResumeParsed(resumeId, mergedParsed)
        if (revision !== this.lifecycleRevision) return null
        this.current = updated
        await this.persistCurrent()
        this.items = this.items.map((item) => (item.resumeId === resumeId ? updated : item))
        return updated
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.error = error.message
        throw error
      }
    },
    analysisTask(resumeId) {
      return this.analysisTasks[resumeId] || null
    },
    isAnalyzing(resumeId) {
      return isAnalysisTaskRunning(this.analysisTask(resumeId))
    },
    analysisStage(resumeId) {
      const task = this.analysisTask(resumeId)
      return task?.message || (task?.status === 'failed' ? task.errorMessage : '') || ''
    },
    applyAnalysisTask(task) {
      if (!task?.resourceKey) return
      this.analysisTasks = { ...this.analysisTasks, [task.resourceKey]: task }
      const visibleResult =
        task.status === 'succeeded' && task.result && Object.keys(task.result).length ? task.result : task.partialResult
      if (visibleResult && Object.keys(visibleResult).length) {
        this.items = this.items.map((item) =>
          item.resumeId === task.resourceKey ? mergeResumeAnalysisResult(item, visibleResult) : item,
        )
        if (this.current?.resumeId === task.resourceKey) {
          this.current = mergeResumeAnalysisResult(this.current, visibleResult)
        }
      }
      if (task.status === 'failed') this.error = task.errorMessage || '简历分析失败'
    },
    async restoreAnalysis(resumeId, expectedRevision = this.lifecycleRevision) {
      if (!resumeId) return null
      const task = await latestResumeAnalysisTask(resumeId)
      if (expectedRevision !== this.lifecycleRevision || !task) return null
      this.applyAnalysisTask(task)
      if (isAnalysisTaskRunning(task)) this.watchAnalysisTask(task.taskId)
      return task
    },
    async analyze(resumeId, sessionId) {
      if (!resumeId || this.isAnalyzing(resumeId)) return this.analysisTask(resumeId)
      const revision = this.lifecycleRevision
      this.error = ''
      try {
        const task = await startResumeAnalysisTask(resumeId, sessionId)
        if (revision !== this.lifecycleRevision) return null
        this.applyAnalysisTask(task)
        this.watchAnalysisTask(task.taskId)
        return task
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.error = error.message
        throw error
      }
    },
    watchAnalysisTask(taskId) {
      if (!taskId || resumeTaskSubscriptions.has(taskId)) return
      const revision = this.lifecycleRevision
      const subscription = { controller: new AbortController(), timer: null }
      resumeTaskSubscriptions.set(taskId, subscription)
      const isCurrent = () =>
        revision === this.lifecycleRevision && resumeTaskSubscriptions.get(taskId) === subscription
      const apply = (task) => {
        if (isCurrent()) this.applyAnalysisTask(task)
      }
      const applyPartial = (task) => {
        if (!isCurrent()) return
        this.applyAnalysisTask(task)
        // partial_result 在后端已先写入 resume_record；再拉一次详情作为一致性兜底，
        // 避免列表摘要或已失效组件实例覆盖 SSE 中的 parsed.analysis。
        if (task?.resourceKey) this.hydrateCurrent(task.resourceKey).catch(() => {})
      }
      streamAnalysisTask(
        taskId,
        { snapshot: apply, progress: apply, partial_result: applyPartial, result: apply, error: apply },
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
                Number.isFinite(RESUME_TASK_POLL_DELAY_MS) && RESUME_TASK_POLL_DELAY_MS > 0
                  ? RESUME_TASK_POLL_DELAY_MS
                  : 1500
              subscription.timer = window.setTimeout(() => {
                if (!isCurrent()) return
                resumeTaskSubscriptions.delete(taskId)
                this.watchAnalysisTask(taskId)
              }, delay)
            }
          } catch (_) {
            // SSE 断开不改变服务端任务终态，稍后返回页面时会按简历恢复。
          }
        })
        .finally(() => {
          if (!isCurrent()) return
          const task = Object.values(this.analysisTasks).find((item) => item?.taskId === taskId)
          if (!isAnalysisTaskRunning(task) || !subscription.timer) resumeTaskSubscriptions.delete(taskId)
        })
    },
    async remove(resumeId) {
      const revision = this.lifecycleRevision
      this.error = ''
      try {
        await deleteResume(resumeId)
        if (revision !== this.lifecycleRevision) return false
        const removingCurrent = this.current?.resumeId === resumeId
        this.items = this.items.filter((item) => item.resumeId !== resumeId)
        if (removingCurrent) {
          this.current = this.items[0] || null
          await this.persistCurrent()
        }
        await this.load(true)
        return true
      } catch (error) {
        if (revision !== this.lifecycleRevision) return false
        this.error = error.message
        throw error
      }
    },
    async applyProfile(operation, expectedRevision = this.lifecycleRevision) {
      const profile = await operation()
      if (expectedRevision !== this.lifecycleRevision) return null
      this.jobProfile = profile
      return profile
    },
    async loadProfile() {
      const revision = this.lifecycleRevision
      this.error = ''
      try {
        return await this.applyProfile(() => getJobProfile(), revision)
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.error = error.message
        throw error
      }
    },
    async saveProfile(parsed) {
      const revision = this.lifecycleRevision
      this.error = ''
      try {
        return await this.applyProfile(() => saveJobProfile(parsed), revision)
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.error = error.message
        throw error
      }
    },
    async syncBossOnline() {
      const revision = this.lifecycleRevision
      this.syncingBoss = true
      this.error = ''
      try {
        return await this.applyProfile(() => syncBossOnlineResume(), revision)
      } catch (error) {
        if (revision === this.lifecycleRevision) this.error = error.message
        else return null
        throw error
      } finally {
        if (revision === this.lifecycleRevision) this.syncingBoss = false
      }
    },
    async upload(file, sessionId) {
      const revision = this.lifecycleRevision
      this.uploading = true
      this.error = ''
      try {
        const uploaded = await uploadResume(file, sessionId)
        if (revision !== this.lifecycleRevision) return null
        this.current = uploaded
        await this.persistCurrent()
        if (revision !== this.lifecycleRevision) return null
        await this.load(true)
      } catch (error) {
        if (revision !== this.lifecycleRevision) return null
        this.error = error.message
        throw error
      } finally {
        if (revision === this.lifecycleRevision) this.uploading = false
      }
    },
  },
})
