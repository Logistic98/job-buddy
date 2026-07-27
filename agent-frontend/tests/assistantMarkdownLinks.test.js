import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MarkdownContent from '../src/components/MarkdownContent.vue'
import { normalizeAssistantMarkdown } from '../src/utils/chatHelpers'

describe('assistant Markdown links', () => {
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
