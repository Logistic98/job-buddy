import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { useResumeWriterPage } from '../../src/composables/useResumeWriterPage'

const api = vi.hoisted(() => ({
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
  createWriterVersion: vi.fn(),
  deleteWriterVersion: vi.fn(),
  getWriterVersion: vi.fn(),
  listWriterVersions: vi.fn(),
  restoreWriterVersion: vi.fn(),
  uploadResumeAsset: vi.fn(),
}))
vi.mock('../../src/api/workspace', () => ({
  getWorkspaceState: api.getWorkspaceState,
  saveWorkspaceState: api.saveWorkspaceState,
}))
vi.mock('../../src/api/resume', async (importOriginal) => ({ ...(await importOriginal()), ...api }))

const pdf = vi.hoisted(() => ({
  setLanguage: vi.fn(),
  setProperties: vi.fn(),
  addPage: vi.fn(),
  addImage: vi.fn(),
  save: vi.fn(),
  link: vi.fn(),
  setFont: vi.fn(),
  setFontSize: vi.fn(),
  text: vi.fn(),
}))
const renderCanvas = vi.hoisted(() => vi.fn())
vi.mock('html2canvas', () => ({ default: renderCanvas }))
vi.mock('jspdf', () => ({
  jsPDF: vi.fn(function () {
    return pdf
  }),
}))
vi.mock('../../src/utils/resumePdf', async (importOriginal) => ({
  ...(await importOriginal()),
  registerPdfTextFont: vi.fn(async () => {}),
}))
vi.mock('../../src/utils/resumeWriterFormat', async (importOriginal) => ({
  ...(await importOriginal()),
  waitForImages: vi.fn(async () => {}),
}))
vi.mock('../../src/utils/imageData', () => ({ imageUrlToDataUrl: vi.fn(async () => 'data:image/png;base64,cGhvdG8=') }))

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  Object.values(api).forEach((fn) => fn.mockReset())
  api.getWorkspaceState.mockResolvedValue({ markdown: '# Draft' })
  api.saveWorkspaceState.mockResolvedValue({})
  api.listWriterVersions.mockResolvedValue([])
  api.createWriterVersion.mockResolvedValue({ versionId: 'created' })
  vi.stubGlobal('alert', vi.fn())
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function writer() {
  const wrapper = mount({ setup: useResumeWriterPage, template: '<div />' })
  await flushPromises()
  return wrapper
}

it('creates validated manual versions and reports save failures without closing the dialog', async () => {
  const wrapper = await writer()
  const page = wrapper.vm
  page.openSaveVersionDialog()
  await page.saveManualVersion()
  expect(page.versionError).not.toBe('')
  expect(api.createWriterVersion).not.toHaveBeenCalled()
  page.versionTitle = 'Reviewed draft'
  await page.saveManualVersion()
  expect(api.createWriterVersion).toHaveBeenCalledWith(
    expect.objectContaining({ source: 'manual', title: 'Reviewed draft', resumeId: '' }),
  )
  expect(page.showSaveVersionDialog).toBe(false)
  page.openSaveVersionDialog()
  page.versionTitle = 'Next'
  api.createWriterVersion.mockRejectedValue(new Error('save offline'))
  await page.saveManualVersion()
  expect(page.versionError).toBe('save offline')
  expect(page.showSaveVersionDialog).toBe(true)
  expect(page.savingVersion).toBe(false)
})

it('previews, restores, and deletes versions through API contracts', async () => {
  const wrapper = await writer()
  const page = wrapper.vm
  const version = { versionId: 'v1', versionNo: 1 }
  api.getWriterVersion.mockResolvedValue({ ...version, snapshotJson: '{"markdown":"# Saved"}' })
  api.restoreWriterVersion.mockResolvedValue({ snapshotJson: '{"markdown":"# Restored"}' })
  await page.openVersionHistory()
  expect(page.showVersionHistory).toBe(true)
  await page.previewHistoryVersion(version)
  expect(page.previewMarkdown).toBe('# Saved')
  await page.restoreHistoryVersion(version)
  expect(page.markdown).toBe('# Restored')
  expect(page.showVersionHistory).toBe(false)
  expect(api.restoreWriterVersion).toHaveBeenCalledWith('v1', expect.objectContaining({ currentResumeId: '' }))
  await page.removeHistoryVersion(version)
  expect(api.deleteWriterVersion).toHaveBeenCalledWith('v1')
  expect(page.previewVersion).toBe(null)
})

