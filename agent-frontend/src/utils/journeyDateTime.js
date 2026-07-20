const JOURNEY_DATE_TIME_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$/

export function formatJourneyDateTime(value) {
  if (!value) return ''

  const text = String(value).trim()
  const match = text.match(JOURNEY_DATE_TIME_PATTERN)
  if (match) return `${match[1]} ${match[2]}`

  return text.replace('T', ' ')
}

export function toJourneyDateTimeLocalValue(value) {
  if (!value) return ''

  const text = String(value).trim()
  const match = text.match(JOURNEY_DATE_TIME_PATTERN)
  if (match) return `${match[1]}T${match[2]}`

  return text
}
