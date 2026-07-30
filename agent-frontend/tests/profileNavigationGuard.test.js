import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from '../src/components/AppSidebar.vue'
import BossResumePage from '../src/components/BossResumePage.vue'

const mocks = vi.hoisted(() => ({
  getJobProfile: vi.fn(),
  saveJobProfile: vi.fn(),
}))

vi.mock('../src/api/resume', async (importOriginal) => ({
  ...(await importOriginal()),
  getJobProfile: mocks.getJobProfile,
  saveJobProfile: mocks.saveJobProfile,
}))

const profile = {
  resumeId: 'profile-guard',
  parsed: {
    name: '当前姓名',
    basic_info: { name: '当前姓名', city: '上海' },
    job_expectations: { position: 'Java 工程师' },
    skills: ['Java'],
  },
}

const TestShell = defineComponent({
  components: { AppSidebar },
  template: '<AppSidebar /><router-view />',
})

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/profile', component: BossResumePage },
      { path: '/jobs', component: { template: '<div data-testid="jobs-page">岗位收藏页面</div>' } },
    ],
  })
}

describe('profile navigation guard', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.getJobProfile.mockResolvedValue(profile)
    mocks.saveJobProfile.mockResolvedValue(profile)
  })

  it('guards a dirty profile when the main sidebar starts a real router navigation', async () => {
    const { useAuthStore } = await import('../src/stores/auth')
    const auth = useAuthStore()
    auth.user = {
      displayName: '测试用户',
      permissions: ['resume:use', 'jobs:use'],
      menus: [
        {
          menuId: 'profile',
          menuName: '求职画像',
          menuType: 'page',
          routePath: '/profile',
          displayOrder: 10,
        },
        {
          menuId: 'jobs',
          menuName: '岗位收藏',
          menuType: 'page',
          routePath: '/jobs',
          displayOrder: 20,
        },
      ],
    }

    const router = createTestRouter()
    await router.push('/profile')
    await router.isReady()
    const wrapper = mount(TestShell, { global: { plugins: [router] } })
    await flushPromises()

    const nameInput = wrapper.get('input[placeholder="请输入姓名"]')
    await nameInput.setValue('未保存的新姓名')
    expect(wrapper.get('.profile-save-indicator').text()).toContain('有未保存的修改')

    const jobsButton = wrapper.findAll('.nav-item').find((button) => button.text().includes('岗位收藏'))
    const confirmLeave = vi.spyOn(window, 'confirm').mockReturnValueOnce(false)
    await jobsButton.trigger('click')
    await flushPromises()

    expect(confirmLeave).toHaveBeenCalledWith('求职画像有未保存的修改，离开后这些修改将丢失。确认离开当前页面吗？')
    expect(router.currentRoute.value.path).toBe('/profile')
    expect(nameInput.element.value).toBe('未保存的新姓名')

    confirmLeave.mockReturnValueOnce(true)
    await jobsButton.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/jobs')
    expect(wrapper.get('[data-testid="jobs-page"]').exists()).toBe(true)
    confirmLeave.mockRestore()
    wrapper.unmount()
  })
})
