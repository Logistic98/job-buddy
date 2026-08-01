import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeManager from '../src/components/ResumeManager.vue'
import { useResumeStore } from '../src/stores/resume'
import { getWorkspaceState, saveWorkspaceState } from '../src/api/workspace'

vi.mock('../src/api/resume', () => ({
  deleteResume: vi.fn(),
  getAnalysisTask: vi.fn(),
  getJobProfile: vi.fn(),
  getResume: vi.fn(),
  latestResumeAnalysisTask: vi.fn(),
  listResumes: vi.fn(),
  resumeDownloadUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/download`),
  resumePreviewUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/preview`),
  resumeThumbnailUrl: vi.fn((resumeId) => `/api/resumes/${resumeId}/thumbnail`),
  saveJobProfile: vi.fn(),
  startResumeAnalysisTask: vi.fn(),
  streamAnalysisTask: vi.fn(),
  syncBossOnlineResume: vi.fn(),
  updateResumeParsed: vi.fn(),
  uploadResume: vi.fn(),
}))

vi.mock('../src/api/workspace', () => ({
  getWorkspaceState: vi.fn().mockResolvedValue({}),
  saveWorkspaceState: vi.fn().mockResolvedValue({}),
}))

function mountResumeManager() {
  return mount(ResumeManager, {
    global: {
      stubs: {
        Teleport: true,
      },
    },
  })
}

async function openTagEditor(wrapper) {
  const tagButton = wrapper.findAll('.resume-card-action').find((button) => button.text() === '标签')
  await tagButton.trigger('click')
}

describe('ResumeManager tags', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({})
    saveWorkspaceState.mockResolvedValue({})
    setActivePinia(createPinia())
  })

  it('shows all six allowed tags without folding the last tag into a counter', async () => {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-with-six-tags',
        originalName: '大模型应用开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-26T20:50:00+08:00',
        parsed: {
          labels: ['Agent', '大模型研发平台', 'RAG', 'LLM', '模型训练', '全栈'],
        },
      },
    ]

    const wrapper = mountResumeManager()
    await flushPromises()

    expect(wrapper.findAll('.resume-tags span').map((tag) => tag.text())).toEqual([
      'Agent',
      '大模型研发平台',
      'RAG',
      'LLM',
      '模型训练',
      '全栈',
    ])
    expect(wrapper.find('.resume-tags em').exists()).toBe(false)
  })

  it('adds multiple tag drafts without closing and saves them together', async () => {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-for-tag-editing',
        originalName: 'Java开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-26T20:50:00+08:00',
        parsed: { labels: ['Java'] },
      },
    ]
    const saveParsed = vi.spyOn(resume, 'saveParsed').mockResolvedValue({})

    const wrapper = mountResumeManager()
    await flushPromises()
    await openTagEditor(wrapper)

    const modal = wrapper.find('.resume-tag-modal')
    await modal.find('.resume-tag-input input').setValue('Python, RAG 大模型')
    const addButton = wrapper.find('.resume-tag-input-row button')
    expect(addButton.attributes('disabled')).toBeUndefined()
    await addButton.trigger('click')

    expect(wrapper.find('.resume-tag-modal').exists()).toBe(true)
    expect(wrapper.findAll('.editable-tag').map((tag) => tag.text().replace('×', ''))).toEqual([
      'Java',
      'Python',
      'RAG',
      '大模型',
    ])

    await wrapper.find('.resume-tag-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(saveParsed).toHaveBeenCalledTimes(1)
    expect(saveParsed).toHaveBeenCalledWith(
      'resume-for-tag-editing',
      expect.objectContaining({
        labels: ['Java', 'Python', 'RAG', '大模型'],
        manageTags: ['Java', 'Python', 'RAG', '大模型'],
      }),
    )
    expect(wrapper.find('.resume-tag-modal').exists()).toBe(false)
  })

  it('toggles common tags while keeping the editor open', async () => {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-for-suggestions',
        originalName: 'Agent开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-26T20:50:00+08:00',
        parsed: { labels: [] },
      },
    ]

    const wrapper = mountResumeManager()
    await flushPromises()
    await openTagEditor(wrapper)

    const suggestions = wrapper.findAll('.resume-tag-suggestions button')
    expect(suggestions.map((suggestion) => suggestion.text())).toEqual([
      '后端',
      'Agent',
      'RAG',
      '大数据处理',
      'AI工程化',
      'AI原生',
      'AI算法',
      'Harness',
      'LLM',
      '模型训练',
      '基础设施',
    ])
    await suggestions[0].trigger('click')
    await suggestions[1].trigger('click')

    expect(wrapper.find('.resume-tag-modal').exists()).toBe(true)
    expect(wrapper.findAll('.editable-tag').map((tag) => tag.text().replace('×', ''))).toEqual(['后端', 'Agent'])
    const updatedSuggestions = wrapper.findAll('.resume-tag-suggestions button')
    expect(updatedSuggestions[0].attributes('aria-pressed')).toBe('true')
    expect(updatedSuggestions[1].attributes('aria-pressed')).toBe('true')
  })
})

describe('ResumeManager versions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({})
    saveWorkspaceState.mockResolvedValue({})
    setActivePinia(createPinia())
  })

  it('keeps an existing fallback version stable when another resume is added on the same day', async () => {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-original',
        originalName: '原始简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-28T10:00:00+08:00',
        parsed: {},
      },
    ]

    const wrapper = mountResumeManager()
    await flushPromises()
    expect(wrapper.findAll('.resume-meta-row span')[1].text()).toBe('版本 20260728_001')

    resume.items.unshift({
      resumeId: 'resume-new',
      originalName: '新增简历.pdf',
      suffix: 'pdf',
      uploadedAt: '2026-07-28T11:00:00+08:00',
      parsed: {},
    })
    await flushPromises()

    const originalCard = wrapper
      .findAll('.resume-manage-card')
      .find((card) => card.find('h2').text() === '原始简历.pdf')
    expect(originalCard.findAll('.resume-meta-row span')[1].text()).toBe('版本 20260728_001')
  })
})

