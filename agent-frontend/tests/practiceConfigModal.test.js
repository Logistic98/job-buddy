import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import PracticeConfigModal from '../src/components/interview/PracticeConfigModal.vue'

vi.mock('../src/api/interview', () => ({
  createRandomExam: vi.fn(),
}))

const props = {
  bankTypeOptions: [
    { value: 'qa', label: '问答题库' },
    { value: 'leetcode', label: '算法题库' },
  ],
  categories: ['Agent 工程'],
  difficulties: ['中等'],
  questionTypes: ['简答'],
}

describe('PracticeConfigModal', () => {
  it('opens with medium difficulty as the default rule value', async () => {
    const wrapper = mount(PracticeConfigModal, { props })
    wrapper.vm.open()
    await nextTick()

    expect(wrapper.find('input[placeholder*="综合练习"]').element.value).toBe('')
    expect(wrapper.find('.practice-duration-custom input').element.value).toBe('')
    expect(wrapper.findAll('input[type="radio"]').every((input) => !input.element.checked)).toBe(true)
    expect(wrapper.findAll('.practice-rule-row select').map((select) => select.element.value)).toEqual([
      '',
      '',
      '中等',
      '',
    ])
    expect(wrapper.find('.practice-rule-row input[type="number"]').element.value).toBe('')
    expect(wrapper.find('.practice-rule-row input[type="number"]').attributes('max')).toBe('100')
    expect(wrapper.find('.practice-rule-row input[type="number"]').attributes('placeholder')).toBe('1-100')
    expect(wrapper.find('.practice-duration-custom input').attributes('placeholder')).toBe('1-240')
    expect(wrapper.find('input[placeholder*="综合练习"]').attributes('placeholder')).not.toContain('最多')
    expect(wrapper.find('.practice-total-pill').exists()).toBe(false)
    expect(wrapper.find('.practice-modal-actions .primary-btn').text()).toBe('开始练习')

    wrapper.unmount()
  })

  it('defaults each added rule to medium difficulty', async () => {
    const wrapper = mount(PracticeConfigModal, { props: { ...props, embedded: true } })

    await wrapper.find('.practice-add-rule').trigger('click')

    expect(wrapper.findAll('.practice-rule-row').map((row) => row.findAll('select')[2].element.value)).toEqual([
      '中等',
      '中等',
    ])
  })

  it('renders the former random composition form inline as rule composition', () => {
    const wrapper = mount(PracticeConfigModal, { props: { ...props, embedded: true } })

    expect(wrapper.find('.rule-practice-panel').exists()).toBe(true)
    expect(wrapper.find('.modal-mask').exists()).toBe(false)
    expect(wrapper.find('.close').exists()).toBe(false)
    expect(wrapper.find('h2').text()).toBe('规则组卷')
    expect(wrapper.find('.practice-duration-section').exists()).toBe(true)
    expect(wrapper.find('.practice-duration-section .practice-duration-custom').exists()).toBe(true)
    expect(wrapper.findAll('.practice-form > .practice-section')[2].classes()).toContain('practice-duration-section')
    expect(wrapper.findAll('.practice-duration .practice-chip').map((button) => button.text())).toEqual([
      '15 分钟',
      '30 分钟',
      '45 分钟',
      '60 分钟',
      '90 分钟',
      '120 分钟',
      '150 分钟',
      '180 分钟',
      '240 分钟',
    ])
  })
})
