import { describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  saveSettings: vi.fn(),
}))

vi.mock('../src/api/settings', () => ({
  getSettings: mocks.getSettings,
  saveSettings: mocks.saveSettings,
}))

import { useScopedSettings } from '../src/composables/useScopedSettings'

const settings = {
  workspace: { runtimeMaxTurns: 6 },
  blacklist: { enabled: true, items: [] },
  memory: { enabled: true, maxItems: 200 },
  services: { runtimeUrl: 'http://localhost:8010' },
}

describe('useScopedSettings', () => {
  it.each(['workspace', 'blacklist', 'memory', 'services'])(
    'only submits the active %s settings scope',
    async (scope) => {
      mocks.getSettings.mockReset().mockResolvedValue(settings)
      mocks.saveSettings.mockReset().mockImplementation(async (payload) => ({ ...settings, ...payload }))
      const scoped = useScopedSettings(scope)

      await scoped.load()
      scoped.value.value = { ...scoped.value.value, changed: true }
      await scoped.save()

      expect(mocks.saveSettings).toHaveBeenCalledTimes(1)
      expect(mocks.saveSettings).toHaveBeenCalledWith({ [scope]: { ...settings[scope], changed: true } })
      expect(Object.keys(mocks.saveSettings.mock.calls[0][0])).toEqual([scope])
    },
  )
})
