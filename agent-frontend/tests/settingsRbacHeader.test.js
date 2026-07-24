import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import SettingsCenter from '../src/components/SettingsCenter.vue'
import UserManagement from '../src/components/UserManagement.vue'
import { saveSettings } from '../src/api/settings'
import { useAuthStore } from '../src/stores/auth'

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: vi.fn().mockResolvedValue({ status: 'unknown' }),
}))

vi.mock('../src/api/settings', () => ({
  addMemory: vi.fn(),
  clearMemories: vi.fn(),
  deleteMemory: vi.fn(),
  getSettings: vi.fn().mockResolvedValue({
    workspace: {},
    blacklist: { enabled: true, matchMode: 'contains', items: [] },
    memory: { enabled: true, autoSaveChat: false, autoUseMemory: true, maxItems: 200, items: [] },
    serviceStatuses: {},
  }),
  listMemories: vi.fn().mockResolvedValue([]),
  refreshServiceHealth: vi.fn().mockResolvedValue({}),
  restoreWorkspaceDefaults: vi.fn(),
  saveSettings: vi.fn(),
}))

vi.mock('../src/api/users', () => ({
  createMenu: vi.fn(),
  createRole: vi.fn(),
  createUser: vi.fn(),
  deleteMenu: vi.fn(),
  deleteRole: vi.fn(),
  listAssignableMenus: vi.fn().mockResolvedValue([]),
  listAssignableRoles: vi.fn().mockResolvedValue([]),
  listMenus: vi.fn().mockResolvedValue([]),
  listPermissionDefinitions: vi.fn().mockResolvedValue([]),
  listRoles: vi.fn().mockResolvedValue([]),
  listUsers: vi.fn().mockResolvedValue([]),
  resetUserPassword: vi.fn(),
  updateMenu: vi.fn(),
  updateRole: vi.fn(),
  updateUser: vi.fn(),
}))

describe('settings RBAC headers', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/settings?tab=users')
    window.sessionStorage.clear()
    vi.mocked(saveSettings)
      .mockReset()
      .mockImplementation(async (payload) => payload)
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = {
      permissions: ['users:manage', 'roles:manage', 'menus:manage', 'platform:manage'],
      menus: [
        { menuId: 'settings', menuCode: 'settings', menuName: '平台设置', menuType: 'page' },
        { menuId: 'settings-users', parentId: 'settings', menuCode: 'settings-users', menuType: 'page' },
        { menuId: 'settings-roles', parentId: 'settings', menuCode: 'settings-roles', menuType: 'page' },
        { menuId: 'settings-menus', parentId: 'settings', menuCode: 'settings-menus', menuType: 'page' },
        { menuId: 'settings-workspace', parentId: 'settings', menuCode: 'settings-workspace', menuType: 'page' },
        { menuId: 'settings-blacklist', parentId: 'settings', menuCode: 'settings-blacklist', menuType: 'page' },
        { menuId: 'settings-memory', parentId: 'settings', menuCode: 'settings-memory', menuType: 'page' },
        { menuId: 'settings-services', parentId: 'settings', menuCode: 'settings-services', menuType: 'page' },
      ],
    }
  })

  it('uses only the management component header on RBAC tabs', async () => {
    const wrapper = mount(SettingsCenter, {
      global: {
        stubs: {
          BossLoginQrModal: true,
          UserManagement: { template: '<section class="user-management-stub"><h2>用户管理</h2></section>' },
          RoleManagement: true,
          MenuManagement: true,
        },
      },
    })

    expect(wrapper.find('.settings-content-head').exists()).toBe(false)
    expect(wrapper.findAll('h2').filter((node) => node.text() === '用户管理')).toHaveLength(1)

    await wrapper
      .findAll('.settings-tab')
      .find((node) => node.text().includes('运行参数'))
      .trigger('click')
    await nextTick()

    expect(wrapper.find('.settings-content-head').exists()).toBe(true)
    expect(wrapper.find('.settings-content-head h1').text()).toBe('运行参数')
    expect(wrapper.find('.settings-content-head').text()).toContain('恢复默认')
    expect(wrapper.find('.settings-content-head').text()).not.toContain('已保存')
  })

  it('labels the user list as 用户', async () => {
    const wrapper = mount(UserManagement)
    await flushPromises()

    expect(wrapper.find('.rbac-panel-toolbar strong').text()).toBe('用户')
    expect(wrapper.text()).not.toContain('租户用户')
  })

  it('saves runtime parameters from the shared settings header', async () => {
    window.history.replaceState({}, '', '/settings?tab=workspace')
    const wrapper = mount(SettingsCenter, {
      global: {
        stubs: { BossLoginQrModal: true },
      },
    })
    await flushPromises()

    await wrapper.get('input[type="number"]').setValue('14')
    await wrapper
      .findAll('.settings-actions button')
      .find((button) => button.text() === '保存设置')
      .trigger('click')
    await flushPromises()

    expect(saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({
        workspace: expect.objectContaining({ maxJobsPerRecommend: 14 }),
      }),
    )
  })
})
