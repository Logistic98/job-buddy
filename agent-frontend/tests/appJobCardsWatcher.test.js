import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
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
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/chat', component: { template: '<div>chat</div>' } },
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
})
