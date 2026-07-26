import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppSwitch from '../src/components/AppSwitch.vue'

describe('AppSwitch', () => {
  it('renders a keyboard-accessible two-state switch and emits boolean updates', async () => {
    const wrapper = mount(AppSwitch, {
      props: {
        modelValue: true,
        ariaLabel: '功能状态',
      },
    })
    const input = wrapper.get('input[role="switch"]')

    expect(input.attributes('aria-label')).toBe('功能状态')
    expect(input.element.checked).toBe(true)
    expect(wrapper.text()).toBe('')

    await input.setValue(false)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
})
