import { defineStore } from 'pinia'
import { analyzeResume, deleteResume, getJobProfile, listResumes, saveJobProfile, syncBossOnlineResume, updateResumeParsed, uploadResume } from '../api/resume'

const CURRENT_RESUME_KEY = 'job-buddy.current-resume-id'

function saveCurrentResumeId(resumeId) {
  if (!resumeId) localStorage.removeItem(CURRENT_RESUME_KEY)
  else localStorage.setItem(CURRENT_RESUME_KEY, resumeId)
}

function readCurrentResumeId() {
  return localStorage.getItem(CURRENT_RESUME_KEY) || ''
}

export const useResumeStore = defineStore('resume', {
  state: () => ({ current: null, items: [], uploading: false, analyzing: false, syncingBoss: false, loading: false, error: '' }),
  actions: {
    async load() {
      this.loading = true
      try {
        this.items = await listResumes()
        const savedResumeId = readCurrentResumeId()
        const saved = savedResumeId ? this.items.find(item => item.resumeId === savedResumeId) : null
        if (saved) this.current = saved
        else if (!this.current && this.items.length) {
          this.current = this.items[0]
          saveCurrentResumeId(this.current.resumeId)
        }
      } finally {
        this.loading = false
      }
    },
    select(item) {
      this.current = item
      saveCurrentResumeId(item?.resumeId)
    },
    async saveParsed(resumeId, parsed) {
      this.error = ''
      try {
        const updated = await updateResumeParsed(resumeId, parsed)
        this.current = updated
        saveCurrentResumeId(updated?.resumeId)
        this.items = this.items.map(item => item.resumeId === resumeId ? updated : item)
        return updated
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
    async analyze(resumeId, sessionId) {
      this.analyzing = true
      this.error = ''
      try {
        const updated = await analyzeResume(resumeId, sessionId)
        this.current = updated
        saveCurrentResumeId(updated?.resumeId)
        this.items = this.items.map(item => item.resumeId === resumeId ? updated : item)
        return updated
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.analyzing = false
      }
    },
    async remove(resumeId) {
      this.error = ''
      try {
        await deleteResume(resumeId)
        const removingCurrent = this.current?.resumeId === resumeId
        this.items = this.items.filter(item => item.resumeId !== resumeId)
        if (removingCurrent) {
          this.current = this.items[0] || null
          saveCurrentResumeId(this.current?.resumeId)
        }
        await this.load()
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
    async loadProfile() {
      this.error = ''
      try {
        const profile = await getJobProfile()
        this.current = profile
        saveCurrentResumeId(profile?.resumeId)
        await this.load()
        const latest = this.items.find(item => item.resumeId === profile.resumeId)
        if (latest) {
          this.current = latest
          saveCurrentResumeId(latest.resumeId)
        }
        return this.current
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
    async saveProfile(parsed) {
      this.error = ''
      try {
        const profile = await saveJobProfile(parsed)
        this.current = profile
        saveCurrentResumeId(profile?.resumeId)
        await this.load()
        const latest = this.items.find(item => item.resumeId === profile.resumeId)
        if (latest) {
          this.current = latest
          saveCurrentResumeId(latest.resumeId)
        }
        return this.current
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
    async syncBossOnline() {
      this.syncingBoss = true
      this.error = ''
      try {
        const synced = await syncBossOnlineResume()
        this.current = synced
        saveCurrentResumeId(synced?.resumeId)
        await this.load()
        const latest = this.items.find(item => item.resumeId === synced.resumeId)
        if (latest) {
          this.current = latest
          saveCurrentResumeId(latest.resumeId)
        }
        return this.current
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.syncingBoss = false
      }
    },
    async upload(file, sessionId) {
      this.uploading = true
      this.error = ''
      try {
        this.current = await uploadResume(file, sessionId)
        saveCurrentResumeId(this.current?.resumeId)
        await this.load()
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.uploading = false
      }
    },
  },
})
