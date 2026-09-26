import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import ChatHistorySidebar from '../../src/components/ChatHistorySidebar.vue'

const mocks = vi.hoisted(() => ({ chat: null }))
vi.mock('../../src/stores/chat', () => ({ useChatStore: () => mocks.chat }))

beforeEach(() => {
  mocks.chat = reactive({
    sessions: [],
    sessionId: 'one',
    loadSessions: vi.fn().mockResolvedValue(),
    openSession: vi.fn().mockResolvedValue(),
    removeSession: vi.fn().mockResolvedValue(),
  })
})

function mountSidebar() {
  return mount(ChatHistorySidebar, { global: { stubs: { teleport: true } } })
}

it('loads history and offers a new chat from the empty search', async () => {
  const wrapper = mountSidebar()
  expect(mocks.chat.loadSessions).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('暂无历史会话')
  await wrapper.get('.primary-btn').trigger('click')
  expect(wrapper.emitted('new-chat')).toHaveLength(1)
  await wrapper.get('.history-search-trigger').trigger('click')
  expect(wrapper.find('.history-search-modal').exists()).toBe(true)
  await wrapper.get('.history-search-new').trigger('click')
  expect(wrapper.emitted('new-chat')).toHaveLength(2)
  expect(wrapper.find('.history-search-modal').exists()).toBe(false)
})

it('searches by title, identity and date, opens sessions, and dismisses search', async () => {
  mocks.chat.sessions = [
    { sessionId: 'one', title: 'Java Interview', updatedAt: '2026-01-01' },
    { sessionId: 'two', title: '' },
  ]
  mocks.chat.loadSessions.mockRejectedValue(new Error('offline'))
  mocks.chat.openSession.mockRejectedValue(new Error('offline'))
  const wrapper = mountSidebar()
  await flushPromises()
  expect(wrapper.get('.history-row').classes()).toContain('active')
  await wrapper.get('.history-row').trigger('click')
  expect(mocks.chat.openSession).toHaveBeenCalledWith('one')
  expect(wrapper.emitted('open-chat')[0]).toEqual(['one'])
  await wrapper.get('.history-search-trigger').trigger('click')
  await wrapper.get('input').setValue('java')
  expect(wrapper.findAll('.history-search-result')).toHaveLength(1)
  await wrapper.get('input').setValue('two')
  expect(wrapper.get('.history-search-result').text()).toContain('新会话')
  await wrapper.get('input').setValue('missing')
  expect(wrapper.text()).toContain('没有匹配的会话')
  await wrapper.get('input').setValue('one')
  await wrapper.get('.history-search-result').trigger('click')
  expect(wrapper.find('.history-search-modal').exists()).toBe(false)
  await wrapper.get('.history-search-trigger').trigger('click')
  await wrapper.get('input').trigger('keydown', { key: 'Escape' })
  expect(wrapper.find('.history-search-modal').exists()).toBe(false)
})

it('requires confirmation and keeps deletion pending until the service finishes', async () => {
  mocks.chat.sessions = [{ sessionId: 'one', title: 'First' }]
  let finish
  mocks.chat.removeSession.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const wrapper = mountSidebar()
  await wrapper.get('.row-delete').trigger('click')
  expect(mocks.chat.removeSession).not.toHaveBeenCalled()
  await wrapper.get('.history-delete-actions .secondary-btn').trigger('click')
  expect(wrapper.find('.history-delete-modal').exists()).toBe(false)
  await wrapper.get('.row-delete').trigger('click')
  await wrapper.get('.danger-btn').trigger('click')
  expect(mocks.chat.removeSession).toHaveBeenCalledWith('one')
  expect(wrapper.get('.danger-btn').attributes('disabled')).toBeDefined()
  await wrapper.get('.history-delete-modal .close').trigger('click')
  expect(wrapper.find('.history-delete-modal').exists()).toBe(true)
  finish()
  await flushPromises()
  expect(wrapper.find('.history-delete-modal').exists()).toBe(false)
})
