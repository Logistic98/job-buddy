import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useExamTimer } from '../src/composables/useExamTimer'

describe('useExamTimer', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts with minutes by default and counts down per second', () => {
    const onExpire = vi.fn()
    const timer = useExamTimer(onExpire)
    timer.startExamTimer(2)
    expect(timer.timerRemaining.value).toBe(120)
    vi.advanceTimersByTime(3000)
    expect(timer.timerRemaining.value).toBe(117)
    expect(onExpire).not.toHaveBeenCalled()
    timer.stopExamTimer()
  })

  it('supports seconds mode and fires onExpire exactly once at zero', () => {
    const onExpire = vi.fn()
    const timer = useExamTimer(onExpire)
    timer.startExamTimer(2, true)
    expect(timer.timerRemaining.value).toBe(2)
    vi.advanceTimersByTime(5000)
    expect(timer.timerRemaining.value).toBe(0)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('falls back to 30 minutes for invalid input and keeps at least 1 minute', () => {
    const timer = useExamTimer(() => {})
    timer.startExamTimer(undefined)
    expect(timer.timerRemaining.value).toBe(30 * 60)
    timer.startExamTimer(0.2)
    expect(timer.timerRemaining.value).toBe(60)
    timer.stopExamTimer()
  })

  it('restart replaces the previous interval instead of stacking', () => {
    const onExpire = vi.fn()
    const timer = useExamTimer(onExpire)
    timer.startExamTimer(3, true)
    timer.startExamTimer(10, true)
    vi.advanceTimersByTime(4000)
    expect(timer.timerRemaining.value).toBe(6)
    expect(onExpire).not.toHaveBeenCalled()
    timer.stopExamTimer()
  })

  it('stopExamTimer resets remaining and formats text as mm:ss', () => {
    const timer = useExamTimer(() => {})
    timer.startExamTimer(90, true)
    expect(timer.remainingTimeText.value).toBe('01:30')
    timer.stopExamTimer()
    expect(timer.timerRemaining.value).toBe(0)
    expect(timer.remainingTimeText.value).toBe('00:00')
  })
})
