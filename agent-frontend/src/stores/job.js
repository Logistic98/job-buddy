import { defineStore } from 'pinia'
import { analyzeFavoriteJob, deleteFavoriteJob, fetchJobDetail, listFavoriteJobs, saveFavoriteJob } from '../api/jobs'

const FAVORITES_KEY = 'job-buddy.favorite-jobs'

function jobKey(item) {
  return String(item?.favoriteKey || item?.securityId || item?.id || item?.jobId || item?.encryptJobId || `${item?.jobName || item?.title || 'job'}_${item?.brandName || item?.companyName || ''}`)
}

function jobDescription(item) {
  return String(item?.jobDescription || item?.description || item?.postDescription || item?.jobDesc || item?.jobSecText || item?.detailText || '').trim()
}

function readFavorites() {
  try {
    const rows = JSON.parse(localStorage.getItem(FAVORITES_KEY) || '[]')
    return Array.isArray(rows) ? rows : []
  } catch (_) {
    return []
  }
}

export const useJobStore = defineStore('job', {
  state: () => ({ jobs: [], match: null, favorites: readFavorites(), favoriteLoading: false, favoriteError: '', analyzingFavoriteKeys: [], detailLoadingKeys: [], detailErrors: {} }),
  getters: {
    favoriteCount: state => state.favorites.length,
  },
  actions: {
    setJobs(jobs) { this.jobs = Array.isArray(jobs) ? jobs : [] },
    setMatch(match) { this.match = match || null },
    isFavorite(item) {
      const key = jobKey(item)
      return this.favorites.some(row => jobKey(row) === key)
    },
    async loadFavorites() {
      this.favoriteLoading = true
      try {
        const localRows = readFavorites()
        let rows = await listFavoriteJobs()
        rows = Array.isArray(rows) ? rows : []
        if (!rows.length && localRows.length) {
          for (const item of localRows) rows = await saveFavoriteJob({ ...item, favoriteKey: jobKey(item) })
        }
        this.favorites = rows
        this.persistFavorites()
        this.favoriteError = ''
      } catch (error) {
        this.favoriteError = error?.message || '加载岗位收藏失败'
      } finally {
        this.favoriteLoading = false
      }
    },
    async addFavorite(item) {
      if (!item) return
      const key = jobKey(item)
      const existing = this.favorites.findIndex(row => jobKey(row) === key)
      const merged = { ...(existing >= 0 ? this.favorites[existing] : {}), ...item, favoriteKey: key, favoritedAt: existing >= 0 ? this.favorites[existing].favoritedAt : new Date().toISOString(), updatedAt: new Date().toISOString() }
      if (existing >= 0) this.favorites.splice(existing, 1, merged)
      else this.favorites.unshift(merged)
      this.persistFavorites()
      try {
        this.favorites = await saveFavoriteJob(merged)
        this.persistFavorites()
        this.favoriteError = ''
      } catch (error) {
        this.favoriteError = error?.message || '保存岗位收藏失败'
      }
    },
    async removeFavorite(item) {
      const key = jobKey(item)
      const before = [...this.favorites]
      this.favorites = this.favorites.filter(row => jobKey(row) !== key)
      this.persistFavorites()
      try {
        this.favorites = await deleteFavoriteJob(key)
        this.persistFavorites()
        this.favoriteError = ''
      } catch (error) {
        this.favorites = before
        this.persistFavorites()
        this.favoriteError = error?.message || '移出岗位收藏失败'
      }
    },
    async toggleFavorite(item) {
      if (this.isFavorite(item)) await this.removeFavorite(item)
      else await this.addFavorite(item)
    },
    isAnalyzingFavorite(item) {
      const key = jobKey(item)
      return this.analyzingFavoriteKeys.includes(key)
    },
    async analyzeFavorite(item, resumeId = '') {
      const key = jobKey(item)
      if (!key || this.analyzingFavoriteKeys.includes(key)) return null
      this.analyzingFavoriteKeys.push(key)
      this.favoriteError = ''
      try {
        const updated = await analyzeFavoriteJob(key, resumeId)
        const idx = this.favorites.findIndex(row => jobKey(row) === key)
        if (idx >= 0) this.favorites.splice(idx, 1, updated)
        else if (updated) this.favorites.unshift(updated)
        this.persistFavorites()
        return updated
      } catch (error) {
        this.favoriteError = error?.message || '岗位分析失败'
        throw error
      } finally {
        this.analyzingFavoriteKeys = this.analyzingFavoriteKeys.filter(itemKey => itemKey !== key)
      }
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
      const merge = list => {
        const idx = list.findIndex(row => jobKey(row) === key)
        if (idx >= 0) list.splice(idx, 1, { ...list[idx], ...detail })
      }
      merge(this.jobs)
      merge(this.favorites)
      this.persistFavorites()
    },
    async loadJobDetail(item, url = '') {
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
        this.applyJobDetail(item, detail)
        if (!jobDescription({ ...item, ...(detail || {}) })) {
          this.detailErrors = { ...this.detailErrors, [key]: '未获取到职位描述，请稍后重试或打开 Boss 原岗位查看。' }
        }
        return detail
      } catch (error) {
        if (error?.authRequired) throw error
        this.detailErrors = { ...this.detailErrors, [key]: error?.message || '获取岗位详情失败' }
        return null
      } finally {
        this.detailLoadingKeys = this.detailLoadingKeys.filter(itemKey => itemKey !== key)
      }
    },
    persistFavorites() {
      localStorage.setItem(FAVORITES_KEY, JSON.stringify(this.favorites))
    },
  },
})
