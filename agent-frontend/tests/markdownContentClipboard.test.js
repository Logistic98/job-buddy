import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MarkdownContent from '../src/components/MarkdownContent.vue'

const mocks = vi.hoisted(() => ({
  copyText: vi.fn(),
}))

vi.mock('../src/utils/clipboard', () => ({
  copyText: mocks.copyText,
}))

vi.mock('../src/utils/markdownFeatures', () => ({
  markdownMermaidProps: (value) => value,
  normalizeMarkdownFeatures: (value) => String(value ?? ''),
}))

vi.mock('markstream-vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MarkdownRender',
      emits: ['copy'],
      setup(_, { emit }) {
        return () =>
          h(
            'button',
            {
              class: 'vendor-copy',
              onClick: () => emit('copy', 'const answer = 42'),
            },
            '复制',
          )
      },
    }),
  }
})

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')

afterEach(() => {
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
  else delete navigator.clipboard
  vi.clearAllMocks()
})

describe('MarkdownContent clipboard compatibility', () => {
  it('falls back when the renderer emits copy on an HTTP origin', async () => {
    delete navigator.clipboard
    mocks.copyText.mockResolvedValue(true)
    const wrapper = mount(MarkdownContent, {
      props: { content: '```js\\nconst answer = 42\\n```', customId: 'http-copy' },
    })

    await wrapper.get('.vendor-copy').trigger('click')
    await flushPromises()

    expect(mocks.copyText).toHaveBeenCalledWith('const answer = 42')
  })

  it('avoids copying twice when the native clipboard API is available', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn() },
    })
    const wrapper = mount(MarkdownContent, {
      props: { content: '```js\\nconst answer = 42\\n```', customId: 'secure-copy' },
    })

    await wrapper.get('.vendor-copy').trigger('click')
    await flushPromises()

    expect(mocks.copyText).not.toHaveBeenCalled()
  })
})
