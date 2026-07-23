import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CodeHighlightEditor from '../src/components/interview/CodeHighlightEditor.vue'
import { detectCodeLanguage, highlightCode, normalizeHighlightLanguage } from '../src/utils/codeHighlight'

describe('codeHighlight', () => {
  it('highlights Python, Java, and JavaScript tokens', () => {
    expect(highlightCode('def solve(value):\n    return 40 + 2', 'python')).toContain('code-token-keyword')
    expect(highlightCode('public String solve() { return "ok"; }', 'java')).toContain('code-token-type')
    expect(highlightCode('const solve = async () => true', 'javascript')).toContain('code-token-keyword')
  })

  it('escapes executable HTML before adding token markup', () => {
    const highlighted = highlightCode('<script>alert("x")</script>', 'javascript')
    expect(highlighted).toContain('&lt;script&gt;')
    expect(highlighted).not.toContain('<script>')
  })

  it('normalizes common language aliases', () => {
    expect(normalizeHighlightLanguage('py')).toBe('python')
    expect(normalizeHighlightLanguage('JS')).toBe('javascript')
  })

  it('detects Python, Java, and JavaScript without requiring a language field', () => {
    expect(detectCodeLanguage('class Solution:\n    def solve(self):\n        return True')).toBe('python')
    expect(detectCodeLanguage('public class Solution { public int solve() { return 1; } }')).toBe('java')
    expect(detectCodeLanguage('const solve = (value) => value + 1')).toBe('javascript')
    expect(detectCodeLanguage('读取输入并输出结果', 'java')).toBe('java')
  })
})

describe('CodeHighlightEditor', () => {
  it('renders highlighted code while preserving a native editable textarea', async () => {
    const wrapper = mount(CodeHighlightEditor, {
      props: {
        modelValue: 'def solve(value):\n    return value',
        language: 'python',
        required: true,
        ariaLabel: '初始代码模板',
        textareaClass: 'question-code-template-textarea',
      },
    })

    expect(wrapper.attributes('data-language')).toBe('python')
    expect(wrapper.find('.code-highlight-layer').element.textContent).toBe('def solve(value):\n    return value\n')
    expect(wrapper.findAll('.code-token-keyword').map((token) => token.text())).toEqual(['def', 'return'])
    const textarea = wrapper.find('textarea')
    expect(textarea.classes()).toContain('question-code-template-textarea')
    expect(textarea.attributes('aria-required')).toBe('true')
    expect(textarea.attributes('spellcheck')).toBe('false')

    await textarea.setValue('class Solution:\n    pass')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['class Solution:\n    pass'])
  })

  it('keeps the highlight layer aligned with textarea scrolling', async () => {
    const wrapper = mount(CodeHighlightEditor, { props: { modelValue: 'line 1\nline 2', language: 'python' } })
    const textarea = wrapper.find('textarea')
    textarea.element.scrollTop = 24
    textarea.element.scrollLeft = 12
    await textarea.trigger('scroll')
    expect(wrapper.find('.code-highlight-layer').attributes('style')).toContain('translate(-12px, -24px)')
  })
})
