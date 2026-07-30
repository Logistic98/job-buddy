import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserManagement from '../src/components/UserManagement.vue'
import { useAuthStore } from '../src/stores/auth'

const mocks = vi.hoisted(() => ({
  changeUserPassword: vi.fn(),
  listUsers: vi.fn(),
  listAssignableRoles: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('../src/api/users', () => ({
  changeUserPassword: mocks.changeUserPassword,
  createUser: vi.fn(),
  listAssignableRoles: mocks.listAssignableRoles,
  listUsers: mocks.listUsers,
  updateUser: mocks.updateUser,
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mocks.listUsers.mockReset().mockResolvedValue([
    {
      userId: 'admin-user',
      username: 'admin',
      displayName: '管理员',
      enabled: true,
      roleIds: ['role-admin'],
      roleNames: ['管理员'],
      permissions: ['platform:manage'],
    },
  ])
  mocks.listAssignableRoles.mockReset().mockResolvedValue([
    {
      roleId: 'role-admin',
      roleCode: 'admin',
      roleName: '管理员',
      enabled: true,
    },
    {
      roleId: 'role-user',
      roleCode: 'user',
      roleName: '普通用户',
      enabled: true,
    },
  ])
  mocks.changeUserPassword.mockReset().mockResolvedValue({})
  mocks.updateUser.mockReset().mockResolvedValue({})
})

describe('UserManagement', () => {
  it('shows administrator role and updates the globally unique username', async () => {
    const auth = useAuthStore()
    auth.user = { userId: 'admin-user', username: 'admin' }
    const refresh = vi.spyOn(auth, 'refresh').mockResolvedValue(auth.user)
    const logout = vi.spyOn(auth, 'logout').mockResolvedValue()
    const wrapper = mount(UserManagement, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('.rbac-action-btn').trigger('click')

    const modal = document.body.querySelector('.rbac-modal')
    const usernameInput = modal.querySelector('input[autocomplete="off"]')
    expect(modal.textContent).toContain('管理员')
    expect(usernameInput.value).toBe('admin')

    await usernameInput.setValue?.('admin_new')
    usernameInput.value = 'admin_new'
    usernameInput.dispatchEvent(new window.Event('input', { bubbles: true }))
    await modal.querySelector('.primary-btn').click()
    await flushPromises()

    expect(mocks.updateUser).toHaveBeenCalledWith('admin-user', {
      username: 'admin_new',
      displayName: '管理员',
      enabled: true,
      roleIds: ['role-admin'],
    })
    expect(refresh).toHaveBeenCalledOnce()
    expect(logout).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('changes the current user password after validating the old password', async () => {
    const auth = useAuthStore()
    auth.user = { userId: 'admin-user', username: 'admin' }
    const logout = vi.spyOn(auth, 'logout').mockResolvedValue()
    const wrapper = mount(UserManagement, { attachTo: document.body })
    await flushPromises()

    const changeButton = wrapper.findAll('.rbac-action-btn')[1]
    expect(changeButton.text()).toBe('修改密码')
    await changeButton.trigger('click')
    const modal = document.body.querySelector('.rbac-modal')
    const passwordInputs = modal.querySelectorAll('input[type="password"]')
    expect(passwordInputs).toHaveLength(2)
    passwordInputs[0].value = 'oldpass123'
    passwordInputs[0].dispatchEvent(new window.Event('input', { bubbles: true }))
    passwordInputs[1].value = 'newpass123'
    passwordInputs[1].dispatchEvent(new window.Event('input', { bubbles: true }))
    await modal.querySelector('.primary-btn').click()
    await flushPromises()

    expect(mocks.changeUserPassword).toHaveBeenCalledWith('admin-user', 'oldpass123', 'newpass123')
    expect(logout).toHaveBeenCalledOnce()
    expect(mocks.listUsers).toHaveBeenCalledOnce()
    expect(document.body.querySelector('.rbac-modal')).toBeNull()

    wrapper.unmount()
  })

  it('shows and hides old and new passwords independently', async () => {
    const wrapper = mount(UserManagement, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('.rbac-action-btn')[1].trigger('click')

    const modal = document.body.querySelector('.rbac-modal')
    const oldPassword = modal.querySelector('input[placeholder="请输入当前密码"]')
    const newPassword = modal.querySelector('input[placeholder="请输入 8-16 位新密码"]')
    const showOld = modal.querySelector('button[aria-label="显示旧密码"]')
    const showNew = modal.querySelector('button[aria-label="显示新密码"]')
    expect(oldPassword.type).toBe('password')
    expect(oldPassword.autocomplete).toBe('new-password')
    expect(oldPassword.value).toBe('')
    expect(newPassword.type).toBe('password')
    expect(showOld.querySelector('svg')).not.toBeNull()
    expect(showNew.querySelector('svg')).not.toBeNull()
    expect(showOld.querySelector('svg').dataset.icon).toBe('eye-closed')
    expect(showNew.querySelector('svg').dataset.icon).toBe('eye-closed')
    expect(showOld.textContent.trim()).toBe('')
    expect(showNew.textContent.trim()).toBe('')

    showOld.click()
    await flushPromises()
    expect(oldPassword.type).toBe('text')
    expect(newPassword.type).toBe('password')
    expect(modal.querySelector('button[aria-label="隐藏旧密码"] svg').dataset.icon).toBe('eye-open')
    expect(showNew.querySelector('svg').dataset.icon).toBe('eye-closed')
    showNew.click()
    await flushPromises()
    expect(newPassword.type).toBe('text')
    expect(modal.querySelector('button[aria-label="隐藏新密码"] svg').dataset.icon).toBe('eye-open')

    wrapper.unmount()
  })
})
