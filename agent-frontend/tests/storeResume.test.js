import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/resume', () => ({
  analyzeResume: vi.fn(),
  startResumeAnalysisTask: vi.fn(),
  latestResumeAnalysisTask: vi.fn(async () => null),
  getAnalysisTask: vi.fn(async () => null),
  streamAnalysisTask: vi.fn(async () => {}),
  deleteResume: vi.fn(),
  getJobProfile: vi.fn(),
  getResume: vi.fn(),
  listResumes: vi.fn(),
  saveJobProfile: vi.fn(),
  syncBossOnlineResume: vi.fn(),
  updateResumeParsed: vi.fn(),
  uploadResume: vi.fn(),
}))
vi.mock('../src/api/workspace', () => ({
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
}))

import {
  getJobProfile,
  getResume,
  listResumes,
  startResumeAnalysisTask,
  streamAnalysisTask,
  updateResumeParsed,
} from '../src/api/resume'
import { getWorkspaceState, saveWorkspaceState } from '../src/api/workspace'
import { useResumeStore } from '../src/stores/resume'

describe('resume store loading', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({ resumeId: 'r1' })
    getResume.mockResolvedValue({ resumeId: 'r1', suffix: 'pdf', parsed: { analysis: { overall_score: 82 } } })
    saveWorkspaceState.mockResolvedValue({})
  })

  it('deduplicates concurrent list loads and reuses loaded data', async () => {
    listResumes.mockResolvedValue([{ resumeId: 'r1', suffix: 'pdf' }])
    const store = useResumeStore()

    await Promise.all([store.load(), store.load()])
    await store.load()

    expect(listResumes).toHaveBeenCalledTimes(1)
    expect(getWorkspaceState).toHaveBeenCalledTimes(1)
    expect(getResume).toHaveBeenCalledTimes(1)
    expect(store.current.parsed.analysis.overall_score).toBe(82)
  })

  it('drops a late resume load after an authentication change', async () => {
    let resolveItems
    listResumes.mockReturnValue(
      new Promise((resolve) => {
        resolveItems = resolve
      }),
    )
    const store = useResumeStore()
    const loading = store.load()
    store.disposeForAuthChange()
    resolveItems([{ resumeId: 'account-a', suffix: 'pdf' }])
    await loading
    expect(store.items).toEqual([])
    expect(store.current).toBeNull()
    expect(store.loading).toBe(false)
  })

  it('keeps job profile separate from the current analysis resume', async () => {
    listResumes.mockResolvedValue([{ resumeId: 'r1', suffix: 'pdf' }])
    getJobProfile.mockResolvedValue({ resumeId: 'profile-1', parsed: { source: { type: 'job_profile' } } })
    const store = useResumeStore()
    await store.load()

    await store.loadProfile()

    expect(store.current.resumeId).toBe('r1')
    expect(store.jobProfile.resumeId).toBe('profile-1')
    expect(saveWorkspaceState).not.toHaveBeenCalled()
  })

  it('starts resume analysis as a task and applies a terminal result without blocking', async () => {
    const analyzed = { resumeId: 'r1', suffix: 'pdf', parsed: { analysis: { overall_score: 91 } } }
    startResumeAnalysisTask.mockResolvedValue({
      taskId: 'resume-task-1',
      resourceKey: 'r1',
      status: 'succeeded',
      stage: 'completed',
      result: analyzed,
    })
    const store = useResumeStore()
    store.current = { resumeId: 'r1', suffix: 'pdf' }
    store.items = [store.current]

    const task = await store.analyze('r1', 'session-1')

    expect(startResumeAnalysisTask).toHaveBeenCalledWith('r1', 'session-1')
    expect(task.taskId).toBe('resume-task-1')
    expect(store.current.parsed.analysis.overall_score).toBe(91)
    expect(store.isAnalyzing('r1')).toBe(false)
  })

  it('applies partial_result SSE data immediately while the task remains running', async () => {
    const partialDetail = {
      resumeId: 'r1',
      suffix: 'pdf',
      parseStatus: 'success',
      parsed: { analysis: { overall_score: 77, summary: 'SSE 首组总体判断' } },
    }
    getResume.mockResolvedValue(partialDetail)
    startResumeAnalysisTask.mockResolvedValue({
      taskId: 'resume-sse-partial',
      resourceKey: 'r1',
      status: 'running',
      stage: 'analyzing',
      result: {},
      partialResult: {},
    })
    streamAnalysisTask.mockImplementationOnce(async (_taskId, handlers) => {
      handlers.partial_result({
        taskId: 'resume-sse-partial',
        resourceKey: 'r1',
        status: 'running',
        stage: 'partial_overview',
        message: '总体判断、优势与风险已生成',
        result: {},
        partialResult: partialDetail,
      })
    })
    const store = useResumeStore()
    store.current = { resumeId: 'r1', suffix: 'pdf', parsed: {} }
    store.items = [store.current]

    await store.analyze('r1', 'session-1')
    await vi.waitFor(() => expect(store.current.parsed.analysis.overall_score).toBe(77))

    expect(store.isAnalyzing('r1')).toBe(true)
    expect(store.current.parsed.analysis.summary).toBe('SSE 首组总体判断')
    expect(store.analysisStage('r1')).toBe('总体判断、优势与风险已生成')
  })

  it('renders a resume partial result while later sections are still running', () => {
    const store = useResumeStore()
    store.current = { resumeId: 'r1', suffix: 'pdf' }
    store.items = [store.current]

    store.applyAnalysisTask({
      taskId: 'resume-partial',
      resourceKey: 'r1',
      status: 'running',
      stage: 'partial_overview',
      result: {},
      partialResult: {
        resumeId: 'r1',
        suffix: 'pdf',
        parsed: { analysis: { overall_score: 79, summary: '首组总体判断' } },
      },
    })

    expect(store.isAnalyzing('r1')).toBe(true)
    expect(store.current.parsed.analysis.overall_score).toBe(79)
    expect(store.current.parsed.analysis.summary).toBe('首组总体判断')
  })

  it('drops a late account-A load after disposeForAuthChange', async () => {
    let resolveList
    listResumes.mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve
      }),
    )
    const store = useResumeStore()

    const loading = store.load()
    store.disposeForAuthChange()
    resolveList([{ resumeId: 'account-a-resume', suffix: 'pdf' }])
    await loading

    expect(store.items).toEqual([])
    expect(store.current).toBeNull()
    expect(store.loaded).toBe(false)
  })

  it('aborts an account-A analysis stream during authentication change', async () => {
    let streamSignal
    startResumeAnalysisTask.mockResolvedValue({
      taskId: 'account-a-task',
      resourceKey: 'r1',
      status: 'running',
      stage: 'analyzing',
      result: {},
      partialResult: {},
    })
    streamAnalysisTask.mockImplementation((_taskId, _handlers, signal) => {
      streamSignal = signal
      return new Promise(() => {})
    })
    const store = useResumeStore()
    store.current = { resumeId: 'r1', suffix: 'pdf' }
    store.items = [store.current]

    await store.analyze('r1', 'session-a')
    store.disposeForAuthChange()

    expect(streamSignal.aborted).toBe(true)
    expect(store.analysisTasks).toEqual({})
  })

  it('merges management metadata with the full parsed detail before saving', async () => {
    const fullDetail = {
      resumeId: 'r1',
      suffix: 'pdf',
      parsed: { summary: '完整摘要', skills: ['Java'], folder: '原分组' },
    }
    getResume.mockResolvedValue(fullDetail)
    updateResumeParsed.mockResolvedValue({
      ...fullDetail,
      parsed: { ...fullDetail.parsed, folder: '新分组', resumeFolder: '新分组' },
    })
    const store = useResumeStore()

    await store.saveParsed('r1', { folder: '新分组', resumeFolder: '新分组' })

    expect(updateResumeParsed).toHaveBeenCalledWith('r1', {
      summary: '完整摘要',
      skills: ['Java'],
      folder: '新分组',
      resumeFolder: '新分组',
    })
  })
})
