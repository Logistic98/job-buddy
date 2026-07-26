import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import MemorySettingsPanel from '../src/components/settings/MemorySettingsPanel.vue'

vi.mock('../src/api/settings', () => ({
  addMemory: vi.fn(),
  clearMemories: vi.fn(),
  deleteMemory: vi.fn(),
  listMemories: vi.fn().mockResolvedValue([]),
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
})
