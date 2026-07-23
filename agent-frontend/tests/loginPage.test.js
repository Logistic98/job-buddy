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

  it('shows required markers and a red validation error for empty credentials', async () => {
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
    expect(usernameInput.attributes('aria-required')).toBe('true')
    expect(passwordInput.attributes('aria-required')).toBe('true')
    expect(usernameInput.attributes('placeholder')).toBe('请输入用户名')
    expect(passwordInput.attributes('placeholder')).toBe('请输入密码')
    expect(usernameInput.attributes('minlength')).toBeUndefined()
    expect(usernameInput.attributes('maxlength')).toBeUndefined()
    expect(passwordInput.attributes('minlength')).toBeUndefined()
    expect(passwordInput.attributes('maxlength')).toBeUndefined()
    expect(wrapper.findAll('.form-required')).toHaveLength(2)
    expect(wrapper.find('button.login-submit').attributes('disabled')).toBeUndefined()
    await form.trigger('submit.prevent')
    expect(wrapper.find('.warning-modal-card[role="alert"] .form-error-alert').text()).toBe('请填写用户名')
  })

  it('accepts credentials without frontend length restrictions', async () => {
    login.mockResolvedValue({ user: { username: 'a' } })
    const wrapper = mountLogin()
    await wrapper.find('input[autocomplete="off"]').setValue('a')
    await wrapper.find('input[type="password"]').setValue('1')
    await wrapper.find('form').trigger('submit.prevent')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(login).toHaveBeenCalledWith('a', '1')
    expect(wrapper.emitted('logged-in')).toBeTruthy()
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
