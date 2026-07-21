import { describe, expect, it } from 'vitest'
import {
  mergeResumePickerDetail,
  resumePickerSearchText,
  resumePickerSkills,
  resumePickerSummary,
  resumePickerTitle,
} from '../src/utils/resumePicker'

describe('resume picker display', () => {
  it('always uses the original file name instead of the parsed person name', () => {
    const item = {
      resumeId: 'resume-1',
      originalName: 'Java后端-5年经验.pdf',
      parsed: { name: '张三' },
    }

    expect(resumePickerTitle(item)).toBe('Java后端-5年经验.pdf')
  })

  it('supports current and historical summary fields', () => {
    expect(resumePickerSummary({ parsed: { summary: '当前摘要' } })).toBe('当前摘要')
    expect(resumePickerSummary({ parsed: { personalAdvantage: '历史摘要' } })).toBe('历史摘要')
    expect(resumePickerSummary({ parsed: { self_evaluation: '自我评价摘要' } })).toBe('自我评价摘要')
  })

  it('builds a readable summary when no explicit summary exists', () => {
    const item = {
      parseStatus: 'success',
      parsed: {
        years_experience: '6年',
        expected_titles: ['Java开发', '架构师'],
        skills: ['Java', 'Spring Boot', 'MySQL'],
      },
    }

    expect(resumePickerSummary(item)).toBe(
      '6年工作经验；求职方向：Java开发、架构师；核心技能：Java、Spring Boot、MySQL',
    )
  })

  it('merges loaded detail without losing management metadata', () => {
    const listItem = {
      resumeId: 'resume-1',
      originalName: 'resume.pdf',
      parsed: { folder: '后端', labels: ['Java'] },
    }
    const result = mergeResumePickerDetail(listItem, {
      'resume-1': { resumeId: 'resume-1', parsed: { summary: '完整摘要', skills: ['Java', 'Python'] } },
    })

    expect(result.parsed.folder).toBe('后端')
    expect(result.parsed.summary).toBe('完整摘要')
    expect(resumePickerSkills(result)).toEqual(['Java', 'Python'])
  })

  it('includes file name, summary and skills in search text', () => {
    const text = resumePickerSearchText({
      originalName: '高级Java工程师.pdf',
      parsed: { summary: '支付平台研发', skills: ['Spring Cloud'] },
    })

    expect(text).toContain('高级java工程师.pdf')
    expect(text).toContain('支付平台研发')
    expect(text).toContain('spring cloud')
  })
})
