import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
import { uploadChatAttachment } from '../src/api/chat'
import { useChatStore } from '../src/stores/chat'

describe('ChatPanel attachment composer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(uploadChatAttachment).mockReset()
    vi.mocked(uploadChatAttachment).mockResolvedValue({})
  })

  it('uses a plus menu and explains the supported local file contract', async () => {
    const wrapper = mount(ChatPanel)

    const addButton = wrapper.get('.composer-add-button')
    expect(addButton.attributes('aria-expanded')).toBe('false')

    await addButton.trigger('click')

    expect(addButton.attributes('aria-expanded')).toBe('true')
    const menu = wrapper.get('.attachment-menu')
    expect(menu.text()).toContain('添加文件')
    expect(menu.text()).toContain('从电脑上传')
    expect(menu.text()).toContain('PDF、DOC、DOCX、TXT、MD')
    expect(menu.text()).toContain('128MB')

    wrapper.unmount()
  })

  it('shows an oversized-file error next to the attachment area without truncation', async () => {
    const wrapper = mount(ChatPanel)
    const input = wrapper.get('.attachment-file-input')
    const oversized = new File(['x'], '智能问答组件项目-平台介绍.pdf', { type: 'application/pdf' })
    Object.defineProperty(oversized, 'size', { value: 128 * 1024 * 1024 + 1 })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [oversized] })

    await input.trigger('change')

    const error = wrapper.get('.composer-file-notice')
    expect(error.attributes('role')).toBe('alert')
    expect(error.text()).toContain('未添加“智能问答组件项目-平台介绍.pdf”：文件大小不能超过 128MB')
    expect(wrapper.find('.composer-attachment').exists()).toBe(false)

    wrapper.unmount()
  })

  it('renders uploaded files as distinct ready cards above the input', async () => {
    const wrapper = mount(ChatPanel)
    const chat = useChatStore()
    chat.pendingAttachments = [
      {
        localId: 'local-ready',
        attachmentId: 'att-ready',
        fileName: '项目介绍.pdf',
        suffix: 'pdf',
        sizeBytes: 2048,
        status: 'ready',
        parseStatus: 'ready',
      },
    ]
    await nextTick()

    const card = wrapper.get('.composer-attachment.ready')
    expect(card.get('.composer-attachment-type').text()).toBe('PDF')
    expect(card.get('.composer-attachment-copy').text()).toContain('项目介绍.pdf')
    expect(card.get('.composer-attachment-copy').text()).toContain('已就绪 · 2 KB')
    expect(wrapper.get('.composer-status').text()).toContain('1 个文件已就绪')

    wrapper.unmount()
  })

  it('renders sent attachments as compact file rows in the user message', async () => {
    const wrapper = mount(ChatPanel)
    const chat = useChatStore()
    chat.messages = [
      {
        id: 'turn-with-attachment',
        role: 'user',
        content: '这个项目的主要功能是啥',
        attachments: [
          {
            attachmentId: 'att-sent',
            fileName: '智能问答组件项目-平台介绍.md',
            suffix: 'md',
          },
        ],
      },
    ]
    await nextTick()

    const attachment = wrapper.get('.message-attachment')
    expect(attachment.get('.message-attachment-type').text()).toBe('MD')
    expect(attachment.get('.message-attachment-name').text()).toBe('智能问答组件项目-平台介绍.md')
    expect(attachment.get('.message-attachment-name').attributes('title')).toBe('智能问答组件项目-平台介绍.md')
    expect(wrapper.get('.user-message-text').text()).toBe('这个项目的主要功能是啥')

    wrapper.unmount()
  })

  it('never repeats submitted attachments in the composer while the request is running', async () => {
    const wrapper = mount(ChatPanel)
    const chat = useChatStore()
    const attachments = [
      {
        localId: 'local-first',
        attachmentId: 'att-first',
        fileName: '智能问答组件项目-平台介绍.md',
        suffix: 'md',
        sizeBytes: 8192,
        status: 'ready',
      },
      {
        localId: 'local-second',
        attachmentId: 'att-second',
        fileName: '智能求职协同平台-介绍材料.md',
        suffix: 'md',
        sizeBytes: 45056,
        status: 'ready',
      },
    ]
    chat.messages = [
      {
        id: 'turn-running',
        role: 'user',
        content: '这两个项目的共同点和不同点',
        attachments,
      },
    ]
    // 模拟旧页面热更新前遗留的待发送状态，组件仍必须以“已发送消息”为唯一展示位置。
    chat.pendingAttachments = attachments
    chat.loading = true
    await nextTick()

    expect(wrapper.findAll('.message-attachment')).toHaveLength(2)
    expect(wrapper.find('.composer-attachment').exists()).toBe(false)

    wrapper.unmount()
  })

  it('enables sending as soon as an uploaded attachment becomes ready', async () => {
    let finishUpload
    vi.mocked(uploadChatAttachment).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishUpload = resolve
        }),
    )
    const wrapper = mount(ChatPanel)
    const input = wrapper.get('.attachment-file-input')
    const file = new File(['project'], '项目介绍.md', { type: 'text/markdown' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await wrapper.get('textarea').setValue('这个项目讲了啥')

    const uploadPromise = input.trigger('change')
    await nextTick()

    expect(wrapper.get('.composer-status').text()).toContain('正在上传并解析附件')
    expect(wrapper.get('.composer-send-button').attributes('disabled')).toBeDefined()

    finishUpload({
      attachmentId: 'att-ready',
      fileName: '项目介绍.md',
      suffix: 'md',
      sizeBytes: file.size,
      parseStatus: 'ready',
    })
    await uploadPromise
    await nextTick()

    expect(wrapper.get('.composer-attachment').classes()).toContain('ready')
    expect(wrapper.get('.composer-status').text()).toContain('1 个文件已就绪')
    expect(wrapper.get('.composer-send-button').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('does not repeat the current resume relationship in the composer footer', () => {
    const wrapper = mount(ChatPanel, {
      props: {
        resumeId: 'resume-1',
        resumeName: '示例候选人-大模型应用开发岗-求职简历.pdf',
      },
    })

    expect(wrapper.find('.composer-status').exists()).toBe(false)

    wrapper.unmount()
  })
})
