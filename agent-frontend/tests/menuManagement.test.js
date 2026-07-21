import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import MenuManagement from '../src/components/MenuManagement.vue'

const { listMenus, listPermissionDefinitions } = vi.hoisted(() => ({
  listMenus: vi.fn(),
  listPermissionDefinitions: vi.fn(),
}))

vi.mock('../src/api/users', () => ({
  createMenu: vi.fn(),
  deleteMenu: vi.fn(),
  listMenus,
  listPermissionDefinitions,
  updateMenu: vi.fn(),
}))

const menus = [
  {
    menuId: 'menu-settings',
    parentId: '',
    menuCode: 'settings',
    menuName: '平台设置',
    menuType: 'page',
    routePath: '/settings',
    permissionCode: '',
    displayOrder: 10,
    visible: true,
    enabled: true,
  },
  {
    menuId: 'menu-settings-users',
    parentId: 'menu-settings',
    menuCode: 'settings-users',
    menuName: '用户管理',
    menuType: 'action',
    permissionCode: 'users:manage',
    displayOrder: 20,
    visible: false,
    enabled: true,
  },
]

describe('menu management semantics', () => {
  beforeEach(() => {
    listMenus.mockResolvedValue(menus)
    listPermissionDefinitions.mockResolvedValue([{ permissionCode: 'users:manage', permissionName: '管理用户' }])
  })

  it('describes aggregate and action nodes without reporting false configuration problems', async () => {
    const wrapper = mount(MenuManagement)
    await flushPromises()

    expect(wrapper.text()).toContain('由子菜单控制')
    expect(wrapper.text()).toContain('不参与导航')
    expect(wrapper.text()).not.toContain('无权限码')

    const visibleMetric = wrapper.findAll('.rbac-metric').find((item) => item.text().includes('前台显示'))
    expect(visibleMetric.text()).toContain('1')
    expect(visibleMetric.text()).toContain('0 个菜单已隐藏')
  })

  it('forces action nodes to stay outside frontend navigation', async () => {
    const wrapper = mount(MenuManagement, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('.primary-btn').trigger('click')

    const fields = Array.from(document.body.querySelectorAll('.rbac-field'))
    const typeField = new DOMWrapper(fields.find((field) => field.textContent.includes('菜单类型')))
    await typeField.find('select').setValue('action')

    const visibilityField = new DOMWrapper(fields.find((field) => field.textContent.includes('前台显示')))
    const visibilitySelect = visibilityField.find('select')
    expect(visibilitySelect.element.disabled).toBe(true)
    expect(visibilitySelect.element.value).toBe('false')
    expect(visibilityField.text()).toContain('不参与导航')

    wrapper.unmount()
  })
})
