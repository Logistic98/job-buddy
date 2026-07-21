import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  addProjectMaterial,
  addProjectQuestion,
  createDeepDiveProject,
  deleteProjectMaterial,
  deleteProjectQuestion,
  generateProjectQuestions,
  getDeepDiveProject,
  listDeepDiveProjects,
  projectMaterialBatchDownloadUrl,
  projectMaterialFileUrl,
  updateDeepDiveProject,
  updateProjectQuestion,
} from '../src/api/projectDeepDive'

function mockFetchResponse({ ok = true, status = 200, body = { code: 0, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
})

describe('project deep-dive API uses PostgreSQL backend only', () => {
  it('loads project summaries and detail through separate endpoints', async () => {
    const fetch = mockFetchResponse({
      body: { code: 0, message: 'success', data: [{ projectId: 'p1', questionCount: 58 }] },
    })
    vi.stubGlobal('fetch', fetch)
    await expect(listDeepDiveProjects()).resolves.toEqual([{ projectId: 'p1', questionCount: 58 }])
    expect(fetch).toHaveBeenCalledWith('/api/project-deep-dive/projects', expect.any(Object))

    const detailFetch = mockFetchResponse({
      body: { code: 0, message: 'success', data: { projectId: 'p1', questions: [] } },
    })
    vi.stubGlobal('fetch', detailFetch)
    await expect(getDeepDiveProject('p1')).resolves.toEqual({ projectId: 'p1', questions: [] })
    expect(detailFetch).toHaveBeenCalledWith('/api/project-deep-dive/projects/p1', expect.any(Object))
  })

  it('reports backend error when generation endpoint is missing', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(generateProjectQuestions('p1', {})).rejects.toThrow('项目深挖问题生成失败')
  })

  it('reports method mismatch without local fallback', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 405, body: '' }))
    await expect(generateProjectQuestions('p1', {})).rejects.toThrow('项目深挖问题生成失败')
  })

  it('returns generated questions on success', async () => {
    const data = { questions: [{ question: '介绍一下项目架构' }] }
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 0, message: 'success', data } }))
    await expect(generateProjectQuestions('p1', {})).resolves.toEqual(data)
  })

  it('uploads an arbitrary file with multipart form data', async () => {
    const project = { projectId: 'p1', materials: [{ materialId: 'm1', fileName: 'source.zip' }] }
    const fetch = mockFetchResponse({ body: { code: 0, message: 'success', data: project } })
    vi.stubGlobal('fetch', fetch)
    const file = new File(['zip-content'], 'source.zip', { type: 'application/zip' })

    await expect(addProjectMaterial('p1', file)).resolves.toEqual(project)

    const [, options] = fetch.mock.calls[0]
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
  })

  it('creates same-origin single and batch download URLs', () => {
    expect(projectMaterialFileUrl('m1')).toBe('/api/project-deep-dive/materials/m1/file')
    expect(projectMaterialBatchDownloadUrl(['m1', '材料 2'])).toBe(
      '/api/project-deep-dive/materials/batch-file?materialIds=m1&materialIds=%E6%9D%90%E6%96%99%202',
    )
    expect(projectMaterialBatchDownloadUrl([])).toBe('#')
  })

  it('deletes a material through the DELETE endpoint', async () => {
    const fetch = mockFetchResponse({
      body: { code: 0, message: 'success', data: { name: 'materialId', value: 'm1' } },
    })
    vi.stubGlobal('fetch', fetch)
    await deleteProjectMaterial('m1')
    expect(fetch).toHaveBeenCalledWith(
      '/api/project-deep-dive/materials/m1',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('updates project info through the PUT endpoint', async () => {
    const project = { projectId: 'p1', name: '平台项目', summary: '新的摘要' }
    const fetch = mockFetchResponse({ body: { code: 0, message: 'success', data: project } })
    vi.stubGlobal('fetch', fetch)
    await expect(updateDeepDiveProject('p1', { name: '平台项目', summary: '新的摘要' })).resolves.toEqual(project)
    expect(fetch).toHaveBeenCalledWith('/api/project-deep-dive/projects/p1', expect.objectContaining({ method: 'PUT' }))
  })

  it('manages questions through dedicated CRUD endpoints', async () => {
    const project = { projectId: 'p1', questions: [{ questionId: 'q1', question: '手动问题' }] }
    let fetch = mockFetchResponse({ body: { code: 0, message: 'success', data: project } })
    vi.stubGlobal('fetch', fetch)
    await expect(addProjectQuestion('p1', { question: '手动问题' })).resolves.toEqual(project)
    expect(fetch).toHaveBeenCalledWith(
      '/api/project-deep-dive/projects/p1/questions',
      expect.objectContaining({ method: 'POST' }),
    )

    fetch = mockFetchResponse({ body: { code: 0, message: 'success', data: project } })
    vi.stubGlobal('fetch', fetch)
    await expect(updateProjectQuestion('q1', { question: '编辑后的问题' })).resolves.toEqual(project)
    expect(fetch).toHaveBeenCalledWith(
      '/api/project-deep-dive/questions/q1',
      expect.objectContaining({ method: 'PUT' }),
    )

    fetch = mockFetchResponse({ body: { code: 0, message: 'success', data: { name: 'questionId', value: 'q1' } } })
    vi.stubGlobal('fetch', fetch)
    await deleteProjectQuestion('q1')
    expect(fetch).toHaveBeenCalledWith(
      '/api/project-deep-dive/questions/q1',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('reports question CRUD failures with readable messages', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 500, body: '' }))
    await expect(addProjectQuestion('p1', { question: 'x' })).rejects.toThrow('问题新增失败')
    await expect(updateProjectQuestion('q1', { question: 'x' })).rejects.toThrow('问题保存失败')
    await expect(deleteProjectQuestion('q1')).rejects.toThrow('问题删除失败')
  })

  it('does not create a browser-local project when backend is missing', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(createDeepDiveProject({ name: '智能问答系统' })).rejects.toThrow('项目保存失败')
  })
})
