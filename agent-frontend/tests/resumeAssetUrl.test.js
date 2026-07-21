import { describe, expect, it } from 'vitest'
import { normalizeResumeAssetUrl, resumeAssetDisplayUrl } from '../src/api/resume'

describe('resume asset urls', () => {
  it('keeps stable asset ids unchanged', () => {
    expect(normalizeResumeAssetUrl('/api/resume/assets/asset_0123456789abcdef')).toBe(
      '/api/resume/assets/asset_0123456789abcdef',
    )
  })

  it('uses same-origin cookie authentication for internal assets', () => {
    expect(resumeAssetDisplayUrl('/api/resume/assets/asset_0123456789abcdef')).toBe(
      '/api/resume/assets/asset_0123456789abcdef',
    )
  })

  it('allows supported image preview protocols', () => {
    expect(normalizeResumeAssetUrl('https://cdn.example.com/photo.png')).toBe('https://cdn.example.com/photo.png')
    expect(normalizeResumeAssetUrl('blob:https://example.com/id')).toBe('blob:https://example.com/id')
    expect(normalizeResumeAssetUrl('data:image/png;base64,AA==')).toBe('data:image/png;base64,AA==')
  })

  it('rejects executable and protocol-relative urls', () => {
    expect(normalizeResumeAssetUrl('javascript:alert(1)')).toBe('')
    expect(normalizeResumeAssetUrl('data:text/html;base64,PHNjcmlwdD4=')).toBe('')
    expect(normalizeResumeAssetUrl('//evil.example/photo.png')).toBe('')
    expect(normalizeResumeAssetUrl('ftp://evil.example/photo.png')).toBe('')
  })
})
