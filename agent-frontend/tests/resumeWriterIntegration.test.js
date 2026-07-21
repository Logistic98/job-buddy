import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../src/api/resume', () => ({
  createWriterVersion: vi.fn(),
}))

import { createWriterVersion } from '../src/api/resume'
import { backupWriterState } from '../src/composables/useResumeWriterImport'

describe('resume writer local import backup', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    createWriterVersion.mockResolvedValue({ versionId: 'v1' })
  })

  it('backs up the current draft without a resume library relation', async () => {
    const current = {
      markdown: '# Old',
      openedResumeId: 'removed-non-pdf-resume',
      analysisContext: { resumeId: 'removed-non-pdf-resume' },
      fontSize: '12px',
    }

    const saved = await backupWriterState(current, '导入前备份')

    expect(saved).toBe(true)
    expect(createWriterVersion).toHaveBeenCalledWith({
      source: 'import_backup',
      title: '导入前备份',
      resumeId: '',
      snapshot: JSON.stringify({
        markdown: '# Old',
        openedResumeId: '',
        analysisContext: null,
        fontSize: '12px',
      }),
    })
  })

  it('skips backup for an empty draft', async () => {
    await expect(backupWriterState({ markdown: '   ' }, '导入前备份')).resolves.toBe(false)
    expect(createWriterVersion).not.toHaveBeenCalled()
  })
})
