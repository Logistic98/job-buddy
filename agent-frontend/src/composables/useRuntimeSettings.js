export const RUNTIME_SETTING_LIMITS = {
  maxJobsPerRecommend: { min: 1, max: 30, fallback: 15 },
  recommendOverfetchFactor: { min: 1, max: 10, fallback: 5 },
  minimumRecommendedMatchScore: { min: 0, max: 100, fallback: 60 },
  bossSearchMaxPages: { min: 1, max: 5, fallback: 2 },
  bossSearchMaxPageDepth: { min: 1, max: 10, fallback: 5 },
  bossSearchCacheTtlMinutes: { min: 1, max: 1440, fallback: 30 },
  bossSearchCooldownMinutesOnRisk: { min: 1, max: 1440, fallback: 30 },
  runtimeMaxTurns: { min: 1, max: 20, fallback: 12 },
  runtimeMaxToolCalls: { min: 1, max: 30, fallback: 20 },
  runtimeMaxFailures: { min: 1, max: 10, fallback: 3 },
  maxResumeBytes: { min: 1024 * 1024, max: 20 * 1024 * 1024, fallback: 5 * 1024 * 1024 },
  resumeWriterVersionLimit: { min: 5, max: 100, fallback: 30 },
}

export function clampRuntimeNumber(value, limits) {
  const number = Number(value)
  if (!Number.isFinite(number)) return limits.fallback
  return Math.min(limits.max, Math.max(limits.min, Math.round(number)))
}

export function normalizeRuntimeSettings(workspace) {
  const source = workspace || {}
  const normalized = {}
  Object.entries(RUNTIME_SETTING_LIMITS).forEach(([key, limits]) => {
    normalized[key] = clampRuntimeNumber(source[key], limits)
  })
  return normalized
}
