import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppSidebar from '../src/components/AppSidebar.vue'
import { useAuthStore } from '../src/stores/auth'

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/chat', component: { template: '<div />' } },
      { path: '/settings', component: { template: '<div />' } },
    ],
  })
}

describe('dynamic RBAC navigation', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('renders sidebar entries from backend menus instead of fixed account navigation', async () => {
    const auth = useAuthStore()
    auth.user = {
      permissions: ['chat:use', 'users:manage'],
      menus: [
        {
          menuId: 'm-chat',
          menuName: '动态对话入口',
          menuType: 'page',
          routePath: '/chat',
          iconKey: 'workbench',
          displayOrder: 20,
        },
        {
          menuId: 'm-settings',
          menuName: '平台设置',
          menuType: 'page',
          routePath: '/settings',
          iconKey: 'settings',
          displayOrder: 30,
        },
        {
          menuId: 'm-settings-users',
          parentId: 'm-settings',
          menuName: '用户管理',
          menuType: 'page',
          routePath: '/settings?tab=users',
          componentKey: 'settings',
          displayOrder: 31,
        },
      ],
    }
    const appRouter = router()
    await appRouter.push('/chat')
    await appRouter.isReady()

    const wrapper = mount(AppSidebar, {
      props: { systemHealth: { status: 'up', label: '就绪' } },
      global: { plugins: [appRouter] },
    })

    expect(wrapper.text()).toContain('动态对话入口')
    expect(wrapper.text()).toContain('平台设置')
    expect(wrapper.text()).not.toContain('用户管理')
    expect(wrapper.text()).not.toContain('账号管理')
  })

  it('does not treat business access as management capability', () => {
    const auth = useAuthStore()
    auth.user = { permissions: ['chat:use'], menus: [] }
    expect(auth.isAdmin).toBe(false)
    expect(auth.hasPermission('chat:use')).toBe(true)
    expect(auth.hasPermission('users:manage')).toBe(false)
  })
})
