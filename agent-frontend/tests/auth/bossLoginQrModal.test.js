import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  cancelBossLogin: vi.fn(async () => ({})),
  getBossLoginQr: vi.fn(async () => ({})),
  getBossLoginStatus: vi.fn(async () => ({})),
}))

vi.mock('../../src/api/boss', () => mocks)

import BossLoginQrModal from '../../src/components/BossLoginQrModal.vue'

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

    await Promise.resolve()
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.qr-wrap').exists()).toBe(false)
    expect(wrapper.find('.qr-placeholder').text()).toContain('正在建立扫码连接')

    await vi.advanceTimersByTimeAsync(2999)
    expect(wrapper.find('.qr-wrap').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.find('.qr-wrap').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(7000)
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
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.login-status-card').text()).toContain('正在连接 Boss 扫码服务')

    await Promise.resolve()
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

describe('Boss login lifecycle and failures', () => {
  const qr = { qrSessionId: 'qr-test', imageBase64: 'dGVzdA==', status: 'qr_ready' }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    vi.resetAllMocks()
    mocks.cancelBossLogin.mockResolvedValue({})
    mocks.getBossLoginQr.mockResolvedValue(qr)
    mocks.getBossLoginStatus.mockResolvedValue({ status: 'waiting' })
  })
  afterEach(() => vi.useRealTimers())

  it('checks saved login before requesting QR and emits success once', async () => {
    mocks.getBossLoginStatus.mockResolvedValue({ authenticated: true })
    const wrapper = mount(BossLoginQrModal, { props: { visible: true, sessionId: 'session' } })
    expect(wrapper.text()).toContain('正在确认登录态')
    await flushPromises()
    expect(wrapper.text()).toContain('登录成功')
    expect(wrapper.emitted('logged-in')).toHaveLength(1)
    expect(mocks.getBossLoginQr).not.toHaveBeenCalled()
    await wrapper.find('.close').trigger('click')
    expect(mocks.cancelBossLogin).not.toHaveBeenCalled()
  })

  it.each([false, true])('creates QR when initial login check is unavailable: %s', async (failed) => {
    if (failed) mocks.getBossLoginStatus.mockRejectedValueOnce(new Error('status offline'))
    const wrapper = mount(BossLoginQrModal, { props: { visible: true, sessionId: 'session' } })
    await flushPromises()
    expect(mocks.getBossLoginQr).toHaveBeenCalledWith('session')
    expect(wrapper.text()).toContain('正在准备二维码')
    await wrapper.find('.close').trigger('click')
    expect(mocks.cancelBossLogin).toHaveBeenCalledWith('session', 'qr-test')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it.each(['exception', 'envelope'])('shows QR generation %s failure and permits retry', async (kind) => {
    if (kind === 'exception') mocks.getBossLoginQr.mockRejectedValueOnce(new Error('QR offline'))
    else mocks.getBossLoginQr.mockResolvedValueOnce({ error: { message: 'QR unavailable' } })
    const wrapper = mount(BossLoginQrModal, { props: { visible: true, data: { authRequired: true } } })
    await flushPromises()
    expect(wrapper.find('.error').text()).toContain('QR')
    await wrapper.find('.modal-actions button').trigger('click')
    await flushPromises()
    expect(mocks.getBossLoginQr).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error').exists()).toBe(false)
  })

  it('expires an inactive QR and stops further polling', async () => {
    const wrapper = mount(BossLoginQrModal, {
      props: { visible: true, data: { ...qr, expiresAt: '2026-01-01T00:00:02Z' } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.text()).toContain('二维码已过期')
    const calls = mocks.getBossLoginStatus.mock.calls.length
    await vi.advanceTimersByTimeAsync(10000)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(calls)
    await wrapper.find('.close').trigger('click')
    expect(mocks.cancelBossLogin).not.toHaveBeenCalled()
  })

  it.each(['logged_in', 'expired', 'cancelled', 'error'])('stops at terminal status %s', async (status) => {
    mocks.getBossLoginStatus.mockResolvedValueOnce({
      status,
      error: status === 'error' ? { message: 'Login rejected' } : undefined,
    })
    const wrapper = mount(BossLoginQrModal, { props: { visible: true, data: qr } })
    await flushPromises()
    expect(wrapper.find('.login-status-card').classes()).toContain(status === 'logged_in' ? 'is-success' : 'is-warning')
    await vi.advanceTimersByTimeAsync(5000)
    expect(mocks.getBossLoginStatus).toHaveBeenCalledOnce()
    if (status === 'error') expect(wrapper.find('.error').text()).toBe('Login rejected')
    if (status === 'logged_in') expect(wrapper.emitted('logged-in')).toHaveLength(1)
  })

  it('manual status checks expose failures and accept refreshed live QR', async () => {
    const wrapper = mount(BossLoginQrModal, { props: { visible: true, data: qr } })
    await flushPromises()
    mocks.getBossLoginStatus.mockRejectedValueOnce(new Error('check offline'))
    await wrapper.findAll('.modal-actions button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.error').text()).toBe('check offline')
    mocks.getBossLoginStatus.mockResolvedValue({ status: 'scanned', imageBase64: 'bmV3' })
    await wrapper.findAll('.modal-actions button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已扫码')
    expect(wrapper.find('.error').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.text()).toContain('秒')
  })

  it('ignores a late login check after the dialog is hidden', async () => {
    let resolve
    mocks.getBossLoginStatus.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = mount(BossLoginQrModal, { props: { visible: true } })
    await wrapper.setProps({ visible: false })
    resolve({ authenticated: true })
    await flushPromises()
    expect(wrapper.emitted('logged-in')).toBeUndefined()
    expect(mocks.getBossLoginQr).not.toHaveBeenCalled()
  })
})
