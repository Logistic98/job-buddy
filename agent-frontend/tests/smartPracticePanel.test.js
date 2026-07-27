import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SmartPracticePanel from '../src/components/interview/SmartPracticePanel.vue'
import { createSmartExam } from '../src/api/interview'

vi.mock('../src/api/interview', () => ({
  createSmartExam: vi.fn(),
}))

beforeEach(() => {
  createSmartExam.mockReset()
})

describe('SmartPracticePanel', () => {
  it('fills an example requirement and emits the created practice', async () => {
    createSmartExam.mockResolvedValue({ examId: 'practice-smart-1', totalCount: 8 })
    const wrapper = mount(SmartPracticePanel)

    expect(wrapper.find('.smart-practice-intro').exists()).toBe(false)
    expect(wrapper.find('[aria-label="智能组卷要求"]').exists()).toBe(true)
    expect(wrapper.find('textarea').attributes('placeholder')).toContain('生产级 Agent 工程')
    await wrapper.find('.smart-practice-example').trigger('click')
    const textarea = wrapper.find('textarea')
    expect(textarea.element.value).toContain('Agent Loop')
    expect(textarea.element.value).toContain('工具治理')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createSmartExam).toHaveBeenCalledWith({ requirements: textarea.element.value.trim() })
    expect(wrapper.emitted('created')).toEqual([[{ examId: 'practice-smart-1', totalCount: 8 }]])
  })

  it('keeps the requirement and shows a readable error when composition fails', async () => {
    createSmartExam.mockRejectedValue(new Error('当前题库没有足够的相关题目'))
    const wrapper = mount(SmartPracticePanel)
    const textarea = wrapper.find('textarea')
    await textarea.setValue('选择 6 道 Agent 工具治理中高难度题，30 分钟考试模式')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(textarea.element.value).toBe('选择 6 道 Agent 工具治理中高难度题，30 分钟考试模式')
    expect(wrapper.find('[role="alert"]').text()).toContain('当前题库没有足够的相关题目')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
