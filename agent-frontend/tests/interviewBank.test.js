import { describe, it, expect } from 'vitest'
import {
  tagLabels,
  splitCleanTags,
  defaultOptions,
  isChoiceType,
  isMultiChoice,
  isCodingQuestion,
  questionStem,
  answerContent,
  optionItems,
  normalizeCodingLanguage,
  buildDefaultTemplate,
  defaultSignature,
  extractFunctionName,
  difficultyClass,
  displayTitle,
  requireText,
  formatRemainingTime,
} from '../src/utils/interviewBank'

describe('tag helpers', () => {
  it('normalizes mixed tag shapes and maps leetcode to 算法', () => {
    expect(tagLabels({ tags: ['Java', { label: 'LeetCode' }, 'Java'] })).toEqual(['Java', '算法'])
  })
  it('splits and de-duplicates identical free-text tags', () => {
    expect(splitCleanTags('Java、Spring  Spring')).toEqual(['Java', 'Spring'])
  })
})

describe('choice and coding type guards', () => {
  it('detects choice types', () => {
    expect(isChoiceType('单选')).toBe(true)
    expect(isChoiceType('简答')).toBe(false)
    expect(isMultiChoice({ questionType: '多选' })).toBe(true)
  })
  it('detects coding questions by bank type or question type', () => {
    expect(isCodingQuestion({ bankType: 'leetcode' })).toBe(true)
    expect(isCodingQuestion({ questionType: '编程题' })).toBe(true)
    expect(isCodingQuestion({ questionType: '单选' })).toBe(false)
  })
})

describe('answer formatting', () => {
  it('removes only a leading answer marker from plain and markdown answers', () => {
    expect(answerContent('答： Transformer 使用自注意力机制')).toBe('Transformer 使用自注意力机制')
    expect(answerContent('**答：**\n\n正文')).toBe('正文')
    expect(answerContent('正文中的答：需要保留')).toBe('正文中的答：需要保留')
  })
})

describe('option parsing', () => {
  it('builds default option keys A-D', () => {
    expect(defaultOptions().map((o) => o.key)).toEqual(['A', 'B', 'C', 'D'])
  })
  it('strips trailing options from the stem and extracts them', () => {
    const item = { content: '以下哪个正确？\n\nA. 选项一\nB. 选项二' }
    expect(questionStem(item)).toBe('以下哪个正确？')
    expect(optionItems(item)).toEqual([
      { key: 'A', text: '选项一' },
      { key: 'B', text: '选项二' },
    ])
  })
})

describe('coding language and templates', () => {
  it('normalizes language aliases', () => {
    expect(normalizeCodingLanguage('py')).toBe('python')
    expect(normalizeCodingLanguage('node')).toBe('javascript')
    expect(normalizeCodingLanguage('JAVA')).toBe('java')
    expect(normalizeCodingLanguage('unknown')).toBe('python')
  })
  it('builds default templates per language', () => {
    expect(buildDefaultTemplate('foo', 'python')).toContain('def foo(*args)')
    expect(buildDefaultTemplate('foo', 'java')).toContain('public Object foo')
    expect(buildDefaultTemplate('foo', 'javascript')).toContain('function foo()')
  })
  it('builds signatures per language', () => {
    expect(defaultSignature('foo', 'python')).toBe('def foo(*args)')
    expect(defaultSignature('foo', 'javascript')).toBe('function foo(...args)')
  })
  it('extracts the function name from a template', () => {
    expect(extractFunctionName('def solve(a, b):', 'python')).toBe('solve')
    expect(extractFunctionName('function run(x) {}', 'javascript')).toBe('run')
    expect(extractFunctionName('public int add(int a)', 'java')).toBe('add')
  })
})

describe('misc formatting', () => {
  it('maps difficulty to a css class', () => {
    expect(difficultyClass('简单')).toBe('ok')
    expect(difficultyClass('困难')).toBe('danger')
    expect(difficultyClass('中等')).toBe('warn')
  })
  it('falls back to an indexed title', () => {
    expect(displayTitle({ title: '' }, 2)).toBe('未命名题目 3')
    expect(displayTitle({ title: 'A' }, 0)).toBe('A')
  })
  it('throws when required text is blank', () => {
    expect(() => requireText('  ', 'boom')).toThrow('boom')
    expect(() => requireText('ok', 'boom')).not.toThrow()
  })
  it('formats remaining seconds as mm:ss', () => {
    expect(formatRemainingTime(75)).toBe('01:15')
    expect(formatRemainingTime(-5)).toBe('00:00')
  })
})
