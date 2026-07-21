import { describe, expect, it } from 'vitest'
import { formatJourneyDateTime, toJourneyDateTimeLocalValue } from '../src/utils/journeyDateTime'

describe('journey date time formatting', () => {
  it.each([
    ['2026-07-19T11:00', '2026-07-19 11:00'],
    ['2026-07-19T11:00:00', '2026-07-19 11:00'],
    ['2026-07-19 11:00:00', '2026-07-19 11:00'],
  ])('formats %s without a T separator', (value, expected) => {
    expect(formatJourneyDateTime(value)).toBe(expected)
  })

  it('keeps an empty value empty', () => {
    expect(formatJourneyDateTime('')).toBe('')
  })

  it('normalizes backend values for datetime-local editing', () => {
    expect(toJourneyDateTimeLocalValue('2026-07-19T11:00:00')).toBe('2026-07-19T11:00')
    expect(toJourneyDateTimeLocalValue('2026-07-19 11:00')).toBe('2026-07-19T11:00')
  })
})
