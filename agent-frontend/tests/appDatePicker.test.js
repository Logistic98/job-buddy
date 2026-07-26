import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppDatePicker from '../src/components/AppDatePicker.vue'

describe('AppDatePicker', () => {
  it('selects and clears a month using the shared popover', async () => {
    const wrapper = mount(AppDatePicker, {
      props: {
        modelValue: '2026-07',
        ariaLabel: '请选择开始时间',
        'onUpdate:modelValue': (value) => wrapper.setProps({ modelValue: value }),
      },
    })

    expect(wrapper.get('.app-date-picker-trigger').text()).toContain('2026年7月')
    expect(wrapper.classes()).toContain('is-month')
    await wrapper.get('.app-date-picker-trigger').trigger('click')
    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toBe('请选择开始时间选择面板')
    expect(wrapper.findAll('.app-date-picker-months button')).toHaveLength(12)
    expect(wrapper.findAll('.app-date-picker-months button')[6].classes()).toContain('selected')

    await wrapper.findAll('.app-date-picker-months button')[8].trigger('click')
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual(['2026-09'])
    expect(wrapper.get('.app-date-picker-trigger').text()).toContain('2026年9月')

    await wrapper.get('.app-date-picker-trigger').trigger('click')
    await wrapper.findAll('.app-date-picker-footer button')[0].trigger('click')
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([''])
    expect(wrapper.get('.app-date-picker-trigger').classes()).toContain('is-placeholder')
  })

  it('selects a date and time without exposing a native browser picker', async () => {
    const wrapper = mount(AppDatePicker, {
      props: {
        modelValue: '2026-07-26T14:30',
        type: 'datetime',
        'onUpdate:modelValue': (value) => wrapper.setProps({ modelValue: value }),
      },
    })

    expect(wrapper.find('input[type="datetime-local"]').exists()).toBe(false)
    expect(wrapper.classes()).toContain('is-datetime')
    await wrapper.get('.app-date-picker-trigger').trigger('click')
    expect(wrapper.get('.app-date-picker-header strong').text()).toBe('2026年7月')
    expect(wrapper.findAll('.app-date-picker-days button')).toHaveLength(42)

    const selectedDay = wrapper.findAll('.app-date-picker-days button').find((button) => button.text() === '26')
    await selectedDay.trigger('click')
    await wrapper.get('select[aria-label="小时"]').setValue('16')
    await wrapper.get('select[aria-label="分钟"]').setValue('45')
    await wrapper.get('.app-date-picker-footer .confirm').trigger('click')

    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual(['2026-07-26T16:45'])
    expect(wrapper.get('.app-date-picker-trigger').text()).toContain('2026年7月26日 16:45')
  })

  it('opens upward when the picker is near the bottom of the viewport', async () => {
    const wrapper = mount(AppDatePicker)
    wrapper.element.getBoundingClientRect = () => ({ top: 650, bottom: 692 })

    await wrapper.get('.app-date-picker-trigger').trigger('click')
    wrapper.get('.app-date-picker-popover').element.getBoundingClientRect = () => ({ height: 309 })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    window.dispatchEvent(new globalThis.Event('resize'))
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.classes()).toContain('is-drop-up')
  })

  it('shifts a tall panel into view when neither side has enough space', async () => {
    const wrapper = mount(AppDatePicker, { props: { type: 'datetime' } })
    wrapper.element.getBoundingClientRect = () => ({ top: 280, bottom: 322 })

    await wrapper.get('.app-date-picker-trigger').trigger('click')
    wrapper.get('.app-date-picker-popover').element.getBoundingClientRect = () => ({
      top: 331,
      bottom: 781,
      height: 450,
    })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    window.dispatchEvent(new globalThis.Event('resize'))
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.classes()).not.toContain('is-drop-up')
    const expectedShift = -(781 - (window.innerHeight - 12))
    expect(wrapper.get('.app-date-picker-popover').attributes('style')).toContain(
      `--app-date-picker-shift: ${expectedShift}px`,
    )
  })
})
