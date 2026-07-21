import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeWriter from '../src/components/ResumeWriter.vue'

const mocks = vi.hoisted(() => ({
  workspaceState: {},
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
  createWriterVersion: vi.fn(),
}))

vi.mock('../src/api/workspace', () => ({
  getWorkspaceState: mocks.getWorkspaceState,
  saveWorkspaceState: mocks.saveWorkspaceState,
}))

vi.mock('../src/api/resume', () => ({
  createWriterVersion: mocks.createWriterVersion,
  deleteWriterVersion: vi.fn(),
  getWriterVersion: vi.fn(),
  listWriterVersions: vi.fn().mockResolvedValue([]),
  normalizeResumeAssetUrl: (value) => value,
  restoreWriterVersion: vi.fn(),
  resumeAssetDisplayUrl: (value) => value,
  uploadResumeAsset: vi.fn(),
}))

describe('ResumeWriter initial content', () => {
  beforeEach(() => {
    mocks.workspaceState = {}
    mocks.getWorkspaceState.mockReset().mockImplementation(async () => mocks.workspaceState)
    mocks.saveWorkspaceState.mockReset().mockResolvedValue({})
    mocks.createWriterVersion.mockReset().mockResolvedValue({ versionId: 'backup-version' })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens without prefilled resume content when no draft exists', async () => {
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    expect(wrapper.find('.resume-filename-field input').element.value).toBe('')
    expect(wrapper.find('.resume-clean-editor textarea').element.value).toBe('')
    expect(wrapper.find('.resume-clean-editor textarea').attributes('placeholder')).toBe(
      '请输入或导入 Markdown 简历内容',
    )
    expect(wrapper.find('.resume-clean-preview').text()).not.toContain('PHOTO')

    wrapper.unmount()
  })

  it('still restores explicitly saved draft content', async () => {
    mocks.workspaceState = {
      fileName: '我的求职简历',
      markdown: '# 已保存简历',
      photoUrl: 'https://example.com/photo.png',
    }
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    expect(wrapper.find('.resume-filename-field input').element.value).toBe('我的求职简历')
    expect(wrapper.find('.resume-clean-editor textarea').element.value).toBe('# 已保存简历')

    wrapper.unmount()
  })

  it('loads the sanitized one-page example only after the user clicks the button', async () => {
    const wrapper = mount(ResumeWriter)
    await flushPromises()
    mocks.saveWorkspaceState.mockClear()

    expect(wrapper.find('.resume-clean-editor textarea').element.value).toBe('')

    await wrapper
      .findAll('.resume-clean-menu button')
      .find((button) => button.text() === '加载示例')
      .trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(wrapper.find('.resume-clean-editor textarea').element.value).toContain('林知远（虚构示例）')
    })

    const content = wrapper.find('.resume-clean-editor textarea').element.value
    expect(content).toContain('上海')
    expect(content).toContain('林知远（虚构示例） ｜ AI 应用开发工程师')
    expect(content).toContain('40-50k')
    expect(content).toContain('零售经营分析 Agent')
    expect(content).toContain('售后工单质检 Agent')
    expect(content.indexOf('## 教育背景')).toBeLessThan(content.indexOf('## 职业概述'))
    for (const privateContent of [
      '示例候选人',
      '示例科技公司',
      '示例理工大学',
      '示例研究院',
      '示例软件公司',
      '示例知识库助手',
      '示例数据问答项目',
      '示例平台项目',
      '示例数据平台',
      '13800000000',
      'candidate',
      'imiao.top',
      '5 年平台研发经验',
      '2017.09 - 2021.06',
      '2024.06 - 至今',
    ]) {
      expect(content).not.toContain(privateContent)
    }
    expect(wrapper.find('img.resume-photo').attributes('src')).toBe('/resume-photo-placeholder.svg')
    expect(wrapper.find('.resume-filename-field input').element.value).toBe('AI应用开发岗-脱敏示例简历')
    expect(wrapper.find('input[aria-label="字号"]').element.value).toBe('12.5px')
    expect(wrapper.find('input[aria-label="行距"]').element.value).toBe('1.72')
    expect(mocks.saveWorkspaceState).toHaveBeenCalledWith(
      'resume.writer',
      expect.objectContaining({
        markdown: expect.stringContaining('林知远（虚构示例）'),
        onePage: true,
      }),
    )

    wrapper.unmount()
  })

  it('does not overwrite an existing draft when example loading is cancelled', async () => {
    mocks.workspaceState = { markdown: '# 我的现有草稿', fileName: '现有简历' }
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    await wrapper
      .findAll('.resume-clean-menu button')
      .find((button) => button.text() === '加载示例')
      .trigger('click')
    await flushPromises()

    expect(wrapper.find('.resume-clean-editor textarea').element.value).toBe('# 我的现有草稿')
    expect(mocks.createWriterVersion).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('backs up an existing draft before loading the example', async () => {
    mocks.workspaceState = { markdown: '# 我的现有草稿', fileName: '现有简历' }
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    await wrapper
      .findAll('.resume-clean-menu button')
      .find((button) => button.text() === '加载示例')
      .trigger('click')
    await flushPromises()

    expect(mocks.createWriterVersion).toHaveBeenCalledWith(
      expect.objectContaining({
        source: 'import_backup',
        title: '加载脱敏示例前的草稿备份',
      }),
    )
    expect(wrapper.find('.resume-clean-editor textarea').element.value).toContain('林知远（虚构示例）')

    wrapper.unmount()
  })

  it('replaces an active real photo with the placeholder when loading the example', async () => {
    mocks.workspaceState = {
      markdown: '# 带照片的现有简历',
      photoUrl: 'https://example.com/private-photo.png',
      photoLibrary: [{ id: 'photo-1', name: '真实照片', url: 'https://example.com/private-photo.png' }],
    }
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    expect(wrapper.find('[data-managed-resume-photo="true"]').exists()).toBe(true)

    await wrapper
      .findAll('.resume-clean-menu button')
      .find((button) => button.text() === '加载示例')
      .trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.find('img[src="/resume-photo-placeholder.svg"]').exists()).toBe(true)
    })

    expect(wrapper.find('[data-managed-resume-photo="true"]').exists()).toBe(false)
    expect(mocks.saveWorkspaceState).toHaveBeenCalledWith('resume.writer', expect.objectContaining({ photoUrl: '' }))

    wrapper.unmount()
  })

  it('keeps a saved photo library unselected when no photo is active', async () => {
    mocks.workspaceState = {
      markdown: '# 无照片简历',
      photoUrl: '',
      photoLibrary: [{ id: 'photo-1', name: '历史照片', url: 'https://example.com/private-photo.png' }],
    }
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    expect(wrapper.find('[data-managed-resume-photo="true"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('supports selecting font size and line height from dropdown menus', async () => {
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    await wrapper.find('button[aria-label="展开字号选项"]').trigger('mousedown')
    const fontSizeMenu = wrapper.find('[role="listbox"][aria-label="字号选项"]')
    expect(fontSizeMenu.exists()).toBe(true)
    expect(fontSizeMenu.findAll('[role="option"]')).toHaveLength(11)
    await fontSizeMenu
      .findAll('[role="option"]')
      .find((option) => option.text() === '14px')
      .trigger('mousedown')
    expect(wrapper.find('input[aria-label="字号"]').element.value).toBe('14px')

    await wrapper.find('button[aria-label="展开行距选项"]').trigger('mousedown')
    const lineHeightMenu = wrapper.find('[role="listbox"][aria-label="行距选项"]')
    expect(lineHeightMenu.exists()).toBe(true)
    expect(lineHeightMenu.findAll('[role="option"]')).toHaveLength(10)
    await lineHeightMenu
      .findAll('[role="option"]')
      .find((option) => option.text() === '1.85')
      .trigger('mousedown')
    expect(wrapper.find('input[aria-label="行距"]').element.value).toBe('1.85')

    wrapper.unmount()
  })

  it('normalizes manually entered font size and line height values', async () => {
    const wrapper = mount(ResumeWriter)
    await flushPromises()

    const fontSizeInput = wrapper.find('input[aria-label="字号"]')
    await fontSizeInput.setValue('13')
    await fontSizeInput.trigger('keydown.enter')
    expect(fontSizeInput.element.value).toBe('13px')
    expect(fontSizeInput.attributes('aria-expanded')).toBe('false')

    const lineHeightInput = wrapper.find('input[aria-label="行距"]')
    await lineHeightInput.setValue('1.6')
    await lineHeightInput.trigger('keydown.enter')
    expect(lineHeightInput.element.value).toBe('1.6')
    expect(lineHeightInput.attributes('aria-expanded')).toBe('false')

    wrapper.unmount()
  })
})
