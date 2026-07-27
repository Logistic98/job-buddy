import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeIconModal from '../src/components/resume-writer/ResumeIconModal.vue'

const mocks = vi.hoisted(() => ({
  copyText: vi.fn(),
}))

vi.mock('../src/utils/clipboard', () => ({
  copyText: mocks.copyText,
}))

beforeEach(() => {
  mocks.copyText.mockReset().mockResolvedValue(true)
})

describe('ResumeIconModal', () => {
  it('copies icon tokens through the HTTP-compatible clipboard helper', async () => {
    const wrapper = mount(ResumeIconModal)

    await wrapper.get('.icon-grid button').trigger('click')
    await flushPromises()

    expect(mocks.copyText).toHaveBeenCalledWith('icon:info')
    expect(wrapper.emitted('insert')).toEqual([['icon:info ']])
    expect(wrapper.get('.icon-copy-hint').text()).toContain('icon:info')
  })
})
