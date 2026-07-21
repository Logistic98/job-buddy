const RESULT_CLASS_MAP = {
  通过: 'journey-result-passed',
  未通过: 'journey-result-failed',
  待反馈: 'journey-result-pending',
  跟进中: 'journey-result-progress',
  已放弃: 'journey-result-abandoned',
}

export function journeyResultClass(result) {
  return RESULT_CLASS_MAP[String(result || '').trim()] || 'journey-result-neutral'
}