it.each(['list', 'preview', 'restore', 'delete'])('retains a readable version %s failure', async (kind) => {
  const wrapper = await writer()
  const methods = {
    list: [api.listWriterVersions, wrapper.vm.refreshVersions],
    preview: [api.getWriterVersion, wrapper.vm.previewHistoryVersion],
    restore: [api.restoreWriterVersion, wrapper.vm.restoreHistoryVersion],
    delete: [api.deleteWriterVersion, wrapper.vm.removeHistoryVersion],
  }
  const [request, action] = methods[kind]
  request.mockRejectedValue(new Error('offline'))
  await action({ versionId: 'one', versionNo: 1 })
  expect(wrapper.vm.versionError).toBe('offline')
  expect(wrapper.vm.versionsLoading).toBe(false)
})

it('creates auto versions only after the interval and a significant change', async () => {
  const wrapper = await writer()
  const page = wrapper.vm
  page.markdown = 'x'.repeat(300)
  await page.maybeCreateAutoVersion()
  expect(api.createWriterVersion).not.toHaveBeenCalled()
  vi.setSystemTime(new Date('2026-01-01T00:06:00Z'))
  await page.maybeCreateAutoVersion()
  expect(api.createWriterVersion).toHaveBeenCalledWith(expect.objectContaining({ source: 'auto' }))
  vi.setSystemTime(new Date('2026-01-01T00:12:00Z'))
  await page.maybeCreateAutoVersion()
  expect(api.createWriterVersion).toHaveBeenCalledTimes(1)
  page.markdown = 'changed'.repeat(100)
  api.createWriterVersion.mockRejectedValue(new Error('snapshot offline'))
  await page.maybeCreateAutoVersion()
  expect(page.versionError).toBe('snapshot offline')
})

it('imports Markdown after a recoverable backup and rejects invalid files', async () => {
  const wrapper = await writer()
  const file = new File(['# Imported'], 'Imported.md', { type: 'text/plain' })
  file.text = async () => '# Imported'
  const event = { target: { files: [file], value: 'selected' } }
  await wrapper.vm.importMarkdown(event)
  expect(wrapper.vm.markdown).toBe('# Imported')
  expect(wrapper.vm.fileName).toBe('Imported')
  expect(event.target.value).toBe('')
  expect(api.createWriterVersion).toHaveBeenCalledWith(expect.objectContaining({ source: 'import_backup' }))
  await wrapper.vm.importMarkdown({ target: { files: [new File(['bad'], 'bad.exe')], value: 'selected' } })
  expect(alert).toHaveBeenCalled()
  expect(wrapper.vm.markdown).toBe('# Imported')
})

it('uploads photos atomically validates batches and selects a replacement on deletion', async () => {
  const wrapper = await writer()
  api.uploadResumeAsset.mockResolvedValue({ assetId: 'asset', url: '/api/photo.png' })
  const file = new File(['image'], 'photo.png', { type: 'image/png' })
  const event = { target: { files: [file], value: 'selected' } }
  await wrapper.vm.importPhoto(event)
  expect(wrapper.vm.photoUrl).toBe('/api/photo.png')
  expect(wrapper.vm.photoLibrary).toHaveLength(1)
  expect(event.target.value).toBe('')
  await wrapper.vm.importPhoto({ target: { files: Array(11).fill(file), value: '' } })
  expect(api.uploadResumeAsset).toHaveBeenCalledOnce()
  expect(alert).toHaveBeenCalledWith('单次最多上传 10 张照片')
  wrapper.vm.selectPhoto({ url: '/api/photo.png' })
  wrapper.vm.deletePhoto(wrapper.vm.photoLibrary[0])
  expect(wrapper.vm.photoLibrary).toEqual([])
  expect(wrapper.vm.photoUrl).toBe('')
  wrapper.vm.clearPhoto()
  expect(wrapper.vm.selectedPhoto).toBe(false)
})

