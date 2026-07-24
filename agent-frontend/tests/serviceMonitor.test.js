import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ServiceMonitorPanel from '../src/components/settings/ServiceMonitorPanel.vue'
import { getSettings, refreshServiceHealth } from '../src/api/settings'

vi.mock('../src/api/settings', () => ({
  getSettings: vi.fn(),
  refreshServiceHealth: vi.fn(),
  saveSettings: vi.fn(),
}))

const firstCheckedAt = '2026-07-21T11:40:00Z'
const secondCheckedAt = '2026-07-21T11:41:00Z'

function serviceStatuses(history) {
  return {
    runtime: {
      id: 'runtime',
      name: 'Agent Runtime',
      url: 'http://127.0.0.1:8010',
      healthUrl: 'http://127.0.0.1:8010/health',
      status: history.at(-1).status,
      checkedAt: history.at(-1).checkedAt,
      message: history.at(-1).message,
      history,
    },
    sandbox: {
      id: 'sandbox',
      name: 'Sandbox Service',
      url: 'http://127.0.0.1:8061',
      healthUrl: 'http://127.0.0.1:8061/health',
      status: 'running',
      checkedAt: secondCheckedAt,
      message: '运行中',
      history: [{ status: 'running', checkedAt: secondCheckedAt, message: '运行中' }],
    },
  }
}

describe('ServiceMonitorPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    getSettings.mockResolvedValue({
      services: {},
      serviceStatuses: serviceStatuses([
        { status: 'running', checkedAt: firstCheckedAt, message: '运行中' },
        { status: 'down', checkedAt: secondCheckedAt, message: '服务不可达' },
      ]),
    })
    refreshServiceHealth.mockResolvedValue(
      serviceStatuses([
        { status: 'running', checkedAt: firstCheckedAt, message: '运行中' },
        { status: 'down', checkedAt: secondCheckedAt, message: '服务不可达' },
        { status: 'running', checkedAt: '2026-07-21T11:42:00Z', message: '运行中' },
      ]),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('renders downstream history including Sandbox without starting a forced refresh', async () => {
    const wrapper = mount(ServiceMonitorPanel)
    await flushPromises()

    expect(refreshServiceHealth).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('50.00%')
    expect(wrapper.text()).toContain('Sandbox Service')
    expect(wrapper.text()).toContain('http://127.0.0.1:8061/health')

    await wrapper.find('.service-health-summary').trigger('click')
    expect(wrapper.text()).toContain('监测次数：2')

    wrapper.unmount()
  })

  it('runs a health check only when the refresh button is clicked', async () => {
    const wrapper = mount(ServiceMonitorPanel)
    await flushPromises()

    await wrapper.find('.service-health-head .secondary-btn').trigger('click')
    await flushPromises()

    expect(refreshServiceHealth).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('66.67%')

    wrapper.unmount()
  })
})