describe('ResumeManager thumbnails', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({})
    saveWorkspaceState.mockResolvedValue({})
    setActivePinia(createPinia())
  })

  it('shows a visible generation state and allows failed thumbnails to retry', async () => {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-thumbnail',
        originalName: '大模型应用开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-08-02T03:13:00+08:00',
        parsed: {},
      },
    ]

    const wrapper = mountResumeManager()
    await flushPromises()

    expect(wrapper.find('.resume-thumb-status').text()).toContain('正在生成预览')
    expect(wrapper.find('.resume-thumb-image').attributes('loading')).toBe('eager')

    await wrapper.find('.resume-thumb-image').trigger('error')
    expect(wrapper.find('.resume-thumb-retry').text()).toBe('重新加载预览')

    await wrapper.find('.resume-thumb-retry').trigger('click')
    expect(wrapper.find('.resume-thumb-status').exists()).toBe(true)
    expect(wrapper.find('.resume-thumb-image').attributes('src')).toContain('retry=')

    await wrapper.find('.resume-thumb-image').trigger('load')
    expect(wrapper.find('.resume-thumb-status').exists()).toBe(false)
    expect(wrapper.find('.resume-thumb-retry').exists()).toBe(false)
  })
})

describe('ResumeManager folder maintenance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({ folders: ['大模型应用开发', '后端开发'] })
    saveWorkspaceState.mockResolvedValue({})
    setActivePinia(createPinia())
  })

  function prepareResumes() {
    const resume = useResumeStore()
    resume.loaded = true
    resume.items = [
      {
        resumeId: 'resume-agent',
        originalName: 'Agent开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-26T20:50:00+08:00',
        parsed: { folder: '大模型应用开发', resumeFolder: '大模型应用开发' },
      },
      {
        resumeId: 'resume-java',
        originalName: 'Java开发简历.pdf',
        suffix: 'pdf',
        uploadedAt: '2026-07-26T20:50:00+08:00',
        parsed: { folder: '后端开发', resumeFolder: '后端开发' },
      },
    ]
    return resume
  }

  it('opens folder maintenance and creates a group without closing the dialog', async () => {
    prepareResumes()
    const wrapper = mountResumeManager()
    await flushPromises()

    await wrapper.find('.resume-manager-actions .secondary-btn').trigger('click')
    expect(wrapper.find('#resume-folder-manager-title').text()).toBe('简历分组维护')

    await wrapper.find('#resume-folder-name').setValue('架构方向')
    await wrapper.find('.resume-folder-create-row button').trigger('click')
    await flushPromises()

    expect(saveWorkspaceState).toHaveBeenCalledWith('resume.folders', {
      folders: ['大模型应用开发', '后端开发', '架构方向'],
    })
    expect(wrapper.find('.resume-folder-manager-modal').exists()).toBe(true)
    expect(wrapper.findAll('.resume-folder-maintenance-item strong').map((item) => item.text())).toContain('架构方向')
  })

  it('renames a group and updates every resume assigned to it', async () => {
    const resume = prepareResumes()
    const saveParsed = vi.spyOn(resume, 'saveParsed').mockResolvedValue({})
    const wrapper = mountResumeManager()
    await flushPromises()
    await wrapper.find('.resume-manager-actions .secondary-btn').trigger('click')

    const target = wrapper
      .findAll('.resume-folder-maintenance-item')
      .find((item) => item.find('strong').text() === '大模型应用开发')
    await target
      .findAll('button')
      .find((button) => button.text() === '重命名')
      .trigger('click')
    const renameTarget = wrapper.findAll('.resume-folder-maintenance-item').find((item) => item.find('input').exists())
    await renameTarget.find('input').setValue('AI应用开发')
    await renameTarget
      .findAll('button')
      .find((button) => button.text() === '保存')
      .trigger('click')
    await flushPromises()

    expect(saveParsed).toHaveBeenCalledWith(
      'resume-agent',
      expect.objectContaining({ folder: 'AI应用开发', resumeFolder: 'AI应用开发' }),
    )
    expect(saveWorkspaceState).toHaveBeenCalledWith('resume.folders', {
      folders: ['AI应用开发', '后端开发'],
    })
  })

  it('deletes a group after confirmation and moves its resumes to ungrouped', async () => {
    const resume = prepareResumes()
    const saveParsed = vi.spyOn(resume, 'saveParsed').mockResolvedValue({})
    const wrapper = mountResumeManager()
    await flushPromises()
    await wrapper.find('.resume-manager-actions .secondary-btn').trigger('click')

    const target = wrapper
      .findAll('.resume-folder-maintenance-item')
      .find((item) => item.find('strong').text() === '大模型应用开发')
    await target
      .findAll('button')
      .find((button) => button.text() === '删除')
      .trigger('click')

    expect(wrapper.find('#resume-folder-delete-description').text()).toContain(
      '组内 1 份简历将移至“未分组”，简历文件不会被删除。',
    )
    await wrapper.find('.resume-delete-actions .danger-btn').trigger('click')
    await flushPromises()

    expect(saveParsed).toHaveBeenCalledWith('resume-agent', expect.objectContaining({ folder: '', resumeFolder: '' }))
    expect(saveWorkspaceState).toHaveBeenCalledWith('resume.folders', { folders: ['后端开发'] })
    expect(wrapper.find('#resume-folder-delete-title').exists()).toBe(false)
  })
})