it('commits photo dragging and scaling using preview coordinates', async () => {
  const wrapper = await writer()
  const page = wrapper.vm
  const frame = document.createElement('div')
  frame.dataset.managedResumePhoto = 'true'
  const handle = document.createElement('span')
  handle.dataset.photoHandle = 'se'
  frame.appendChild(handle)
  document.body.appendChild(frame)
  try {
    page.previewScale = 0.5
    page.handlePreviewClick({ target: frame })
    expect(page.selectedPhoto).toBe(true)
    const event = { target: frame, type: 'pointerdown', clientX: 10, clientY: 10, preventDefault: vi.fn() }
    page.handlePreviewPointerDown(event)
    page.handlePhotoPointerMove({ clientX: 20, clientY: 25, preventDefault: vi.fn() })
    expect(frame.style.cssText).not.toBe('')
    page.stopPhotoDrag()
    expect(page.photoSettings).toMatchObject({ x: 20, y: 30 })
    page.handlePreviewPointerDown({ ...event, target: handle, type: 'mousedown' })
    page.handlePhotoPointerMove({ clientX: 55, clientY: 55, preventDefault: vi.fn() })
    page.stopPhotoDrag()
    expect(page.photoSettings.scale).toBeGreaterThan(1)
    page.resetPhotoAdjust()
    expect(page.photoSettings).toMatchObject({ x: 0, y: 0, scale: 1 })
    page.setPhotoScale(1.5)
    expect(page.photoSettings.scale).toBe(1.5)
  } finally {
    frame.remove()
  }
})

it('exports each preview page as one A4 page and cleans temporary rendering elements', async () => {
  const wrapper = await writer()
  Object.values(pdf).forEach((fn) => fn.mockClear())
  const toDataURL = vi.fn(() => 'data:image/jpeg;base64,cGFnZQ==')
  renderCanvas.mockResolvedValue({ width: 840, height: 1188, toDataURL })
  const root = document.createElement('div')
  root.id = 'resume-print-root'
  root.innerHTML =
    '<article class="resume-page"><div class="resume-photo-frame is-selected"><img class="resume-photo"><span class="resume-photo-handle"></span></div></article><article class="resume-page"></article>'
  for (const page of root.children) page.getBoundingClientRect = () => ({ width: 840, height: 1188, top: 0, left: 0 })
  document.body.appendChild(root)
  wrapper.vm.fileName = 'Reviewed'
  try {
    await wrapper.vm.exportPdf()
    expect(renderCanvas).toHaveBeenCalledTimes(2)
    expect(pdf.addPage).toHaveBeenCalledOnce()
    expect(pdf.addImage).toHaveBeenCalledWith(expect.any(String), 'JPEG', 0, 0, 210, 297, undefined, 'FAST')
    expect(pdf.save).toHaveBeenCalledWith('Reviewed.pdf')
    const clone = renderCanvas.mock.calls[0][0]
    expect(clone.querySelector('.resume-photo-handle')).toBeNull()
    expect(clone.querySelector('.is-selected')).toBeNull()
    expect(clone.querySelector('img').src).toBe('data:image/png;base64,cGhvdG8=')
    expect(document.querySelector('.resume-pdf-export-wrapper')).toBeNull()
    expect(wrapper.vm.exportingPdf).toBe(false)
    renderCanvas.mockRejectedValueOnce(new Error('render failed'))
    await wrapper.vm.exportPdf()
    expect(alert).toHaveBeenCalledWith('PDF 导出失败：render failed')
    expect(document.querySelector('.resume-pdf-export-wrapper')).toBeNull()
    expect(wrapper.vm.exportingPdf).toBe(false)
  } finally {
    root.remove()
  }
})

it('exports escaped HTML and Markdown with validated filenames', async () => {
  const wrapper = await writer()
  wrapper.vm.fileName = 'bad/name'
  wrapper.vm.normalizeFileName()
  expect(wrapper.vm.fileNameError).toContain('导出文件名不能包含')
  wrapper.vm.fileName = 'Reviewed'
  wrapper.vm.normalizeFileName()
  expect(wrapper.vm.fileNameError).toBe('')
  wrapper.vm.markdown = '# Reviewed'
  const html = await wrapper.vm.buildHtmlDocument()
  expect(html).toContain('<title>Reviewed</title>')
  expect(html).toContain('<h2>Reviewed</h2>')
  const blobs = []
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL(blob) {
        blobs.push(blob)
        return 'blob:test'
      }
      static revokeObjectURL() {}
    },
  )
  const click = vi.spyOn(window.HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  try {
    wrapper.vm.exportMarkdown()
    await wrapper.vm.exportHtml()
    expect(blobs.map((blob) => blob.type)).toEqual(['text/markdown;charset=utf-8', 'text/html;charset=utf-8'])
    expect(click).toHaveBeenCalledTimes(2)
  } finally {
    click.mockRestore()
  }
})

