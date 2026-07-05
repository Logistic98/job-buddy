// Countdown timer for practice exams. Owns the interval lifecycle and remaining-seconds state;
// the caller supplies an onExpire callback that fires once when the countdown reaches zero.

import { computed, ref } from 'vue'
import { formatRemainingTime } from '../utils/interviewBank'

export function useExamTimer(onExpire) {
  const timerRemaining = ref(0)
  let timerId = null

  const remainingTimeText = computed(() => formatRemainingTime(timerRemaining.value))

  function startExamTimer(value, secondsMode = false) {
    stopExamTimer()
    timerRemaining.value = secondsMode ? Math.max(0, Number(value || 0)) : Math.max(1, Number(value || 30)) * 60
    timerId = window.setInterval(() => {
      timerRemaining.value = Math.max(0, timerRemaining.value - 1)
      if (timerRemaining.value <= 0) {
        stopExamTimer(false)
        onExpire()
      }
    }, 1000)
  }

  function stopExamTimer(reset = true) {
    if (timerId) window.clearInterval(timerId)
    timerId = null
    if (reset) timerRemaining.value = 0
  }

  return { timerRemaining, remainingTimeText, startExamTimer, stopExamTimer }
}
