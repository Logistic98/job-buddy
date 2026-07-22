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
    const fieldByLabel = (label) => new DOMWrapper(fields.find((field) => field.textContent.includes(label)))
    expect(fieldByLabel('菜单类型').find('select').element.selectedOptions[0].text).toBe('请选择菜单类型')
    expect(fieldByLabel('父菜单').find('select').element.selectedOptions[0].text).toBe('请选择父菜单')
    expect(fieldByLabel('排序值').find('input').element.value).toBe('')
    expect(fieldByLabel('关联权限码').find('select').element.selectedOptions[0].text).toBe('请选择关联权限')
    expect(fieldByLabel('前台显示').find('select').element.selectedOptions[0].text).toBe('请选择显示状态')
    expect(fieldByLabel('菜单状态').find('select').element.selectedOptions[0].text).toBe('请选择菜单状态')

    const typeField = fieldByLabel('菜单类型')
    await typeField.find('select').setValue('action')

    const visibilityField = new DOMWrapper(fields.find((field) => field.textContent.includes('前台显示')))
    const visibilitySelect = visibilityField.find('select')
    expect(visibilitySelect.element.disabled).toBe(true)
    expect(visibilitySelect.element.value).toBe('false')
    expect(visibilityField.text()).toContain('不参与导航')

    wrapper.unmount()
  })
})
