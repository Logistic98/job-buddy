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

  it('renders page authorization as a cascading menu tree without feature permissions', async () => {
    mocks.listAssignableMenus.mockResolvedValue([
      {
        menuId: 'menu-chat',
        parentId: '',
        menuName: '智能引擎',
        menuType: 'page',
        routePath: '/chat',
        displayOrder: 10,
      },
      {
        menuId: 'menu-settings',
        parentId: '',
        menuName: '平台设置',
        menuType: 'page',
        routePath: '/settings',
        displayOrder: 90,
      },
      {
        menuId: 'menu-settings-users',
        parentId: 'menu-settings',
        menuName: '用户管理',
        menuType: 'page',
        routePath: '/settings?tab=users',
        displayOrder: 91,
      },
      {
        menuId: 'menu-settings-roles',
        parentId: 'menu-settings',
        menuName: '角色管理',
        menuType: 'page',
        routePath: '/settings?tab=roles',
        displayOrder: 92,
      },
    ])
    const wrapper = mount(RoleManagement, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('.primary-btn').trigger('click')

    const menuSection = [...document.body.querySelectorAll('.rbac-form-section')].find(
      (section) => section.querySelector('strong')?.textContent === '菜单授权',
    )
    const settingsChoice = [...menuSection.querySelectorAll('.rbac-choice')].find((choice) =>
      choice.textContent.includes('平台设置'),
    )
    const settingsChildren = [...menuSection.querySelectorAll('.rbac-choice')].filter(
      (choice) => choice.textContent.includes('用户管理') || choice.textContent.includes('角色管理'),
    )

    expect(menuSection.textContent).toContain('智能引擎')
    expect(settingsChildren).toHaveLength(2)
    expect(document.body.textContent).not.toContain('功能权限')

    settingsChoice.querySelector('input').click()
    await wrapper.vm.$nextTick()
    expect(settingsChildren.every((choice) => choice.querySelector('input').checked)).toBe(true)

    wrapper.unmount()
  })
})
