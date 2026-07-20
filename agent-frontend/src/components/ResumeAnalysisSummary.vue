<template>
  <div class="resume-analysis-summary" role="list" aria-label="简历分析概览">
    <article
      v-for="metric in metrics"
      :key="metric.key"
      :class="['analysis-summary-metric', `is-${metric.tone}`]"
      role="listitem"
    >
      <span class="analysis-summary-icon">
        <svg v-if="metric.key === 'score'" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="8" />
          <path d="M12 8v4l2.5 2.5" />
          <path d="M8.5 3.5 7 2M15.5 3.5 17 2" />
        </svg>
        <svg v-else-if="metric.key === 'advantage'" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 3.2 14.5 8l5.3.8-3.8 3.8.9 5.4-4.9-2.5L7.1 18l.9-5.4-3.8-3.8L9.5 8 12 3.2Z" />
        </svg>
        <svg v-else-if="metric.key === 'disadvantage'" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 3 21 19H3L12 3Z" />
          <path d="M12 9v4M12 16.5v.5" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="11" cy="11" r="6.5" />
          <path d="m16 16 4 4M11 8v6M8 11h6" />
        </svg>
      </span>
      <span class="analysis-summary-copy">
        <small>{{ metric.label }}</small>
        <strong>{{ metric.value }}</strong>
      </span>
    </article>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  analysis: { type: Object, default: () => ({}) },
})

const count = (value) => (Array.isArray(value) ? value.length : value ? 1 : 0)
const metrics = computed(() => [
  { key: 'score', label: '综合评分', value: props.analysis.overall_score ?? '-', tone: 'score' },
  { key: 'advantage', label: '优势点', value: count(props.analysis.advantages), tone: 'advantage' },
  { key: 'disadvantage', label: '劣势点', value: count(props.analysis.disadvantages), tone: 'disadvantage' },
  { key: 'deepDive', label: '深挖点', value: count(props.analysis.interview_deep_dive_points), tone: 'deep-dive' },
])
</script>
