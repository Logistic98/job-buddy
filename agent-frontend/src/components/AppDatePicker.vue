<template>
  <div
    ref="root"
    class="app-date-picker"
    :class="{
      'is-open': open,
      'is-drop-up': dropUp,
      'is-month': type === 'month',
      'is-datetime': type === 'datetime',
      'has-value': !!modelValue,
    }"
  >
    <button
      type="button"
      class="app-date-picker-trigger"
      :class="{ 'is-placeholder': !modelValue }"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
      @keydown.down.prevent="openPicker"
    >
      <span>{{ displayValue }}</span>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 3v3m10-3v3M4.5 9h15M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z" />
      </svg>
    </button>

    <div
      v-if="open"
      ref="popover"
      class="app-date-picker-popover"
      role="dialog"
      :style="{ '--app-date-picker-shift': `${panelShift}px` }"
      :aria-label="`${ariaLabel}选择面板`"
    >
      <div class="app-date-picker-header">
        <button type="button" :aria-label="type === 'month' ? '上一年' : '上个月'" @click="movePanel(-1)">‹</button>
        <strong>{{ panelTitle }}</strong>
        <button type="button" :aria-label="type === 'month' ? '下一年' : '下个月'" @click="movePanel(1)">›</button>
      </div>

      <div v-if="type === 'month'" class="app-date-picker-months">
        <button
          v-for="month in 12"
          :key="month"
          type="button"
          :class="{ selected: isSelectedMonth(month), current: isCurrentMonth(month) }"
          :aria-pressed="isSelectedMonth(month)"
          @click="selectMonth(month)"
        >
          {{ month }}月
        </button>
      </div>

      <template v-else>
        <div class="app-date-picker-weekdays" aria-hidden="true">
          <span v-for="weekday in weekdays" :key="weekday">{{ weekday }}</span>
        </div>
        <div class="app-date-picker-days">
          <button
            v-for="day in calendarDays"
            :key="day.key"
            type="button"
            :class="{ muted: !day.inPanelMonth, selected: isSelectedDay(day), current: day.isToday }"
            :aria-label="day.ariaLabel"
            :aria-pressed="isSelectedDay(day)"
            @click="selectDay(day)"
          >
            {{ day.day }}
          </button>
        </div>
        <div class="app-date-picker-time">
          <span>时间</span>
          <select v-model="pendingHour" aria-label="小时">
            <option v-for="hour in hours" :key="hour" :value="hour">{{ hour }}</option>
          </select>
          <i>:</i>
          <select v-model="pendingMinute" aria-label="分钟">
            <option v-for="minute in minutes" :key="minute" :value="minute">{{ minute }}</option>
          </select>
        </div>
      </template>

      <div class="app-date-picker-footer">
        <button type="button" class="subtle" @click="clearValue">清除</button>
        <span></span>
        <button type="button" class="subtle" @click="selectCurrent">{{ type === 'month' ? '本月' : '现在' }}</button>
        <button v-if="type === 'datetime'" type="button" class="confirm" @click="confirmDateTime">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

defineOptions({ name: 'AppDatePicker' })

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  type: {
    type: String,
    default: 'month',
    validator: (value) => ['month', 'datetime'].includes(value),
  },
  ariaLabel: {
    type: String,
    default: '请选择日期',
  },
  placeholder: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:modelValue'])
const root = ref(null)
const popover = ref(null)
const open = ref(false)
const dropUp = ref(false)
const panelShift = ref(0)
const now = new Date()
const panelYear = ref(now.getFullYear())
const panelMonth = ref(now.getMonth())
const pendingDate = ref(null)
const pendingHour = ref('09')
const pendingMinute = ref('00')
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const hours = Array.from({ length: 24 }, (_, index) => pad(index))
const minutes = Array.from({ length: 60 }, (_, index) => pad(index))

const displayValue = computed(() => {
  if (!props.modelValue) {
    return props.placeholder || (props.type === 'month' ? '请选择年月' : '请选择日期时间')
  }
  if (props.type === 'month') {
    const [year, month] = props.modelValue.split('-')
    return `${year}年${Number(month)}月`
  }
  const parsed = parseDateTime(props.modelValue)
  if (!parsed) return props.modelValue
  return `${parsed.year}年${parsed.month}月${parsed.day}日 ${pad(parsed.hour)}:${pad(parsed.minute)}`
})

const panelTitle = computed(() =>
  props.type === 'month' ? `${panelYear.value}年` : `${panelYear.value}年${panelMonth.value + 1}月`,
)

