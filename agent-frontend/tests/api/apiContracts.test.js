import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import * as settings from '../../src/api/settings'
import * as resume from '../../src/api/resume'
import * as interview from '../../src/api/interview'
import * as jobs from '../../src/api/jobs'

const http = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  parseApiResponse: vi.fn(),
  apiUrl: vi.fn((path) => `/api${path}`),
}))
vi.mock('../../src/api/http', () => http)
const analysis = vi.hoisted(() => ({
  getLatestAnalysisTask: vi.fn(),
  getAnalysisTask: vi.fn(),
  streamAnalysisTask: vi.fn(),
  cancelAnalysisTask: vi.fn(),
}))
vi.mock('../../src/api/analysisTasks', () => analysis)

beforeEach(() => {
  http.apiFetch.mockResolvedValue({ status: 200 })
  http.parseApiResponse.mockResolvedValue({ saved: true })
})
afterEach(() => vi.useRealTimers())

it.each([
  [settings.getSettings, [], '/settings', 'GET', undefined],
  [settings.refreshServiceHealth, [], '/settings/services/health/refresh', 'POST', undefined],
  [settings.saveSettings, [{ enabled: true }], '/settings', 'PUT', { enabled: true }],
  [settings.restoreWorkspaceDefaults, [], '/settings/workspace/restore-defaults', 'POST', undefined],
  [settings.listMemories, [], '/settings/memories', 'GET', undefined],
  [settings.addMemory, [{ content: 'plain' }], '/settings/memories', 'POST', { content: 'plain' }],
  [settings.updateMemory, ['a/b', { content: 'changed' }], '/settings/memories/a%2Fb', 'PUT', { content: 'changed' }],
  [settings.deleteMemory, ['a/b'], '/settings/memories/a%2Fb', 'DELETE', undefined],
  [settings.clearMemories, [], '/settings/memories', 'DELETE', undefined],
  [resume.getJobProfile, [], '/resume/profile', 'GET', undefined],
  [resume.saveJobProfile, [{ name: 'Sample' }], '/resume/profile', 'PUT', { parsed: { name: 'Sample' } }],
  [
    resume.generateJobProfileSummary,
    [{ role: 'Java' }, 's&1'],
    '/resume/profile/summary?sessionId=s%261',
    'POST',
    { parsed: { role: 'Java' } },
  ],
  [resume.generateJobProfileSummary, [{}, ''], '/resume/profile/summary', 'POST', { parsed: {} }],
  [resume.getResume, ['a/b'], '/resume/a%2Fb', 'GET', undefined],
  [
    resume.startResumeAnalysisTask,
    ['resume', 'session'],
    '/resume/analysis-tasks',
    'POST',
    { resumeId: 'resume', sessionId: 'session' },
  ],
  [resume.updateResumeParsed, ['a/b', { role: 'Java' }], '/resume/a%2Fb/parsed', 'PUT', { parsed: { role: 'Java' } }],
  [resume.deleteResume, ['a/b'], '/resume/a%2Fb', 'DELETE', undefined],
  [resume.getWriterVersion, ['a/b'], '/resume/writer/versions/a%2Fb', 'GET', undefined],
  [
    resume.createWriterVersion,
    [{ source: 'writer', title: 'Draft', resumeId: 'r', snapshot: {} }],
    '/resume/writer/versions',
    'POST',
    { source: 'writer', title: 'Draft', resumeId: 'r', snapshot: {} },
  ],
  [
    resume.restoreWriterVersion,
    ['a/b', { currentSnapshot: {}, currentResumeId: 'r' }],
    '/resume/writer/versions/a%2Fb/restore',
    'POST',
    { currentSnapshot: {}, currentResumeId: 'r' },
  ],
  [resume.deleteWriterVersion, ['a/b'], '/resume/writer/versions/a%2Fb', 'DELETE', undefined],
  [interview.createQuestion, [{ title: 'Question' }], '/interview/questions', 'POST', { title: 'Question' }],
  [interview.generateQuestions, [{ count: 2 }], '/interview/questions/generate', 'POST', { count: 2 }],
  [interview.importQuestions, [{ items: [] }], '/interview/questions/import', 'POST', { items: [] }],
  [interview.updateQuestion, ['a/b', { title: 'Updated' }], '/interview/questions/a%2Fb', 'PUT', { title: 'Updated' }],
  [interview.deleteQuestion, ['a/b'], '/interview/questions/a%2Fb', 'DELETE', undefined],
  [interview.batchQuestions, [{ ids: ['one'] }], '/interview/questions/batch', 'POST', { ids: ['one'] }],
  [interview.getExam, ['a/b'], '/interview/practices/a%2Fb', 'GET', undefined],
  [interview.deleteExam, ['a/b'], '/interview/practices/a%2Fb', 'DELETE', undefined],
  [interview.createRandomExam, [{ count: 5 }], '/interview/practices/random', 'POST', { count: 5 }],
  [
    interview.createSmartExam,
    [{ requirements: 'Java' }],
    '/interview/practices/smart',
    'POST',
    { requirements: 'Java' },
  ],
  [interview.runCodeSample, [{ code: 'return 1' }], '/interview/code/run', 'POST', { code: 'return 1' }],
  [
    interview.submitExam,
    ['a/b', { one: 'A' }],
    '/interview/practices/a%2Fb/submit',
    'POST',
    { answers: { one: 'A' }, codingResults: {} },
  ],
  [jobs.importBossFavoriteJobs, [[{ id: 'one' }]], '/jobs/favorites/boss/import', 'POST', { jobs: [{ id: 'one' }] }],
  [jobs.saveFavoriteJob, [{ id: 'one' }], '/jobs/favorites', 'POST', { id: 'one' }],
  [
    jobs.startFavoriteAnalysisTask,
    [{ securityId: ' job ' }, 'resume'],
    '/jobs/favorites/analysis-tasks',
    'POST',
    { securityId: ' job ', jobKey: 'job', resumeId: 'resume' },
  ],
  [jobs.deleteFavoriteJob, ['a/b'], '/jobs/favorites/a%2Fb', 'DELETE', undefined],
])('%s sends its documented method, encoded identity, and body', async (call, args, path, method, body) => {
  await expect(call(...args)).resolves.toEqual({ saved: true })
  const [actualPath, options = {}] = http.apiFetch.mock.calls.at(-1)
  expect(actualPath).toBe(path)
  expect(options.method || 'GET').toBe(method)
  if (body !== undefined) {
    expect(JSON.parse(options.body)).toEqual(body)
    expect(options.headers['Content-Type']).toBe('application/json')
  } else expect(options.body).toBeUndefined()
  expect(http.parseApiResponse).toHaveBeenCalledWith({ status: 200 }, expect.stringMatching(/失败$/))
})

