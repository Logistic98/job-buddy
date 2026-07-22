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

  it('shows empty credential fields, disables autofill, and requires both values', async () => {
    const wrapper = mountLogin()
    const form = wrapper.find('form.login-form')
    const usernameInput = wrapper.find('input[autocomplete="off"]')
    const passwordInput = wrapper.find('input[type="password"]')

    expect(wrapper.text()).not.toContain('租户编码')
    expect(wrapper.findAll('input')).toHaveLength(2)
    expect(form.attributes('autocomplete')).toBe('off')
    expect(usernameInput.element.value).toBe('')
    expect(passwordInput.attributes('autocomplete')).toBe('new-password')
    expect(passwordInput.element.value).toBe('')
    expect(wrapper.find('button.login-submit').attributes('disabled')).toBeDefined()
    await usernameInput.setValue('admin')
    await passwordInput.setValue('secret123')
    expect(wrapper.find('button.login-submit').attributes('disabled')).toBeUndefined()
  })

  it('emits logged-in after a successful login', async () => {
    login.mockResolvedValue({ user: { username: 'admin' } })
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="off"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret123')
    await wrapper.find('form').trigger('submit.prevent')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(login).toHaveBeenCalledWith('admin', 'secret123')
    expect(wrapper.emitted('logged-in')).toBeTruthy()
  })

  it('shows a friendly error modal and masks backend stack traces', async () => {
    login.mockRejectedValue(new Error('nested SQLException: HikariPool timed out'))
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="off"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret123')
    await wrapper.find('form').trigger('submit.prevent')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(wrapper.emitted('logged-in')).toBeFalsy()
    const modal = wrapper.find('.warning-modal-card')
    expect(modal.exists()).toBe(true)
    expect(modal.text()).toContain('服务暂时不可用')
    expect(modal.text()).not.toContain('SQLException')
  })
})
