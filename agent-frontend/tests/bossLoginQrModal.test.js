import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  cancelBossLogin: vi.fn(async () => ({})),
  getBossLoginQr: vi.fn(async () => ({})),
  getBossLoginStatus: vi.fn(async () => ({})),
}))

vi.mock('../src/api/boss', () => mocks)

import BossLoginQrModal from '../src/components/BossLoginQrModal.vue'

describe('BossLoginQrModal embedded mode', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders QR content without a second mask or nested modal card', () => {
    const wrapper = mount(BossLoginQrModal, {
      props: {
        visible: true,
        embedded: true,
        data: {
          imageBase64: 'dGVzdA==',
          imageMime: 'image/png',
          status: 'qr_ready',
        },
      },
    })

    expect(wrapper.find('.boss-login-embedded').exists()).toBe(true)
    expect(wrapper.find('.boss-login-modal-mask').exists()).toBe(false)
    expect(wrapper.find('.boss-login-modal-card').exists()).toBe(false)
    expect(wrapper.find('.close').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('当前未登录')
  })

  it('waits for a slow status request before scheduling the next poll', async () => {
    vi.useFakeTimers()
    let resolveFirst
    let resolveSecond
    mocks.getBossLoginStatus
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveSecond = resolve
          }),
      )
    const wrapper = mount(BossLoginQrModal, {
      props: {
        visible: true,
        embedded: true,
        sessionId: 'boss-favorite-import',
        data: {
          qrSessionId: 'qr-session-1',
          imageBase64: 'dGVzdA==',
          imageMime: 'image/png',
          status: 'qr_ready',
        },
      },
    })

    await vi.advanceTimersByTimeAsync(1000)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10000)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(1)

    resolveFirst({ status: 'waiting' })
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(999)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    resolveSecond({ status: 'waiting' })
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(10000)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(2)
  })

  it('shows scanned and confirmed stages as soon as polling returns them', async () => {
    vi.useFakeTimers()
    mocks.getBossLoginStatus.mockResolvedValueOnce({ status: 'scanned' }).mockResolvedValueOnce({ status: 'confirmed' })
    const wrapper = mount(BossLoginQrModal, {
      props: {
        visible: true,
        embedded: true,
        sessionId: 'boss-status-progress',
        data: {
          qrSessionId: 'qr-session-progress',
          imageBase64: 'dGVzdA==',
          imageMime: 'image/png',
          status: 'qr_ready',
        },
      },
    })

    expect(wrapper.findAll('.login-stage')[0].classes()).toContain('current')
    expect(wrapper.find('.login-status-card').text()).toContain('状态每秒自动更新')

    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.find('.login-status-card strong').text()).toBe('已扫码，请在手机上确认登录')
    expect(wrapper.findAll('.login-stage')[0].classes()).toContain('done')
    expect(wrapper.findAll('.login-stage')[1].classes()).toContain('current')

    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.find('.login-status-card strong').text()).toBe('已确认，保存登录态中')
    expect(wrapper.findAll('.login-stage')[1].classes()).toContain('done')
    expect(wrapper.findAll('.login-stage')[2].classes()).toContain('current')

    wrapper.unmount()
  })
})
