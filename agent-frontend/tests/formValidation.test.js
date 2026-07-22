import { describe, expect, it } from 'vitest'
import {
  validateCode,
  validateFile,
  validateHttpUrl,
  validateInteger,
  validateMonthRange,
  validatePassword,
  validateTags,
  validateUsername,
} from '../src/utils/formValidation'

describe('formValidation', () => {
  it('validates usernames, passwords and stable codes', () => {
    expect(validateUsername('agent_user')).toBe('agent_user')
    expect(() => validateUsername('1user')).toThrow('用户名须以字母开头')
    expect(validatePassword('Agent2026')).toBe('Agent2026')
    expect(() => validatePassword('abcdefgh')).toThrow('必须同时包含字母和数字')
    expect(validateCode('menu:read', '菜单编码')).toBe('menu:read')
    expect(() => validateCode('菜单', '菜单编码')).toThrow('菜单编码须以字母开头')
  })

  it('validates integers, URLs and month ranges', () => {
    expect(validateInteger('12', '数量', { min: 1, max: 20 })).toBe(12)
    expect(() => validateInteger('1.5', '数量', { min: 1, max: 20 })).toThrow('必须为整数')
    expect(validateHttpUrl('https://example.com/', '服务地址')).toBe('https://example.com')
    expect(() => validateHttpUrl('ftp://example.com', '服务地址')).toThrow('仅支持 HTTP 或 HTTPS')
    expect(() => validateMonthRange('2026-08', '2026-07', '项目经历')).toThrow('开始时间不能晚于结束时间')
  })

  it('validates tags and uploaded files', () => {
    expect(() => validateTags(['a', 'b'], '标签', { maxCount: 1 })).toThrow('最多添加 1 个')
    const valid = new File(['content'], 'resume.pdf', { type: 'application/pdf' })
    expect(validateFile(valid, '简历', { extensions: ['pdf'], mimeTypes: ['application/pdf'], maxBytes: 1024 })).toBe(
      valid,
    )
    const invalid = new File(['content'], 'resume.txt', { type: 'text/plain' })
    expect(() => validateFile(invalid, '简历', { extensions: ['pdf'] })).toThrow('格式不支持')
  })
})