it.each([
  [resume.listResumes, '/resume'],
  [resume.listWriterVersions, '/resume/writer/versions'],
  [interview.listExams, '/interview/practices'],
  [jobs.listFavoriteJobs, '/jobs/favorites'],
])('list %s normalizes empty data', async (call, path) => {
  http.parseApiResponse.mockResolvedValue(null)
  await expect(call()).resolves.toEqual([])
  expect(http.apiFetch.mock.calls.at(-1)[0]).toBe(path)
})

it('encodes paging and refresh and bounds invalid Boss pages', async () => {
  await jobs.listBossFavoriteJobs(-2, true)
  expect(http.apiFetch).toHaveBeenCalledWith('/jobs/favorites/boss?page=1&refresh=true', {
    cache: 'no-store',
    headers: { 'Cache-Control': 'no-cache' },
  })
})

it('delegates latest analysis lookup with the correct resource kind', () => {
  jobs.latestFavoriteAnalysisTask('job')
  resume.latestResumeAnalysisTask('resume')
  expect(analysis.getLatestAnalysisTask.mock.calls).toEqual([
    ['favorite_job', 'job'],
    ['resume', 'resume'],
  ])
})

it('keeps uploaded multipart files intact and normalizes asset URLs', async () => {
  const file = new File(['content'], '简历.pdf', { type: 'application/pdf' })
  await interview.extractInterviewDocument(file)
  expect(http.apiFetch.mock.calls.at(-1)[0]).toBe('/interview/documents/extract')
  expect(http.apiFetch.mock.calls.at(-1)[1].body.get('file')).toBe(file)
  http.parseApiResponse.mockResolvedValue({ url: '/resume/assets/image.png' })
  await expect(resume.uploadResumeAsset(file)).resolves.toEqual({ url: '/api/resume/assets/image.png' })
  expect(http.apiFetch.mock.calls.at(-1)[1].body.get('file')).toBe(file)
  expect(resume.resumeDownloadUrl('a/b')).toBe('/api/resume/a%2Fb/download')
})

it.each(['AbortError', 'Error'])('Boss sync maps %s and clears its deadline', async (name) => {
  vi.useFakeTimers()
  const error = Object.assign(new Error('offline'), { name })
  http.apiFetch.mockRejectedValue(error)
  await expect(resume.syncBossOnlineResume()).rejects.toThrow(name === 'AbortError' ? '超时' : 'offline')
  expect(vi.getTimerCount()).toBe(0)
})

