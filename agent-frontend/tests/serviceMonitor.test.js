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

  it('renders degraded checks separately and excludes them from the running success rate', async () => {
    getSettings.mockResolvedValueOnce({
      services: {},
      serviceStatuses: serviceStatuses([
        { status: 'running', checkedAt: firstCheckedAt, message: '运行中' },
        { status: 'degraded', checkedAt: secondCheckedAt, message: '运行降级：gateway unavailable' },
      ]),
    })

    const wrapper = mount(ServiceMonitorPanel)
    await flushPromises()

    expect(wrapper.get('.health-state.degraded').text()).toBe('运行降级')
    expect(wrapper.text()).toContain('50.00% 运行成功率')
    expect(wrapper.findAll('.uptime-bar.degraded')).toHaveLength(1)

    await wrapper.find('.service-health-summary').trigger('click')
    expect(wrapper.findAll('.history-dot.degraded')).toHaveLength(1)
    expect(wrapper.text()).toContain('运行降级：gateway unavailable')

    wrapper.unmount()
  })

  it('clears a stale refresh error after the next successful health check', async () => {
    refreshServiceHealth
      .mockRejectedValueOnce(new Error('第一次刷新失败'))
      .mockRejectedValueOnce(new Error('第二次刷新失败'))

    const wrapper = mount(ServiceMonitorPanel)
    await flushPromises()
    const refreshButton = wrapper.find('.service-health-head .secondary-btn')

    await refreshButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('第一次刷新失败')

    await refreshButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('第二次刷新失败')

    refreshServiceHealth.mockResolvedValueOnce(
      serviceStatuses([{ status: 'running', checkedAt: secondCheckedAt, message: '运行中' }]),
    )
    await refreshButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('clears a stale polling error after the next successful status load', async () => {
    const wrapper = mount({
      components: { ServiceMonitorPanel },
      template: '<KeepAlive><ServiceMonitorPanel /></KeepAlive>',
    })
    await flushPromises()

    getSettings.mockRejectedValueOnce(new Error('轮询失败'))
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('轮询失败')

    getSettings.mockResolvedValueOnce({
      services: {},
      serviceStatuses: serviceStatuses([{ status: 'running', checkedAt: secondCheckedAt, message: '运行中' }]),
    })
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows environment-managed container addresses as read-only', async () => {
    getSettings.mockResolvedValueOnce({
      services: {
        intentUrl: 'http://agent-intent:8020',
        runtimeUrl: 'http://agent-runtime:8010',
        memoryUrl: 'http://agent-memory:8030',
        toolUrl: 'http://agent-tool:8040',
        evalUrl: 'http://agent-eval:8050',
        sandboxUrl: 'http://agent-sandbox:8061',
      },
      serviceStatuses: {},
    })

    const wrapper = mount(ServiceMonitorPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('当前地址由部署环境管理')
    expect(wrapper.find('input[type="url"]').element.value).toBe('http://agent-intent:8020')
    expect(wrapper.findAll('input[type="url"]').every((input) => input.element.disabled)).toBe(true)

    wrapper.unmount()
  })
})
