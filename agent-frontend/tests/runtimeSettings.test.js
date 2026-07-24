import { describe, expect, it } from 'vitest'
import {
  clampRuntimeNumber,
  normalizeRuntimeSettings,
  RUNTIME_SETTING_LIMITS,
} from '../src/composables/useRuntimeSettings'

describe('clampRuntimeNumber', () => {
  it('uses fallback for invalid input and rounds valid numbers', () => {
    const limits = { min: 1, max: 10, fallback: 5 }
    expect(clampRuntimeNumber('', limits)).toBe(1)
    expect(clampRuntimeNumber('invalid', limits)).toBe(5)
    expect(clampRuntimeNumber(3.6, limits)).toBe(4)
  })

  it('clamps values into the configured range', () => {
    const limits = { min: 1, max: 10, fallback: 5 }
    expect(clampRuntimeNumber(-1, limits)).toBe(1)
    expect(clampRuntimeNumber(20, limits)).toBe(10)
  })
})

describe('normalizeRuntimeSettings', () => {
  it('returns all supported business settings with defaults', () => {
    const normalized = normalizeRuntimeSettings()
    expect(Object.keys(normalized)).toEqual(Object.keys(RUNTIME_SETTING_LIMITS))
    expect(normalized.maxJobsPerRecommend).toBe(15)
    expect(normalized.minimumRecommendedMatchScore).toBe(60)
    expect(normalized.runtimeMaxTurns).toBe(12)
    expect(normalized.runtimeMaxToolCalls).toBe(20)
    expect(normalized.runtimeMaxFailures).toBe(3)
    expect(normalized.resumeWriterVersionLimit).toBe(30)
  })

  it('drops unsupported fields and normalizes every limit', () => {
    const normalized = normalizeRuntimeSettings({
      name: 'unsupported',
      defaultUserId: 'unsupported-user',
      resumeRuntimeWorkspace: '/tmp/unsupported',
      maxJobsPerRecommend: 99,
      recommendOverfetchFactor: 0,
      maxJobsPerScoring: 500,
      minimumRecommendedMatchScore: 500,
      bossSearchMaxPages: 0,
      bossSearchMaxPageDepth: 99,
      bossSearchCacheTtlMinutes: 0,
      bossSearchCooldownMinutesOnRisk: 9999,
      runtimeMaxTurns: 0,
      runtimeMaxToolCalls: 99,
      runtimeMaxFailures: 0,
      maxResumeBytes: 1,
      resumeWriterVersionLimit: 999,
    })

    expect(normalized).toEqual({
      maxJobsPerRecommend: 30,
      recommendOverfetchFactor: 1,
      minimumRecommendedMatchScore: 100,
      bossSearchMaxPages: 1,
      bossSearchMaxPageDepth: 10,
      bossSearchCacheTtlMinutes: 1,
      bossSearchCooldownMinutesOnRisk: 1440,
      runtimeMaxTurns: 1,
      runtimeMaxToolCalls: 30,
      runtimeMaxFailures: 1,
      maxResumeBytes: 1024 * 1024,
      resumeWriterVersionLimit: 100,
    })
  })
})
