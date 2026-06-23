import { describe, it, expect, vi, beforeEach } from 'vitest'
import { generateProjectQuestions, listDeepDiveProjects, createDeepDiveProject } from '../src/api/projectDeepDive'

function mockFetchResponse({ ok = true, status = 200, body = { code: 0, message: 'success', data: {} } } = {}) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  })
}

beforeEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('generateProjectQuestions', () => {
  it('throws explicit error when backend endpoint is missing (404)', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    await expect(generateProjectQuestions('p1', {})).rejects.toThrow('项目深挖问题生成服务不可用')
  })

  it('throws explicit error on 405 as well', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 405, body: '' }))
    await expect(generateProjectQuestions('p1', {})).rejects.toThrow('项目深挖问题生成服务不可用')
  })

  it('returns generated questions on success', async () => {
    const data = { questions: [{ question: '介绍一下项目架构' }] }
    vi.stubGlobal('fetch', mockFetchResponse({ body: { code: 0, message: 'success', data } }))
    await expect(generateProjectQuestions('p1', {})).resolves.toEqual(data)
  })
})

describe('project CRUD local fallback (kept)', () => {
  it('createDeepDiveProject stores project locally on 404 and list returns it', async () => {
    vi.stubGlobal('fetch', mockFetchResponse({ ok: false, status: 404, body: 'Not Found' }))
    const project = await createDeepDiveProject({ name: '智能问答系统' })
    expect(project.projectId).toMatch(/^pdd_/)
    const rows = await listDeepDiveProjects()
    expect(rows).toHaveLength(1)
    expect(rows[0].name).toBe('智能问答系统')
  })
})
