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
  useScopedSettings: () => ({
    value: ref({ enabled: true, autoSaveChat: false, autoUseMemory: true, maxItems: 200 }),
    loading: ref(false),
    saving: ref(false),
    error: ref(''),
    dirty: ref(false),
    load: vi.fn().mockResolvedValue(),
    save: vi.fn().mockResolvedValue(),
  }),
}))

describe('MemorySettingsPanel', () => {
  it('shows required markers and a red error when a new memory is incomplete', async () => {
    const wrapper = mount(MemorySettingsPanel)
    await flushPromises()

    expect(wrapper.find('.memory-editor select').element.value).toBe('')
    expect(wrapper.find('.memory-editor select').attributes('aria-required')).toBe('true')
    expect(wrapper.find('.memory-editor input').attributes('aria-required')).toBe('true')
    expect(wrapper.findAll('.memory-editor-field .form-required')).toHaveLength(2)
    expect(wrapper.find('.memory-editor button').attributes()).not.toHaveProperty('disabled')
    await wrapper.find('.memory-editor button').trigger('click')
    expect(wrapper.find('.form-error-alert[role="alert"]').text()).toBe('请选择记忆类型')
  })
})
