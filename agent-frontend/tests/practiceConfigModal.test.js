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
  it('opens every practice field without a default value', async () => {
    const wrapper = mount(PracticeConfigModal, { props })
    wrapper.vm.open()
    await nextTick()

    expect(wrapper.find('input[placeholder*="综合练习"]').element.value).toBe('')
    expect(wrapper.find('.practice-duration-custom input').element.value).toBe('')
    expect(wrapper.findAll('input[type="radio"]').every((input) => !input.element.checked)).toBe(true)
    expect(wrapper.findAll('.practice-rule-row select').map((select) => select.element.value)).toEqual(['', '', '', ''])
    expect(wrapper.find('.practice-rule-row input[type="number"]').element.value).toBe('')

    wrapper.unmount()
  })
})
