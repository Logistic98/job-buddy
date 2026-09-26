import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import ChatView from '../../src/views/ChatView.vue'
import JobsView from '../../src/views/JobsView.vue'

const mocks = vi.hoisted(() => ({ chat: null, resume: null, push: vi.fn() }))
vi.mock('../../src/stores/chat', () => ({ useChatStore: () => mocks.chat }))
vi.mock('../../src/stores/resume', () => ({ useResumeStore: () => mocks.resume }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('../../src/components/ChatPanel.vue', () => ({
  default: {
    name: 'ChatPanel',
    props: ['resumeId', 'resumeName'],
    emits: ['select-resume'],
    template: '<button @click="$emit(\'select-resume\')">选择简历</button>',
  },
}))
vi.mock('../../src/components/ChatHistorySidebar.vue', () => ({
  default: { name: 'ChatHistorySidebar', emits: ['new-chat', 'open-chat'], template: '<aside />' },
}))
vi.mock('../../src/components/JobCardList.vue', () => ({
  default: { name: 'JobCardList', props: ['mode'], template: '<div>{{ mode }}</div>' },
}))
vi.mock('../../src/api/resume', () => ({ resumeThumbnailUrl: (id) => `/thumb/${id}` }))

beforeEach(() => {
  mocks.chat = reactive({ sessionId: 'session', newSession: vi.fn() })
  mocks.resume = reactive({
    current: null,
    items: [],
    loading: false,
    uploading: false,
    error: '',
    load: vi.fn().mockResolvedValue(),
    upload: vi.fn().mockResolvedValue(),
    select: vi.fn(),
  })
})

it('opens an empty picker, supports loading, and links to resume management', async () => {
  const wrapper = mount(ChatView)
  wrapper.findComponent({ name: 'ChatHistorySidebar' }).vm.$emit('new-chat')
  expect(mocks.chat.newSession).toHaveBeenCalledOnce()
  wrapper.findComponent({ name: 'ChatHistorySidebar' }).vm.$emit('open-chat', 'session')
  await wrapper.getComponent({ name: 'ChatPanel' }).trigger('click')
  expect(mocks.resume.load).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('暂无简历')
  mocks.resume.loading = true
  await flushPromises()
  expect(wrapper.text()).toContain('正在加载简历')
  await wrapper.get('.resume-picker-head-actions button').trigger('click')
  expect(mocks.push).toHaveBeenCalledWith('/resumes')
  expect(wrapper.find('.modal-mask').exists()).toBe(false)
})

it('renders resume labels, fallback dates and selected context, then selects a resume', async () => {
  mocks.resume.items = [
    {
      resumeId: 'one',
      originalName: 'One.pdf',
      uploadedAt: '2026-01-01T00:00:00Z',
      parsed: { folder: 'Work', version: 'v2', labels: [{ label: 'Java' }, { name: 'Python' }, 'AI', 'SQL'] },
    },
    { originalName: '', suffix: 'pdf', uploadedAt: 'invalid', parsed: { manageTags: 'A， B A' } },
    { resumeId: 'three', parsed: {} },
  ]
  mocks.resume.current = mocks.resume.items[0]
  const wrapper = mount(ChatView)
  expect(wrapper.getComponent({ name: 'ChatPanel' }).props('resumeName')).toBe('One.pdf')
  await wrapper.getComponent({ name: 'ChatPanel' }).trigger('click')
  expect(wrapper.text()).toContain('版本 v2')
  expect(wrapper.text()).toContain('未分组')
  expect(wrapper.text()).toContain('未知时间')
  expect(wrapper.text()).toContain('+1')
  expect(wrapper.get('img').attributes('src')).toBe('/thumb/one')
  expect(wrapper.findAll('.resume-picker-item')[1].text()).toContain('PDF')
  await wrapper.findAll('.resume-picker-actions button')[2].trigger('click')
  expect(mocks.resume.select).toHaveBeenCalledWith(mocks.resume.items[2])
  expect(wrapper.find('.modal-mask').exists()).toBe(false)
})

it('validates uploaded PDFs and passes the active session to upload', async () => {
  mocks.resume.load.mockRejectedValue(new Error('offline'))
  mocks.resume.upload.mockRejectedValue(new Error('offline'))
  const wrapper = mount(ChatView)
  await wrapper.getComponent({ name: 'ChatPanel' }).trigger('click')
  await flushPromises()
  const input = wrapper.get('input[type=file]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [] })
  await input.trigger('change')
  expect(mocks.resume.upload).not.toHaveBeenCalled()
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [new File(['bad'], 'bad.txt', { type: 'text/plain' })],
  })
  await input.trigger('change')
  expect(mocks.resume.error).not.toBe('')
  expect(mocks.resume.upload).not.toHaveBeenCalled()
  const file = new File(['pdf'], 'resume.pdf', { type: 'application/pdf' })
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
  expect(mocks.resume.upload).toHaveBeenCalledWith(file, 'session')
  expect(input.element.value).toBe('')
  await wrapper.get('.close').trigger('click')
  expect(wrapper.find('.modal-mask').exists()).toBe(false)
})

it('jobs view explicitly requests favorite jobs', () => {
  const wrapper = mount(JobsView)
  expect(wrapper.getComponent({ name: 'JobCardList' }).props('mode')).toBe('favorites')
})
