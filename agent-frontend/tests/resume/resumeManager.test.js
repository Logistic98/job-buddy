import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeManager from '../../src/components/ResumeManager.vue'
import { useResumeStore } from '../../src/stores/resume'
import { getWorkspaceState, saveWorkspaceState } from '../../src/api/workspace'

vi.mock('../../src/api/resume', () => ({
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

vi.mock('../../src/api/workspace', () => ({
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

describe('ResumeManager file and version actions', () => {
  let resume
  let wrapper
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    getWorkspaceState.mockResolvedValue({ folders: [] })
    saveWorkspaceState.mockResolvedValue({})
    resume = useResumeStore()
    resume.loaded = true
    resume.items = [{ resumeId: 'r1', originalName: 'resume.pdf', suffix: 'pdf', parsed: {} }]
  })
  afterEach(() => {
    wrapper?.unmount()
    vi.restoreAllMocks()
  })
  async function action(label) {
    await wrapper
      .findAll('.resume-card-action')
      .find((button) => button.text() === label)
      .trigger('click')
  }

  it('validates upload format and forwards a valid file', async () => {
    const upload = vi.spyOn(resume, 'upload').mockResolvedValue({})
    wrapper = mountResumeManager()
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['x'], 'invalid.txt', { type: 'text/plain' })],
    })
    await input.trigger('change')
    expect(upload).not.toHaveBeenCalled()
    expect(wrapper.get('.manager-error').text()).toContain('PDF')
    const file = new File(['pdf'], 'resume.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    expect(upload).toHaveBeenCalledWith(file, expect.any(String))
    Object.defineProperty(input.element, 'files', { configurable: true, value: [] })
    await input.trigger('change')
    expect(upload).toHaveBeenCalledOnce()
  })

  it('retries failed thumbnail loading with a cache-busting URL', async () => {
    wrapper = mountResumeManager()
    await flushPromises()
    await wrapper.get('.resume-thumb-image').trigger('error')
    expect(wrapper.find('.resume-thumb.is-error').exists()).toBe(true)
    await wrapper.get('.resume-thumb-retry').trigger('click')
    expect(wrapper.get('.resume-thumb-image').attributes('src')).toMatch(/\?retry=\d+/)
    await wrapper.get('.resume-thumb-image').trigger('load')
    expect(wrapper.find('.resume-thumb.is-loaded').exists()).toBe(true)
  })

  it('selects cards and opens the PDF preview', async () => {
    const select = vi.spyOn(resume, 'select').mockResolvedValue({})
    wrapper = mountResumeManager()
    await flushPromises()
    await wrapper.get('.resume-manage-card').trigger('click')
    expect(select).toHaveBeenCalledWith(resume.items[0])
    await action('预览')
    expect(wrapper.html()).toContain('/api/resumes/r1/preview')
    expect(wrapper.text()).toContain('未知时间')
  })

  it('persists an edited version with existing metadata', async () => {
    resume.items[0].parsed = { labels: ['Java'], version: 'old' }
    const save = vi.spyOn(resume, 'saveParsed').mockResolvedValue({})
    wrapper = mountResumeManager()
    await flushPromises()
    await action('版本')
    await wrapper.get('.resume-meta-form input').setValue('release-2')
    await wrapper.get('.resume-tag-actions .primary-btn').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith('r1', { labels: ['Java'], version: 'release-2', resumeVersion: 'release-2' })
    expect(wrapper.find('.resume-tag-modal').exists()).toBe(false)
  })

  it('keeps the version dialog open after persistence fails and allows retry', async () => {
    const save = vi.spyOn(resume, 'saveParsed').mockRejectedValueOnce(new Error('save offline')).mockResolvedValue({})
    wrapper = mountResumeManager()
    await flushPromises()
    await action('版本')
    await wrapper.get('.resume-meta-form input').setValue('')
    await wrapper.get('.resume-tag-actions .primary-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.resume-tag-modal').exists()).toBe(true)
    expect(wrapper.get('.resume-tag-actions .primary-btn').attributes('disabled')).toBeUndefined()
    await wrapper.get('.resume-tag-actions .primary-btn').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledTimes(2)
    expect(save.mock.calls[1][1].version).toMatch(/^\d{8}_001$/)
    expect(wrapper.find('.resume-tag-modal').exists()).toBe(false)
  })
})

