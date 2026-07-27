import { afterEach, describe, expect, it, vi } from 'vitest'
import { createUuid } from '../src/utils/clientId'

const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('createUuid', () => {
  it('uses the native UUID implementation when available', () => {
    vi.stubGlobal('crypto', { randomUUID: () => '11111111-1111-4111-8111-111111111111' })

    expect(createUuid()).toBe('11111111-1111-4111-8111-111111111111')
  })

  it('generates a UUID on an HTTP origin where randomUUID is unavailable', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes) => {
        bytes.set(Array.from({ length: bytes.length }, (_, index) => index))
        return bytes
      },
    })

    expect(createUuid()).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
    expect(createUuid()).toMatch(uuidV4Pattern)
  })

  it('keeps legacy browsers functional when Web Crypto is unavailable', () => {
    vi.stubGlobal('crypto', undefined)

    expect(createUuid()).toMatch(uuidV4Pattern)
  })
})
