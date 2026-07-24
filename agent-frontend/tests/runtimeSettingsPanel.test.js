import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RuntimeSettingsPanel from '../src/components/settings/RuntimeSettingsPanel.vue'

const mocks = vi.hoisted(() => ({
  getBossLoginStatus: vi.fn(),
  getSettings: vi.fn(),
  restoreWorkspaceDefaults: vi.fn(),
  saveSettings: vi.fn(),
}))

vi.mock('../src/api/boss', () => ({
  getBossLoginStatus: mocks.getBossLoginStatus,
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
      bossSearchMaxPages: 2,
      bossSearchMaxPageDepth: 5,
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
    mocks.getSettings.mockReset().mockResolvedValue(settings(25))
    mocks.restoreWorkspaceDefaults.mockReset().mockResolvedValue(settings(15))
    mocks.saveSettings.mockReset()
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
})
