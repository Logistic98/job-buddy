import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RuntimeSettingsPanel from '../src/components/settings/RuntimeSettingsPanel.vue'

const mocks = vi.hoisted(() => ({
  getBossLoginStatus: vi.fn(),
  logoutBoss: vi.fn(),
  getSettings: vi.fn(),
  restoreWorkspaceDefaults: vi.fn(),
  saveSettings: vi.fn(),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: mocks.getBossLoginStatus,
  logoutBoss: mocks.logoutBoss,
}))

vi.mock('../src/api/settings', () => ({
  getSettings: mocks.getSettings,
  restoreWorkspaceDefaults: mocks.restoreWorkspaceDefaults,
  saveSettings: mocks.saveSettings,
}))

function settings(maxJobsPerRecommend) {
  return {
    workspace: {
      maxJobsPerRecommend,
      recommendOverfetchFactor: 5,
      minimumRecommendedMatchScore: 60,
      bossSearchMaxPages: 3,
      bossSearchMaxPageDepth: 15,
      bossSearchCacheTtlMinutes: 30,
      bossSearchCooldownMinutesOnRisk: 30,
      runtimeMaxTurns: 12,
      runtimeMaxToolCalls: 20,
      runtimeMaxFailures: 3,
      maxResumeBytes: 5 * 1024 * 1024,
      resumeWriterVersionLimit: 30,
    },
  }
}

async function mountPanel() {
  const wrapper = mount(RuntimeSettingsPanel, {
    global: {
      plugins: [createPinia()],
      stubs: { BossLoginQrModal: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('RuntimeSettingsPanel restore defaults', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.getBossLoginStatus.mockReset().mockResolvedValue({ status: 'unknown' })
    mocks.logoutBoss.mockReset().mockResolvedValue({ ok: true, status: 'logged_out' })
    mocks.getSettings.mockReset().mockResolvedValue(settings(25))
    mocks.restoreWorkspaceDefaults.mockReset().mockResolvedValue(settings(15))
    mocks.saveSettings.mockReset()
  })

  it('exposes the expanded Boss page-depth range without changing the candidate multiplier range', async () => {
    const wrapper = await mountPanel()
    const inputs = wrapper.findAll('input')

    expect(inputs[1].attributes('max')).toBe('10')
    expect(inputs[3].element.value).toBe('3')
    expect(inputs[4].element.value).toBe('15')
    expect(inputs[4].attributes('max')).toBe('30')
  })

  it('requires confirmation and does not restore when cancelled', async () => {
    const wrapper = await mountPanel()

    wrapper.vm.openRestoreConfirm()
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[role="dialog"]').text()).toContain('恢复默认参数')

    const cancelButton = wrapper.findAll('.runtime-restore-actions button').find((button) => button.text() === '取消')
    await cancelButton.trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(mocks.restoreWorkspaceDefaults).not.toHaveBeenCalled()
  })

  it('restores persisted defaults and refreshes the form after confirmation', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.get('input').element.value).toBe('25')

    wrapper.vm.openRestoreConfirm()
    await wrapper.vm.$nextTick()
    const confirmButton = wrapper
      .findAll('.runtime-restore-actions button')
      .find((button) => button.text() === '确认恢复')
    await confirmButton.trigger('click')
    await flushPromises()

    expect(mocks.restoreWorkspaceDefaults).toHaveBeenCalledOnce()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('input').element.value).toBe('15')
  })

  it('keeps the confirmation open and shows restore failures', async () => {
    mocks.restoreWorkspaceDefaults.mockRejectedValueOnce(new Error('后端恢复失败'))
    const wrapper = await mountPanel()

    wrapper.vm.openRestoreConfirm()
    await wrapper.vm.$nextTick()
    const confirmButton = wrapper
      .findAll('.runtime-restore-actions button')
      .find((button) => button.text() === '确认恢复')
    await confirmButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain('后端恢复失败')
    expect(wrapper.get('input').element.value).toBe('25')
  })

  it('does not open the QR modal when a fresh status check reports logged in', async () => {
    mocks.getBossLoginStatus
      .mockResolvedValueOnce({ status: 'unknown' })
      .mockResolvedValueOnce({ ok: true, authenticated: true, status: 'logged_in' })
    const wrapper = await mountPanel()

    const loginButton = wrapper.findAll('.auth-actions button').find((button) => button.text() === '扫码登录')
    await loginButton.trigger('click')
    await flushPromises()

    expect(mocks.getBossLoginStatus).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'BossLoginQrModal' }).props('visible')).toBe(false)
    expect(wrapper.findAll('.auth-actions button').some((button) => button.text() === '退出登录')).toBe(true)
  })

  it('confirms Boss logout and switches the action back to QR login', async () => {
    mocks.getBossLoginStatus.mockResolvedValueOnce({ ok: true, authenticated: true, status: 'logged_in' })
    const wrapper = await mountPanel()

    const logoutButton = wrapper.findAll('.auth-actions button').find((button) => button.text() === '退出登录')
    await logoutButton.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('JobBuddy 登录状态不受影响')
    expect(mocks.logoutBoss).not.toHaveBeenCalled()

    const confirmButton = wrapper.findAll('[role="dialog"] button').find((button) => button.text() === '确认退出')
    await confirmButton.trigger('click')
    await flushPromises()

    expect(mocks.logoutBoss).toHaveBeenCalledOnce()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.findAll('.auth-actions button').some((button) => button.text() === '扫码登录')).toBe(true)
    expect(wrapper.text()).toContain('已退出 Boss 直聘登录')
  })
})
