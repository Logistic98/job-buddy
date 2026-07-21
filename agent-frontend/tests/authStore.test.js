import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/auth', () => ({
  currentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

import { currentUser, logout } from '../src/api/auth'
import { useAuthStore } from '../src/stores/auth'

describe('auth store initialization', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('restores a persistent cookie session', async () => {
    const user = { username: 'admin', permissions: ['resume:use'] }
    currentUser.mockResolvedValue(user)
    const auth = useAuthStore()

    await auth.init()

    expect(currentUser).toHaveBeenCalledWith()
    expect(auth.user).toEqual(user)
    expect(auth.isLoggedIn).toBe(true)
  })

  it('does not restore a stale user after another tab logs out', async () => {
    let resolveCurrentUser
    currentUser.mockReturnValue(
      new Promise((resolve) => {
        resolveCurrentUser = resolve
      }),
    )
    const auth = useAuthStore()

    const initialization = auth.init()
    auth.clearLocal()
    resolveCurrentUser({ username: 'admin' })
    await initialization

    expect(auth.isLoggedIn).toBe(false)
    expect(window.sessionStorage.getItem('job_buddy_auth_user')).toBeNull()
  })

  it('clears cached authentication when the backend rejects the session', async () => {
    window.sessionStorage.setItem('job_buddy_auth_user', JSON.stringify({ username: 'admin' }))
    currentUser.mockRejectedValue(new Error('unauthorized'))
    const auth = useAuthStore()

    await auth.init()

    expect(auth.isLoggedIn).toBe(false)
    expect(window.sessionStorage.getItem('job_buddy_auth_user')).toBeNull()
  })

  it('invalidates the cookie session on logout', async () => {
    logout.mockResolvedValue(true)
    const auth = useAuthStore()
    auth.user = { username: 'admin' }

    await auth.logout()

    expect(logout).toHaveBeenCalledWith()
    expect(auth.isLoggedIn).toBe(false)
  })
})
