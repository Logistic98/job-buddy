import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RoleManagement from '../src/components/RoleManagement.vue'

const mocks = vi.hoisted(() => ({
  listRoles: vi.fn(),
  listAssignableMenus: vi.fn(),
}))

vi.mock('../src/api/users', () => ({
  createRole: vi.fn(),
  deleteRole: vi.fn(),
  listAssignableMenus: mocks.listAssignableMenus,
  listRoles: mocks.listRoles,
  updateRole: vi.fn(),
}))

beforeEach(() => {
  mocks.listRoles.mockReset().mockResolvedValue([])
  mocks.listAssignableMenus.mockReset().mockResolvedValue([])
})

describe('RoleManagement', () => {
  it('does not preselect a status when creating a role', async () => {
    const wrapper = mount(RoleManagement, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('.primary-btn').trigger('click')

    expect(document.body.querySelector('.rbac-modal select').selectedOptions[0].text).toBe('请选择角色状态')

    wrapper.unmount()
  })
})
