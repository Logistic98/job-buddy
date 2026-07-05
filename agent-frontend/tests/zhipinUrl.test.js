import { describe, expect, it } from 'vitest'
import { bossDetailUrl, firstUsableJobPathId } from '../src/utils/zhipinUrl'

describe('zhipinUrl utilities', () => {
  it('prefers encryptJobId over other id fields', () => {
    expect(firstUsableJobPathId({ encryptJobId: 'abc123', id: '999' })).toBe('abc123')
  })

  it('falls back through id keys in priority order', () => {
    expect(firstUsableJobPathId({ encrypt_job_id: 'enc1', jobId: 'job1' })).toBe('enc1')
    expect(firstUsableJobPathId({ jobId: 'job1', job_id: 'job2' })).toBe('job1')
  })

  it('rejects purely numeric ids of 4+ digits as unusable path ids', () => {
    expect(firstUsableJobPathId({ id: '123456' })).toBe('')
  })

  it('accepts short numeric or alphanumeric ids', () => {
    expect(firstUsableJobPathId({ id: 'a89820b5b7ff0e700nBy3d2-FlFY' })).toBe('a89820b5b7ff0e700nBy3d2-FlFY')
  })

  it('returns empty string when no usable id field is present', () => {
    expect(firstUsableJobPathId({})).toBe('')
    expect(firstUsableJobPathId({ id: '1234567' })).toBe('')
  })

  it('builds a zhipin job detail url with securityId and lid query params', () => {
    const url = bossDetailUrl({
      encryptJobId: '3635020ea8572e420nV-2t60ElVT',
      securityId: 'sec-abc',
      lid: '8SqsQz9LiOw.search.42',
    })
    expect(url).toBe(
      'https://www.zhipin.com/job_detail/3635020ea8572e420nV-2t60ElVT.html?securityId=sec-abc&lid=8SqsQz9LiOw.search.42',
    )
  })

  it('supports snake_case security_id and listId fallbacks', () => {
    const url = bossDetailUrl({ encryptJobId: 'abc', security_id: 'sec-1', listId: 'list-1' })
    expect(url).toBe('https://www.zhipin.com/job_detail/abc.html?securityId=sec-1&lid=list-1')
  })

  it('omits the query string entirely when no securityId or lid is available', () => {
    expect(bossDetailUrl({ encryptJobId: 'abc' })).toBe('https://www.zhipin.com/job_detail/abc.html')
  })

  it('returns an empty string when the item has no usable path id', () => {
    expect(bossDetailUrl({ id: '123456', securityId: 'sec-1' })).toBe('')
  })
})
