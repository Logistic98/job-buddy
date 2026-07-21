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

  it('renders the configured empty state when no answer is maintained', async () => {
    const wrapper = await renderMarkdown('   ', { emptyText: '未维护参考答案' })

    expect(wrapper.find('.practice-markdown').exists()).toBe(false)
    expect(wrapper.find('.practice-markdown-empty').text()).toBe('未维护参考答案')
    wrapper.unmount()
  })
})
