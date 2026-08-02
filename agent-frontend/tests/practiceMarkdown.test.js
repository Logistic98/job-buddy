import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PracticeMarkdown from '../src/components/interview/PracticeMarkdown.vue'

async function renderMarkdown(content, props = {}) {
  const wrapper = mount(PracticeMarkdown, {
    props: { content, customId: 'practice-markdown-test', ...props },
    attachTo: document.body,
  })
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 80))
  await flushPromises()
  return wrapper
}

describe('PracticeMarkdown', () => {
  it('renders fenced code and lists instead of raw Markdown markers', async () => {
    const wrapper = await renderMarkdown('示例：\n\n```python\nprint("ok")\n```\n\n- 第一步\n- 第二步')

    await expect
      .poll(
        () => ({
          hasCodeBlock: wrapper.find('pre').exists(),
          hasCopyButton: wrapper.find('.practice-code-copy').exists(),
          listItemCount: wrapper.findAll('li').length,
        }),
        { timeout: 5000 },
      )
      .toEqual({ hasCodeBlock: true, hasCopyButton: true, listItemCount: 2 })

    expect(wrapper.find('pre').exists()).toBe(true)
    expect(wrapper.find('pre code').text()).toContain('print("ok")')
    expect(wrapper.find('.practice-code-copy').text()).toBe('复制代码')
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('```python')
    wrapper.unmount()
  })

  it('escapes raw HTML from question content', async () => {
    const wrapper = await renderMarkdown('<script>window.practiceUnsafe = true</script>\n\n**安全内容**')

    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>')
    expect(wrapper.find('strong').text()).toBe('安全内容')
    wrapper.unmount()
  })

  it('renders Mermaid fences as diagrams instead of ordinary code blocks', async () => {
    const wrapper = await renderMarkdown(`\`\`\`mermaid
graph LR
    U[用户] --> FE[Vue 3 工作台]
    FE --> BE[Spring Boot Backend]
\`\`\``)
    await expect
      .poll(() => wrapper.find('[data-markstream-mermaid="1"]').attributes('data-markstream-mode'), { timeout: 5000 })
      .toBe('preview')

    const mermaidBlock = wrapper.find('[data-markstream-mermaid="1"]')
    expect(mermaidBlock.find('[data-mermaid-wrapper] svg').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders inline and block LaTeX with KaTeX', async () => {
    const wrapper = await renderMarkdown(
      String.raw`行内公式 $E=mc^2$

$$
\sum_{i=1}^{n} i=\frac{n(n+1)}{2}
$$`,
    )

    expect(wrapper.find('.katex').exists()).toBe(true)
    expect(wrapper.find('.katex-display').exists()).toBe(true)
    expect(wrapper.find('.assistant-math-block math annotation').text()).toContain('\\frac')
    wrapper.unmount()
  })

  it('keeps Mermaid and LaTeX markers inside ordinary code fences as source text', async () => {
    const wrapper = await renderMarkdown(`\`\`\`\`text
\`\`\`mermaid
graph LR
$E=mc^2$
\`\`\`
\`\`\`\``)

    expect(wrapper.find('[data-markstream-mermaid="1"]').exists()).toBe(false)
    expect(wrapper.find('.katex').exists()).toBe(false)
    expect(wrapper.find('pre code').text()).toContain('```mermaid')
    expect(wrapper.find('pre code').text()).toContain('$E=mc^2$')
    wrapper.unmount()
  })

  it('treats a dash line after plain text as a thematic break instead of a setext heading', async () => {
    const wrapper = await renderMarkdown('一句话：这是普通正文。\n---')

    expect(wrapper.find('h2').exists()).toBe(false)
    expect(wrapper.find('p').text()).toBe('一句话：这是普通正文。')
    expect(wrapper.find('hr').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders the configured empty state when no answer is maintained', async () => {
    const wrapper = await renderMarkdown('   ', { emptyText: '未维护参考答案' })

    expect(wrapper.find('.practice-markdown').exists()).toBe(false)
    expect(wrapper.find('.practice-markdown-empty').text()).toBe('未维护参考答案')
    wrapper.unmount()
  })
})
