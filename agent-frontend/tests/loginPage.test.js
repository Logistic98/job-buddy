import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(async () => ({})),
  currentUser: vi.fn(async () => null),
}))

import { login } from '../src/api/auth'
import LoginPage from '../src/components/LoginPage.vue'

function mountLogin() {
  return mount(LoginPage, { global: { plugins: [createPinia()] } })
}

describe('LoginPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    window.localStorage.clear()
  })

  it('disables submit until both fields are filled', async () => {
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="username"]').setValue('')
    await wrapper.find('input[type="password"]').setValue('')
    expect(wrapper.find('button.login-submit').attributes('disabled')).toBeDefined()
    await wrapper.find('input[autocomplete="username"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    expect(wrapper.find('button.login-submit').attributes('disabled')).toBeUndefined()
  })

  it('emits logged-in after a successful login', async () => {
    login.mockResolvedValue({ token: 'tok', user: { username: 'admin' } })
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="username"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(login).toHaveBeenCalledWith('admin', 'secret')
    expect(wrapper.emitted('logged-in')).toBeTruthy()
  })

  it('shows a friendly error modal and masks backend stack traces', async () => {
    login.mockRejectedValue(new Error('nested SQLException: HikariPool timed out'))
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="username"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(wrapper.emitted('logged-in')).toBeFalsy()
    const modal = wrapper.find('.warning-modal-card')
    expect(modal.exists()).toBe(true)
    expect(modal.text()).toContain('服务暂时不可用')
    expect(modal.text()).not.toContain('SQLException')
  })
})
