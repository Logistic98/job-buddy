import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { drain } from 'stream-markdown'
import { getMarkdown, parseMarkdownToStructure } from 'markstream-vue'
import MarkdownContent from '../src/components/MarkdownContent.vue'
import { registerAssistantMarkdownFeatures } from '../src/utils/markdownFeatures'

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand')

beforeAll(() => registerAssistantMarkdownFeatures())

function mockVisibleViewport() {
  vi.spyOn(globalThis.HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    x: 0,
    y: 0,
    top: 0,
    right: 800,
    bottom: 320,
    left: 0,
    width: 800,
    height: 320,
    toJSON: () => ({}),
  })
}

afterEach(() => {
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
  else delete navigator.clipboard
  if (originalExecCommand) Object.defineProperty(document, 'execCommand', originalExecCommand)
  else delete document.execCommand
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('assistant Markdown features', () => {
  it('uses the open-source parser for standard inline LaTeX delimiters', () => {
    const nodes = parseMarkdownToStructure(
      String.raw`当 \( a \neq 0 \) 时，方程 \( ax^2 + bx + c = 0 \)。`,
      getMarkdown(),
      {
        final: true,
        streamParse: false,
      },
    )
    const inlineMath = nodes.flatMap((node) => node.children || []).filter((node) => node.type === 'math_inline')

    expect(inlineMath.map((node) => node.content.trim())).toEqual(['a \\neq 0', 'ax^2 + bx + c = 0'])
  })

  it('escapes untrusted assistant HTML', async () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: '<img src=x onerror="globalThis.compromised=true"><script>alert(1)</script>',
        customId: 'job-chat',
      },
    })

    await flushPromises()

    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=')
    wrapper.unmount()
  })

  it('renders Mermaid diagrams and inline and block LaTeX', async () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: [
          String.raw`行内公式 $E=mc^2$

$$
\sum_{i=1}^{n} i=\frac{n(n+1)}{2}
$$`,
          '```mermaid',
          'graph LR',
          '    U[用户] --> E[智能引擎]',
          '```',
        ].join('\n\n'),
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await flushPromises()
    await expect
      .poll(
        () => {
          const mermaid = wrapper.find('[data-markstream-mermaid="1"]')
          return mermaid.exists() ? mermaid.attributes('data-markstream-mode') : ''
        },
        { timeout: 5000 },
      )
      .toBe('preview')

    expect(wrapper.find('[data-mermaid-wrapper] svg').exists()).toBe(true)
    expect(wrapper.findAll('.katex')).toHaveLength(2)
    expect(wrapper.find('.katex-display').exists()).toBe(true)
    expect(wrapper.find('.assistant-math-block math annotation').text()).toContain('\\frac')
    wrapper.unmount()
  }, 10000)

  it('renders standard Mermaid fences and compact LaTeX formulas with open-source renderers', async () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: [
          '## Mermaid 流程图示例',
          '',
          '```mermaid',
          'graph TD',
          '  A[开始] --> B{条件判断}',
          '  B -->|是| C[执行操作]',
          '  B -->|否| D[执行另操作]',
          '  C --> E[结束]',
          '  D --> E',
          '```',
          '',
          '当 \\( a \\neq 0 \\) 时，方程 \\( ax^2 + bx + c = 0 \\) 的解为：',
          '',
          '$$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}$$',
          '',
          '$$e^{i\\pi} + 1 = 0$$',
        ].join('\n'),
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await flushPromises()
    await expect
      .poll(
        () => ({
          mermaid: wrapper.find('[data-mermaid-wrapper] svg').exists(),
          math: wrapper.findAll('.katex').length,
          displayMath: wrapper.findAll('.katex-display').length,
        }),
        { timeout: 5000 },
      )
      .toEqual({ mermaid: true, math: 4, displayMath: 2 })
    wrapper.unmount()
  }, 10000)

  it('renders common assistant list formulas through the eager KaTeX component', async () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: [
          '- **贝叶斯定理：**',
          String.raw`  $$P(A|B) = \frac{P(B|A) \cdot P(A)}{P(B)}$$`,
          '',
          '- **Transformer 注意力机制：**',
          String.raw`  $$\text{Attention}(Q, K, V) = \text{softmax}\left(\frac{QK^T}{\sqrt{d_k}}\right)V$$`,
          '',
          '- **均方误差损失函数：**',
          String.raw`  $$\mathcal{L}_{\text{MSE}} = \frac{1}{n}\sum_{i=1}^{n}(y_i - \hat{y}_i)^2$$`,
        ].join('\n'),
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await flushPromises()

    expect(wrapper.findAll('.assistant-math-block')).toHaveLength(3)
    expect(wrapper.findAll('.assistant-math-block .katex-display')).toHaveLength(3)
    expect(wrapper.text()).not.toContain('$$')
    expect(wrapper.find('math annotation').text()).toContain('\\frac')
    wrapper.unmount()
  })

  it('syntax-highlights and copies fenced code from the assistant renderer', async () => {
    mockVisibleViewport()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const wrapper = mount(MarkdownContent, {
      props: {
        content: '```javascript\nconst answer = 42\nconsole.log(answer)\n```',
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await flushPromises()
    await expect.poll(() => wrapper.find('.code-fallback-plain .shiki').exists()).toBe(true)
    await drain()
    await flushPromises()
    await expect
      .poll(
        () => wrapper.find('.code-block-container .shiki:not(.shiki-fallback) span[class^="smd-token-"]').exists(),
        {
          timeout: 5000,
        },
      )
      .toBe(true)

    const codeBlock = wrapper.get('.code-block-container')
    expect(codeBlock.get('.shiki').text()).toContain('const answer = 42')
    await codeBlock.get('button[aria-label="复制代码"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('const answer = 42\nconsole.log(answer)')
    expect(codeBlock.get('.assistant-code-copy').text()).toBe('已复制')
    wrapper.unmount()
  }, 10000)

  it('renders plain-text fences as readable output instead of a code card', async () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: "执行结果：\n\n```text\n字符串 'JobBuddy' 中字母 'd' 的出现次数为：2\n```",
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await flushPromises()

    expect(wrapper.find('.code-block-container').exists()).toBe(false)
    expect(wrapper.get('.assistant-plain-text').text()).toBe("字符串 'JobBuddy' 中字母 'd' 的出现次数为：2")
    wrapper.unmount()
  })

  it('falls back when the Clipboard API rejects the copy request', async () => {
    mockVisibleViewport()
    const writeText = vi.fn().mockRejectedValue(new DOMException('Permission denied', 'NotAllowedError'))
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    })
    const wrapper = mount(MarkdownContent, {
      props: {
        content: '```python\nprint("ok")\n```',
        customId: 'job-chat',
      },
      attachTo: document.body,
    })

    await expect.poll(() => wrapper.find('.assistant-code-copy').exists()).toBe(true)
    await wrapper.get('.assistant-code-copy').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('print("ok")')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(wrapper.get('.assistant-code-copy').text()).toBe('已复制')
    wrapper.unmount()
  })
})
