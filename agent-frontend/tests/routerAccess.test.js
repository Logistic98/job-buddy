import { describe, expect, it } from 'vitest'
import { firstAllowedPath } from '../src/router'

describe('router access fallback', () => {
  it('uses an allowed menu when one is available', () => {
    const auth = {
      isAdmin: false,
      menus: [
        { routePath: '/chat', permissionCode: 'chat:use', componentKey: 'chat' },
        { routePath: '/jobs', permissionCode: 'jobs:use', componentKey: 'jobs' },
      ],
      hasPermission: (permission) => permission === 'jobs:use',
    }

    expect(firstAllowedPath(auth)).toBe('/jobs')
  })

  it('uses a stable access-denied route when no menu is usable', () => {
    const auth = {
      isAdmin: false,
      menus: [{ routePath: '/chat', permissionCode: 'chat:use', componentKey: 'chat' }],
      hasPermission: () => false,
    }

    expect(firstAllowedPath(auth)).toBe('/access-denied')
  })
})
