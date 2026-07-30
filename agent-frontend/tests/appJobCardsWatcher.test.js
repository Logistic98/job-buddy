import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(async () => ({})),
  currentUser: vi.fn(async () => null),
}))

import App from '../src/App.vue'
import { useAuthStore } from '../src/stores/auth'
import { useChatStore } from '../src/stores/chat'
import { useJobStore } from '../src/stores/job'

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div>login</div>' }, meta: { public: true } },
      { path: '/chat', component: { template: '<div class="chat-page">chat</div>' } },
    ],
  })
}

describe('App job card synchronization', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.clearAllMocks()
  })

  it('propagates restored and cleared job card arrays to the job store', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createTestRouter()
    await router.push('/login')
    await router.isReady()

    const auth = useAuthStore()
    auth.initialized = true
    const chat = useChatStore()
    const job = useJobStore()
    const setJobs = vi.spyOn(job, 'setJobs')
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })

    chat.lastJobCardsEvent = [{ securityId: 'restored-job' }]
    await nextTick()
    expect(job.jobs).toEqual([{ securityId: 'restored-job' }])

    chat.lastJobCardsEvent = []
    await nextTick()
    expect(job.jobs).toEqual([])
    expect(setJobs).toHaveBeenLastCalledWith([])

    wrapper.unmount()
  })

  it('does not render a protected page without its shell while logout navigation is pending', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createTestRouter()
    let finishLoginNavigation
    router.beforeEach((to) => {
      if (to.path !== '/login') return true
      return new Promise((resolve) => {
        finishLoginNavigation = resolve
      })
    })
    await router.push('/chat')
    await router.isReady()

    const auth = useAuthStore()
    auth.initialized = true
    auth.user = { username: 'admin', permissions: [] }
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })
    await nextTick()

    expect(wrapper.find('.system-shell').exists()).toBe(true)
    expect(wrapper.find('.chat-page').exists()).toBe(true)

    await wrapper.find('.sidebar-logout-btn').trigger('click')
    await nextTick()
    await flushPromises()

    expect(finishLoginNavigation).toBeTypeOf('function')
    expect(router.currentRoute.value.path).toBe('/chat')
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.logoutPending).toBe(true)
    expect(wrapper.find('.chat-page').exists()).toBe(true)
    expect(wrapper.find('.system-shell').exists()).toBe(true)

    finishLoginNavigation(true)
    await flushPromises()
    await nextTick()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.logoutPending).toBe(false)
    expect(wrapper.text()).toContain('login')
    expect(wrapper.find('.app-boot-screen').exists()).toBe(false)

    wrapper.unmount()
  })
})
