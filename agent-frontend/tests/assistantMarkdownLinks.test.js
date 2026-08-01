import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MarkdownContent from '../src/components/MarkdownContent.vue'
import { normalizeAssistantMarkdown } from '../src/utils/chatHelpers'

describe('assistant Markdown links', () => {
  it('repairs a model URL with a dangling Markdown opening bracket', async () => {
    const url = 'https://www.anthropic.com/research/global-workspace'
    const content = normalizeAssistantMarkdown(`获取链接：[${url}`)
    const wrapper = mount(MarkdownContent, {
      props: { content, customId: 'malformed-remote-link' },
    })

    await vi.waitFor(() => {
      expect(wrapper.find('a').exists()).toBe(true)
    })
    expect(content).toBe(`获取链接：[${url}](${url})`)
    expect(wrapper.text()).toBe(`获取链接：${url}`)
    expect(wrapper.get('a').attributes('href')).toBe(url)

    wrapper.unmount()
  })

  it('keeps an existing self-labelled HTTPS Markdown link unchanged', () => {
    const url = 'https://www.anthropic.com/research/global-workspace'

    expect(normalizeAssistantMarkdown(`[${url}](${url})`)).toBe(`[${url}](${url})`)
  })

  it('repairs a bracket-only URL without leaving an extra closing bracket', () => {
    const url = 'https://www.anthropic.com/research/global-workspace'

    expect(normalizeAssistantMarkdown(`[${url}]`)).toBe(`[${url}](${url})`)
  })

  it('does not rewrite an image label that happens to contain a URL', () => {
    const url = 'https://www.anthropic.com/research/global-workspace'

    expect(normalizeAssistantMarkdown(`![${url}]`)).toBe(`![${url}]`)
  })

  it('does not turn a local attachment file name into a clickable link', async () => {
    const content = normalizeAssistantMarkdown(
      '根据你上传的《智能问答组件项目-平台介绍.md》和[项目介绍.md](项目介绍.md)总结内容。',
    )
    const wrapper = mount(MarkdownContent, {
      props: { content, customId: 'local-file-reference' },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('智能问答组件项目-平台介绍.md')
    })
    expect(wrapper.find('a').exists()).toBe(false)

    wrapper.unmount()
  })
})
