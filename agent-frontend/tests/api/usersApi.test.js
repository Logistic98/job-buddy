import { afterEach, describe, expect, it, vi } from 'vitest'
import * as users from '../../src/api/users'

const reply = (code = 200, data = { id: 'saved' }) => ({
  ok: code === 200,
  status: code,
  text: async () => JSON.stringify({ code, data, message: code === 200 ? 'success' : 'permission denied' }),
})

afterEach(() => vi.unstubAllGlobals())

describe('administration API contracts', () => {
  it.each([
    ['listUsers', '/admin/users'],
    ['listAssignableRoles', '/admin/users/roles'],
    ['listRoles', '/admin/rbac/roles'],
    ['listAssignableMenus', '/admin/rbac/roles/menus'],
    ['listMenus', '/admin/rbac/menus'],
    ['listPermissionDefinitions', '/admin/rbac/permissions'],
  ])('%s reads through the authenticated API', async (method, path) => {
    const fetch = vi.fn().mockResolvedValue(reply(200, []))
    vi.stubGlobal('fetch', fetch)
    await expect(users[method]()).resolves.toEqual([])
    expect(fetch).toHaveBeenCalledWith(`/api${path}`, expect.objectContaining({ credentials: 'include' }))
  })

  it.each([
    ['createUser', '/admin/users', 'POST', false],
    ['updateUser', '/admin/users/a%2Fb', 'PUT', true],
    ['createRole', '/admin/rbac/roles', 'POST', false],
    ['updateRole', '/admin/rbac/roles/a%2Fb', 'PUT', true],
    ['createMenu', '/admin/rbac/menus', 'POST', false],
    ['updateMenu', '/admin/rbac/menus/a%2Fb', 'PUT', true],
  ])('%s encodes IDs and sends JSON', async (method, path, verb, hasId) => {
    const fetch = vi.fn().mockResolvedValue(reply())
    vi.stubGlobal('fetch', fetch)
    const payload = { name: 'synthetic' }
    await expect(users[method](...(hasId ? ['a/b', payload] : [payload]))).resolves.toEqual({ id: 'saved' })
    expect(fetch).toHaveBeenCalledWith(`/api${path}`, {
      method: verb,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  })

  it.each([
    ['deleteRole', 'roles'],
    ['deleteMenu', 'menus'],
  ])('%s propagates permission rejection', async (method, resource) => {
    const fetch = vi.fn().mockResolvedValue(reply(403))
    vi.stubGlobal('fetch', fetch)
    await expect(users[method]('a/b')).rejects.toMatchObject({ code: 403, message: 'permission denied' })
    expect(fetch).toHaveBeenCalledWith(
      `/api/admin/rbac/${resource}/a%2Fb`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('password change sends both values without putting them in the URL', async () => {
    const fetch = vi.fn().mockResolvedValue(reply())
    vi.stubGlobal('fetch', fetch)
    await users.changeUserPassword('a/b', 'synthetic-old', 'synthetic-new')
    expect(fetch).toHaveBeenCalledWith(
      '/api/admin/users/a%2Fb/password',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ oldPassword: 'synthetic-old', newPassword: 'synthetic-new' }),
      }),
    )
  })
})
