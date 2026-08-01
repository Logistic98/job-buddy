import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { addMemory, clearMemories, deleteMemory, listMemories, updateMemory } from '../src/api/settings'
import MemorySettingsPanel from '../src/components/settings/MemorySettingsPanel.vue'

vi.mock('../src/api/settings', () => ({
  addMemory: vi.fn(),
  clearMemories: vi.fn(),
  deleteMemory: vi.fn(),
  listMemories: vi.fn().mockResolvedValue([]),
  updateMemory: vi.fn(),
}))

vi.mock('../src/composables/useScopedSettings', () => ({
  useScopedSettings: (_scope, normalize) => ({
    value: ref(normalize({})),
    loading: ref(false),
    saving: ref(false),
    error: ref(''),
    dirty: ref(false),
    load: vi.fn().mockResolvedValue(),
    save: vi.fn().mockResolvedValue(),
  }),
}))

describe('MemorySettingsPanel', () => {
  it('shows a title and description beside each memory switch', async () => {
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    const switches = wrapper.findAll('.memory-switch-field')
    expect(switches).toHaveLength(3)
    expect(switches.map((item) => item.find('.switch-text strong').text())).toEqual([
      '记忆已启用',
      '自动保存已启用',
      '按需使用已启用',
    ])
    expect(switches.map((item) => item.find('.switch-text small').text())).toEqual([
      '保存长期偏好与求职信息，并在后续任务中按需使用。',
      '从对话中识别稳定偏好，并自动沉淀为长期记忆。',
      '执行任务时检索与当前请求相关的记忆，减少重复说明。',
    ])
  })

  it('shows required markers and a red error when a new memory is incomplete', async () => {
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    expect(wrapper.find('.memory-editor select').exists()).toBe(false)
    expect(wrapper.find('.memory-editor input').attributes('aria-required')).toBe('true')
    expect(wrapper.findAll('.memory-editor-field .form-required')).toHaveLength(1)
    expect(wrapper.find('.memory-editor button').attributes()).not.toHaveProperty('disabled')
    await wrapper.find('.memory-editor button').trigger('click')
    expect(wrapper.find('.form-error-alert[role="alert"]').text()).toBe('请填写记忆内容')
  })

  it('uses Agent-focused example copy and localized memory source labels', async () => {
    listMemories.mockResolvedValueOnce([
      {
        id: 'manual-memory',
        content: '示例偏好：优先看杭州云原生平台开发岗',
        source: 'manual',
        enabled: true,
        updatedAt: '2026-07-27T11:51:00Z',
      },
      {
        id: 'automatic-memory',
        content: '关注 Agent Runtime 与评测体系',
        source: 'agent-memory',
        enabled: true,
        updatedAt: '2026-07-27T11:50:00Z',
      },
    ])

    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    const editor = wrapper.find('.memory-editor input')
    const placeholder = editor.attributes('placeholder')
    expect(placeholder).toContain('例如：')
    expect(placeholder).toContain('岗')
    expect(placeholder).toContain('薪资')
    expect(placeholder).toContain('排除')
    expect(placeholder.toLowerCase()).not.toContain('java')
    expect(wrapper.findAll('.memory-item small').map((item) => item.text())).toEqual([
      expect.stringContaining('手动添加'),
      expect.stringContaining('自动沉淀'),
    ])
    expect(wrapper.text()).not.toContain('manual')
    expect(wrapper.text()).not.toContain('agent-memory')
  })

  it('edits an existing memory and refreshes the rendered content', async () => {
    listMemories.mockResolvedValueOnce([
      {
        id: 'mem_1',
        content: '示例偏好：优先成都岗位',
        source: 'manual',
        enabled: true,
        updatedAt: '2026-07-27T11:51:00Z',
      },
    ])
    updateMemory.mockResolvedValueOnce({
      id: 'mem_1',
      content: '示例偏好：优先杭州岗位',
      source: 'manual',
      enabled: true,
      updatedAt: '2026-07-31T03:00:00Z',
    })
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    await wrapper.get('button[aria-label="编辑记忆"]').trigger('click')
    const editor = wrapper.get('.memory-editor input')
    expect(editor.element.value).toBe('示例偏好：优先成都岗位')
    await editor.setValue('示例偏好：优先杭州岗位')
    await wrapper.get('.memory-editor .primary-btn').trigger('click')
    await flushPromises()

    expect(updateMemory).toHaveBeenCalledWith('mem_1', {
      content: '示例偏好：优先杭州岗位',
      source: 'manual',
      enabled: true,
    })
    expect(wrapper.text()).toContain('示例偏好：优先杭州岗位')
    expect(wrapper.text()).not.toContain('示例偏好：优先成都岗位')
  })

  it('adds a memory and renders the persisted response', async () => {
    addMemory.mockResolvedValueOnce({
      id: 'mem_new',
      content: '优先远程 Agent 岗位',
      source: 'manual',
      enabled: true,
      updatedAt: '2026-08-01T06:00:00Z',
    })
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    const editor = wrapper.get('.memory-editor input')
    await editor.setValue('优先远程 Agent 岗位')
    await wrapper.get('.memory-editor .primary-btn').trigger('click')
    await flushPromises()

    expect(addMemory).toHaveBeenCalledWith({
      content: '优先远程 Agent 岗位',
      source: 'manual',
      enabled: true,
    })
    expect(wrapper.text()).toContain('优先远程 Agent 岗位')
    expect(editor.element.value).toBe('')
  })

  it('deletes only the selected memory after the API succeeds', async () => {
    listMemories.mockResolvedValueOnce([
      {
        id: 'mem_delete',
        content: '本轮待删除记忆',
        source: 'manual',
        enabled: true,
        updatedAt: '2026-08-01T06:00:00Z',
      },
      {
        id: 'mem_keep',
        content: '应保留的既有记忆',
        source: 'manual',
        enabled: true,
        updatedAt: '2026-08-01T05:00:00Z',
      },
    ])
    deleteMemory.mockResolvedValueOnce({ memoryId: 'mem_delete' })
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    await wrapper.findAll('.memory-item .danger-text')[0].trigger('click')
    await flushPromises()

    expect(deleteMemory).toHaveBeenCalledWith('mem_delete')
    expect(wrapper.text()).not.toContain('本轮待删除记忆')
    expect(wrapper.text()).toContain('应保留的既有记忆')
  })

  it('returns to create mode after clearing memories while editing', async () => {
    listMemories.mockResolvedValueOnce([
      {
        id: 'mem_1',
        content: '示例偏好：优先成都岗位',
        source: 'manual',
        enabled: true,
        updatedAt: '2026-07-27T11:51:00Z',
      },
    ])
    clearMemories.mockResolvedValueOnce()
    vi.spyOn(window, 'confirm').mockReturnValueOnce(true)
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    await wrapper.get('button[aria-label="编辑记忆"]').trigger('click')
    expect(wrapper.get('.memory-editor .primary-btn').text()).toBe('保存修改')
    await wrapper.get('.danger-btn').trigger('click')
    await flushPromises()

    expect(clearMemories).toHaveBeenCalled()
    expect(wrapper.get('.memory-editor .primary-btn').text()).toBe('新增记忆')
    expect(wrapper.get('.memory-editor input').element.value).toBe('')
  })
})
