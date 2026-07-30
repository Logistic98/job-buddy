import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

vi.mock('markstream-vue', () => ({
  default: {
    props: ['content'],
    template: '<div class="markdown-stub">{{ content }}</div>',
  },
  enableKatex: vi.fn(),
  enableMermaid: vi.fn(),
}))

vi.mock('../src/api/chat', () => ({
  deleteChatAttachment: vi.fn(async () => ({})),
  deleteSession: vi.fn(async () => ({})),
  listSessionMessages: vi.fn(async () => []),
  listSessions: vi.fn(async () => []),
  streamChat: vi.fn(async () => {}),
  uploadChatAttachment: vi.fn(async () => ({})),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: vi.fn(async () => ({ status: 'logged_in' })),
}))

vi.mock('../src/api/jobs', () => ({
  cancelAnalysisTask: vi.fn(),
  deleteFavoriteJob: vi.fn(),
  fetchJobDetail: vi.fn(),
  getAnalysisTask: vi.fn(),
  latestFavoriteAnalysisTask: vi.fn(),
  listFavoriteJobs: vi.fn(),
  saveFavoriteJob: vi.fn(),
  startFavoriteAnalysisTask: vi.fn(),
  streamAnalysisTask: vi.fn(),
}))

import ChatPanel from '../src/components/ChatPanel.vue'
import { useChatStore } from '../src/stores/chat'

describe('ChatPanel composer keyboard behavior', () => {
  it('uses Enter to confirm an IME candidate before allowing Enter to send', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const chat = useChatStore()
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const composer = wrapper.get('textarea')

    await composer.setValue('联网查找open ai')
    await composer.trigger('compositionstart')
    await composer.trigger('keydown', { key: 'Enter' })

    expect(send).not.toHaveBeenCalled()
    expect(composer.element.value).toBe('联网查找open ai')

    await composer.trigger('compositionend')
    await composer.trigger('keydown', { key: 'Enter' })

    expect(send).toHaveBeenCalledOnce()
    expect(send).toHaveBeenCalledWith('联网查找open ai', '')

    wrapper.unmount()
  })

  it('honors the native composing flag when the browser omits compositionstart', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ChatPanel, { global: { plugins: [pinia] } })
    const chat = useChatStore()
    const send = vi.spyOn(chat, 'send').mockResolvedValue(true)
    const composer = wrapper.get('textarea')

    await composer.setValue('联网查找open ai')
    await composer.trigger('keydown', { key: 'Enter', isComposing: true })

    expect(send).not.toHaveBeenCalled()
    expect(composer.element.value).toBe('联网查找open ai')

    wrapper.unmount()
  })
})
