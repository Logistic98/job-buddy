import { createWriterVersion } from '../api/resume'

// 本地 Markdown 导入覆盖当前草稿前，先保存一条可回退版本。
export async function backupWriterState(current, title) {
  if (!String(current?.markdown || '').trim()) return false
  await createWriterVersion({
    source: 'import_backup',
    title,
    resumeId: '',
    snapshot: JSON.stringify({ ...current, openedResumeId: '', analysisContext: null }),
  })
  return true
}