const calendarDays = computed(() => {
  const firstDay = new Date(panelYear.value, panelMonth.value, 1)
  const mondayOffset = (firstDay.getDay() + 6) % 7
  const start = new Date(panelYear.value, panelMonth.value, 1 - mondayOffset)
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + index)
    return {
      key: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
      year: date.getFullYear(),
      month: date.getMonth() + 1,
      day: date.getDate(),
      inPanelMonth: date.getMonth() === panelMonth.value,
      isToday: sameDay(date, now),
      ariaLabel: `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`,
    }
  })
})

watch(
  () => [props.modelValue, props.type],
  () => syncPanel(),
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('pointerdown', handleOutside)
  document.addEventListener('keydown', handleKeydown)
  window.addEventListener('resize', updatePlacement)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleOutside)
  document.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('resize', updatePlacement)
})

function pad(value) {
  return String(value).padStart(2, '0')
}

function parseDateTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
    hour: Number(match[4]),
    minute: Number(match[5]),
  }
}

function syncPanel() {
  if (props.type === 'month') {
    const match = /^(\d{4})-(\d{2})$/.exec(props.modelValue)
    panelYear.value = match ? Number(match[1]) : now.getFullYear()
    return
  }
  const parsed = parseDateTime(props.modelValue)
  const base = parsed || {
    year: now.getFullYear(),
    month: now.getMonth() + 1,
    day: now.getDate(),
    hour: now.getHours(),
    minute: now.getMinutes(),
  }
  panelYear.value = base.year
  panelMonth.value = base.month - 1
  pendingDate.value = { year: base.year, month: base.month, day: base.day }
  pendingHour.value = pad(base.hour)
  pendingMinute.value = pad(base.minute)
}

function toggle() {
  if (open.value) {
    open.value = false
    return
  }
  openPicker()
}

function openPicker() {
  syncPanel()
  dropUp.value = false
  panelShift.value = 0
  open.value = true
  nextTick(updatePlacement)
}

async function updatePlacement() {
  if (!open.value || !root.value || !popover.value) return
  dropUp.value = false
  panelShift.value = 0
  await nextTick()
  const triggerRect = root.value.getBoundingClientRect()
  const panelRect = popover.value.getBoundingClientRect()
  const panelHeight = panelRect.height
  const spaceBelow = window.innerHeight - triggerRect.bottom - 12
  const spaceAbove = triggerRect.top - 12
  if (panelHeight > spaceBelow && spaceAbove >= panelHeight) {
    dropUp.value = true
    return
  }
  const bottomOverflow = panelRect.bottom - (window.innerHeight - 12)
  const topAfterShift = panelRect.top - Math.max(0, bottomOverflow)
  panelShift.value = -Math.max(0, bottomOverflow) + Math.max(0, 12 - topAfterShift)
}

function movePanel(direction) {
  if (props.type === 'month') {
    panelYear.value += direction
    return
  }
  const next = new Date(panelYear.value, panelMonth.value + direction, 1)
  panelYear.value = next.getFullYear()
  panelMonth.value = next.getMonth()
}

function isSelectedMonth(month) {
  return props.modelValue === `${panelYear.value}-${pad(month)}`
}

function isCurrentMonth(month) {
  return panelYear.value === now.getFullYear() && month === now.getMonth() + 1
}

function selectMonth(month) {
  emit('update:modelValue', `${panelYear.value}-${pad(month)}`)
  open.value = false
}

function sameDay(left, right) {
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  )
}

function isSelectedDay(day) {
  const selected = pendingDate.value
  return selected && selected.year === day.year && selected.month === day.month && selected.day === day.day
}

function selectDay(day) {
  pendingDate.value = { year: day.year, month: day.month, day: day.day }
  if (!day.inPanelMonth) {
    panelYear.value = day.year
    panelMonth.value = day.month - 1
  }
}

function selectCurrent() {
  if (props.type === 'month') {
    emit('update:modelValue', `${now.getFullYear()}-${pad(now.getMonth() + 1)}`)
    open.value = false
    return
  }
  pendingDate.value = { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() }
  pendingHour.value = pad(now.getHours())
  pendingMinute.value = pad(now.getMinutes())
  panelYear.value = now.getFullYear()
  panelMonth.value = now.getMonth()
}

function confirmDateTime() {
  if (!pendingDate.value) return
  const { year, month, day } = pendingDate.value
  emit('update:modelValue', `${year}-${pad(month)}-${pad(day)}T${pendingHour.value}:${pendingMinute.value}`)
  open.value = false
}

function clearValue() {
  emit('update:modelValue', '')
  open.value = false
}

function handleOutside(event) {
  if (open.value && root.value && !root.value.contains(event.target)) {
    open.value = false
  }
}

function handleKeydown(event) {
  if (event.key === 'Escape') {
    open.value = false
  }
}
</script>