it('inserts snippets at selection and restores the original text with undo', async () => {
  const wrapper = await writer()
  const textarea = document.createElement('textarea')
  textarea.value = wrapper.vm.markdown
  textarea.selectionStart = 2
  textarea.selectionEnd = 7
  wrapper.vm.textareaRef = textarea
  wrapper.vm.insertSnippet('Edited')
  expect(wrapper.vm.markdown).toBe('# Edited')
  textarea.value = wrapper.vm.markdown
  await vi.advanceTimersByTimeAsync(0)
  expect(textarea.selectionStart).toBe(8)
  const event = { key: 'z', ctrlKey: true, metaKey: false, shiftKey: false, preventDefault: vi.fn() }
  wrapper.vm.handleEditorKeydown(event)
  expect(wrapper.vm.markdown).toBe('# Draft')
  expect(event.preventDefault).toHaveBeenCalled()
})

it('paginates measured content and scales to the available preview width', async () => {
  const wrapper = await writer()
  const measure = document.createElement('article')
  measure.getBoundingClientRect = () => ({ width: 840, height: 100, top: 0, left: 0 })
  wrapper.vm.measureRef = measure
  await wrapper.vm.paginate()
  expect(wrapper.vm.pages).toEqual([''])
  measure.innerHTML = '<p>Content</p>'
  measure.firstChild.getBoundingClientRect = () => ({ width: 840, height: 20, top: 0, bottom: 20, left: 0 })
  const canvas = document.createElement('div')
  Object.defineProperty(canvas, 'clientWidth', { value: 456 })
  wrapper.vm.previewCanvas = canvas
  await wrapper.vm.paginate()
  expect(wrapper.vm.pages[0]).toContain('Content')
  expect(wrapper.vm.previewScale).toBe(0.5)
  expect(wrapper.vm.pagesScaleStyle).toEqual({ zoom: 0.5 })
  wrapper.vm.previewCanvas = null
  wrapper.vm.updateScale()
  expect(wrapper.vm.previewScale).toBe(1)
})

it('normalizes toolbar input after blur and cycles theme', async () => {
  const wrapper = await writer()
  wrapper.vm.openCombo('fontSize')
  wrapper.vm.fontSize = '18'
  wrapper.vm.handleComboBlur('fontSize')
  await vi.advanceTimersByTimeAsync(120)
  expect(wrapper.vm.fontSize).toBe('18px')
  expect(wrapper.vm.activeCombo).toBe('')
  wrapper.vm.lineHeight = '1.7'
  wrapper.vm.handleComboBlur('lineHeight')
  await vi.advanceTimersByTimeAsync(120)
  expect(wrapper.vm.lineHeight).toBe('1.7')
  const previous = wrapper.vm.currentTheme
  wrapper.vm.cycleTheme()
  expect(wrapper.vm.currentTheme).not.toBe(previous)
  wrapper.vm.fileInput = { click: vi.fn() }
  wrapper.vm.importClick()
  expect(wrapper.vm.fileInput.click).toHaveBeenCalledOnce()
})

it('reports malformed snapshots and draft persistence errors', async () => {
  const wrapper = await writer()
  api.getWriterVersion.mockResolvedValue({ snapshotJson: '{bad' })
  await wrapper.vm.previewHistoryVersion({ versionId: 'invalid' })
  expect(wrapper.vm.versionError).toContain('格式无效')
  api.saveWorkspaceState.mockRejectedValue(new Error('draft offline'))
  await wrapper.vm.saveNow()
  expect(alert).toHaveBeenCalledWith('draft offline')
  expect(wrapper.vm.sourceLabel('manual')).toBe('手动保存')
  expect(wrapper.vm.sourceLabel('')).toBe('未知来源')
  expect(wrapper.vm.formatVersionTime('')).toBe('未知时间')
  expect(wrapper.vm.formatVersionTime('2026-01-01')).toContain('2026')
})
