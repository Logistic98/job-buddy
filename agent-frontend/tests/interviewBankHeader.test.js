import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InterviewBankHeader from '../src/components/interview/InterviewBankHeader.vue'

const baseProps = {
  activeMode: 'bank',
  pageEyebrow: 'Question Bank',
  pageTitle: '题库',
  pageDescription: '管理题目',
  bankTypeOptions: [{ value: 'leetcode', label: '算法题库' }],
  activeBankType: 'leetcode',
}

describe('InterviewBankHeader', () => {
  it.each([false, true])('does not render a manual refresh button when embedded is %s', (embedded) => {
    const wrapper = mount(InterviewBankHeader, { props: { ...baseProps, embedded } })

    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('刷新')
    expect(wrapper.findAll('button').map((button) => button.text())).toContain('新增题目')
  })

  it.each([false, true])('hides mode actions during an active practice when embedded is %s', (embedded) => {
    const wrapper = mount(InterviewBankHeader, {
      props: { ...baseProps, embedded, activeMode: 'exam', showActions: false },
    })

    const buttonTexts = wrapper.findAll('button').map((button) => button.text())
    expect(buttonTexts).not.toContain('返回题库')
    expect(buttonTexts).not.toContain('练习记录')
    expect(buttonTexts).not.toContain('随机组卷')
  })
})
