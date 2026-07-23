import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserManagement from '../src/components/UserManagement.vue'

const mocks = vi.hoisted(() => ({
  listUsers: vi.fn(),
  listAssignableRoles: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('../src/api/users', () => ({
  createUser: vi.fn(),
  listAssignableRoles: mocks.listAssignableRoles,
  listUsers: mocks.listUsers,
  resetUserPassword: vi.fn(),
  updateUser: mocks.updateUser,
}))

beforeEach(() => {
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
  mocks.updateUser.mockReset().mockResolvedValue({})
})

describe('UserManagement', () => {
  it('shows administrator role and updates the globally unique username', async () => {
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

    wrapper.unmount()
  })
})