describe('ResumeManager folder validation and recovery', () => {
  let wrapper
  let resume
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getWorkspaceState.mockResolvedValue({ folders: ['Alpha', 'Beta'] })
    saveWorkspaceState.mockResolvedValue({})
    resume = useResumeStore()
    resume.loaded = true
    resume.items = [{ resumeId: 'r1', suffix: 'pdf', originalName: 'resume.pdf', parsed: { folder: 'Alpha' } }]
    wrapper = mountResumeManager()
    await flushPromises()
    await wrapper.get('.resume-manager-actions > button').trigger('click')
  })
  afterEach(() => {
    wrapper.unmount()
    vi.restoreAllMocks()
  })

  it('validates empty and duplicate folder names before persistence', async () => {
    const input = wrapper.get('#resume-folder-name')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('[role="alert"]').text()).toBe('请输入分组名称。')
    await input.setValue('Alpha')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('[role="alert"]').text()).toBe('分组已存在。')
    expect(saveWorkspaceState).not.toHaveBeenCalled()
  })

  it('recovers from failed folder creation', async () => {
    saveWorkspaceState.mockRejectedValueOnce(new Error('workspace offline'))
    await wrapper.get('#resume-folder-name').setValue('Gamma')
    await wrapper.get('.resume-folder-create button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('workspace offline')
    await wrapper.get('.resume-folder-create button').trigger('click')
    await flushPromises()
    expect(saveWorkspaceState).toHaveBeenLastCalledWith(expect.any(String), { folders: ['Alpha', 'Beta', 'Gamma'] })
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it.each([
    ['', '请输入分组名称。'],
    ['Beta', '分组已存在。'],
  ])('rejects rename to %s', async (name, message) => {
    await wrapper.get('.resume-folder-maintenance-item button').trigger('click')
    const input = wrapper.get('input[aria-label="重命名分组 Alpha"]')
    await input.setValue(name)
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('[role="alert"]').text()).toBe(message)
    expect(saveWorkspaceState).not.toHaveBeenCalled()
  })

  it('closes an unchanged rename without writing and allows Escape cancellation', async () => {
    await wrapper.get('.resume-folder-maintenance-item button').trigger('click')
    await wrapper.get('input[aria-label="重命名分组 Alpha"]').trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('input[aria-label="重命名分组 Alpha"]').exists()).toBe(false)
    await wrapper.get('.resume-folder-maintenance-item button').trigger('click')
    await wrapper.get('input[aria-label="重命名分组 Alpha"]').trigger('keydown', { key: 'Escape' })
    expect(saveWorkspaceState).not.toHaveBeenCalled()
  })

  it('retains rename state on resume update failure for a retry', async () => {
    const save = vi.spyOn(resume, 'saveParsed').mockRejectedValueOnce(new Error('resume offline')).mockResolvedValue({})
    await wrapper.get('.resume-folder-maintenance-item button').trigger('click')
    const input = wrapper.get('input[aria-label="重命名分组 Alpha"]')
    await input.setValue('Gamma')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('resume offline')
    expect(saveWorkspaceState).not.toHaveBeenCalled()
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith('r1', { folder: 'Gamma', resumeFolder: 'Gamma' })
    expect(saveWorkspaceState).toHaveBeenLastCalledWith(expect.any(String), { folders: ['Gamma', 'Beta'] })
  })

  it('keeps deletion confirmation open on failure then moves resumes to ungrouped', async () => {
    const save = vi.spyOn(resume, 'saveParsed').mockRejectedValueOnce(new Error('resume offline')).mockResolvedValue({})
    await wrapper.get('.resume-folder-maintenance-item .danger').trigger('click')
    await wrapper.get('[role="alertdialog"] .danger-btn').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alertdialog"] [role="alert"]').text()).toBe('resume offline')
    await wrapper.get('[role="alertdialog"] .danger-btn').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith('r1', { folder: '', resumeFolder: '' })
    expect(saveWorkspaceState).toHaveBeenLastCalledWith(expect.any(String), { folders: ['Beta'] })
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
  })
})