it('Boss sync aborts at the configured deadline and returns successful parsed results', async () => {
  vi.useFakeTimers()
  let complete
  http.apiFetch.mockImplementation(
    (_url, options) =>
      new Promise((resolve) => {
        complete = resolve
        expect(options.signal.aborted).toBe(false)
      }),
  )
  const pending = resume.syncBossOnlineResume()
  await vi.advanceTimersByTimeAsync(45000)
  expect(http.apiFetch.mock.calls.at(-1)[1].signal.aborted).toBe(true)
  complete({ status: 200 })
  await expect(pending).resolves.toEqual({ saved: true })
  expect(vi.getTimerCount()).toBe(0)
})

it.each([
  [200, '', '响应体为空'],
  [200, '<html>bad</html>', 'HTTP 200'],
  [503, '{"code":500}', 'HTTP 503'],
  [200, '{"code":4001,"data":{"url":"qr"}}', '重新扫码'],
  [400, '{"code":400,"message":"invalid"}', 'invalid'],
])('job detail classifies HTTP %s responses', async (status, body, message) => {
  http.apiFetch.mockResolvedValue({ ok: status === 200, status, text: async () => body })
  await expect(jobs.fetchJobDetail('a/b', 'https://example.test/job')).rejects.toThrow(message)
  const params = new URL(http.apiFetch.mock.calls.at(-1)[0], 'http://local').searchParams
  expect(params.get('securityId')).toBe('a/b')
  expect(params.get('url')).toBe('https://example.test/job')
})

it('job detail preserves auth metadata and returns successful data', async () => {
  http.apiFetch.mockResolvedValue({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ code: 4001, message: 'login', data: { state: 'qr' } }),
  })
  await expect(jobs.fetchJobDetail('job')).rejects.toMatchObject({ authRequired: true, authData: { state: 'qr' } })
  http.apiFetch.mockResolvedValue({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ code: 0, data: { title: 'Engineer' } }),
  })
  await expect(jobs.fetchJobDetail('job')).resolves.toEqual({ title: 'Engineer' })
})

it('job detail converts aborts into a user facing timeout', async () => {
  vi.useFakeTimers()
  http.apiFetch.mockImplementation(
    (_url, options) =>
      new Promise((_, reject) =>
        options.signal.addEventListener('abort', () =>
          reject(Object.assign(new Error('aborted'), { name: 'AbortError' })),
        ),
      ),
  )
  const pending = expect(jobs.fetchJobDetail('job')).rejects.toThrow('超时')
  await vi.advanceTimersByTimeAsync(90000)
  await pending
  expect(vi.getTimerCount()).toBe(0)
})

it('question filters preserve query values and legacy lists become pages', async () => {
  http.parseApiResponse.mockResolvedValue([{ id: 'one' }])
  const result = await interview.listQuestions({
    keyword: 'Java & Python',
    bankType: 'qa',
    category: 'backend',
    difficulty: 'easy',
    page: 2,
    size: 10,
    _ts: 123,
  })
  const params = new URL(http.apiFetch.mock.calls.at(-1)[0], 'http://local').searchParams
  expect(Object.fromEntries(params)).toEqual({
    keyword: 'Java & Python',
    bankType: 'qa',
    category: 'backend',
    difficulty: 'easy',
    page: '2',
    size: '10',
    _ts: '123',
  })
  expect(result).toEqual({ items: [{ id: 'one' }], total: 1, page: 1, size: 1, pages: 1 })
  http.parseApiResponse.mockResolvedValue([])
  expect((await interview.listQuestions()).size).toBe(20)
  await interview.getQuestionMeta({ bankType: 'qa', _ts: 123 })
  expect(http.apiFetch).toHaveBeenLastCalledWith('/interview/questions/meta?bankType=qa&_ts=123', { cache: 'no-store' })
})

it.each([true, false])('question startup retry is bounded with eventual success %s', async (success) => {
  vi.useFakeTimers()
  const error = new Error('HTTP 500 响应体为空')
  http.parseApiResponse.mockRejectedValue(error)
  if (success) http.parseApiResponse.mockRejectedValueOnce(error).mockResolvedValueOnce({ items: [] })
  const check = success
    ? expect(interview.listQuestions()).resolves.toEqual({ items: [] })
    : expect(interview.listQuestions()).rejects.toThrow('HTTP 500')
  await vi.runAllTimersAsync()
  await check
  expect(http.apiFetch).toHaveBeenCalledTimes(success ? 2 : 3)
})

it('question validation failures do not retry', async () => {
  http.parseApiResponse.mockRejectedValue(new Error('invalid query'))
  await expect(interview.listQuestions()).rejects.toThrow('invalid query')
  expect(http.apiFetch).toHaveBeenCalledOnce()
})
