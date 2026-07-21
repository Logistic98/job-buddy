import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AUTH_SYNC_CHANNEL,
  AUTH_USER_KEY,
  clearAuthSession,
  publishAuthLogout,
  readAuthUser,
  saveAuthUser,
  subscribeAuthLogout,
} from '../src/utils/authStorage'

let channels = []
let publishedMessages = []

class MockBroadcastChannel {
  constructor(name) {
    this.name = name
    this.onmessage = null
    channels.push(this)
  }

  postMessage(data) {
    publishedMessages.push(data)
    channels
      .filter((channel) => channel !== this && channel.name === this.name)
      .forEach((channel) => channel.onmessage?.({ data }))
  }

  close() {
    channels = channels.filter((channel) => channel !== this)
  }
}

describe('auth storage', () => {
  beforeEach(() => {
    channels = []
    publishedMessages = []
    window.sessionStorage.clear()
    vi.stubGlobal('BroadcastChannel', MockBroadcastChannel)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('caches only non-sensitive user display data in the current tab', () => {
    const user = { username: 'admin', permissions: ['resume:use'] }

    saveAuthUser(user)

    expect(window.sessionStorage.getItem(AUTH_USER_KEY)).toBe(JSON.stringify(user))
    expect(readAuthUser()).toEqual(user)
    expect(JSON.stringify(window.sessionStorage)).not.toContain('token-value')
  })

  it('clears the tab-scoped login cache on logout', () => {
    saveAuthUser({ username: 'admin' })

    clearAuthSession()

    expect(window.sessionStorage.getItem(AUTH_USER_KEY)).toBeNull()
  })

  it('publishes a credential-free cross-tab logout message', () => {
    publishAuthLogout()

    expect(publishedMessages).toHaveLength(1)
    expect(publishedMessages[0].type).toBe('logout')
    expect(publishedMessages[0].nonce).toBeTruthy()
    expect(JSON.stringify(publishedMessages[0])).not.toContain('token-value')
  })

  it('notifies subscribers when another tab publishes logout', () => {
    const onLogout = vi.fn()
    const unsubscribe = subscribeAuthLogout(onLogout)
    const anotherTab = new BroadcastChannel(AUTH_SYNC_CHANNEL)

    anotherTab.postMessage({ type: 'logout', nonce: 'another-tab' })

    expect(onLogout).toHaveBeenCalledTimes(1)
    unsubscribe()
    anotherTab.close()
  })
})
