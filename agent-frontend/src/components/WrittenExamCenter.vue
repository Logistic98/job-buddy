<template>
  <section class="system-page written-center-page">
    <header class="page-header written-center-header">
      <div>
        <p class="eyebrow">Practice Center</p>
        <h1>练习中心</h1>
        <p>先管理和选择题目，再进入练习台完成作答与复盘。</p>
      </div>
      <nav class="written-center-tabs" aria-label="练习中心功能切换" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          role="tab"
          :aria-selected="activeTab === tab.key"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          <span class="tab-icon" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.9"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path v-for="path in tab.iconPaths" :key="path" :d="path" />
            </svg>
          </span>
          <span class="tab-text">
            <strong>{{ tab.label }}</strong>
            <small>{{ tab.description }}</small>
          </span>
        </button>
      </nav>
    </header>

    <KeepAlive>
      <InterviewBank
        v-if="activeTab === 'practice'"
        key="written-practice"
        mode="exam"
        :initial-exam-id="activeExamId"
        embedded
        @back-to-bank="handleBackToBank"
      />
      <InterviewBank v-else key="written-bank" mode="bank" embedded @practice-created="handlePracticeCreated" />
    </KeepAlive>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import InterviewBank from './InterviewBank.vue'

const tabs = [
  {
    key: 'bank',
    label: '题库',
    description: '管理与选择题目',
    iconPaths: [
      'M5 4.5A2.5 2.5 0 0 1 7.5 2H19v18H7.5A2.5 2.5 0 0 1 5 17.5z',
      'M5 17.5A2.5 2.5 0 0 0 7.5 15H19',
      'M9 6h6',
      'M9 10h5',
    ],
  },
  {
    key: 'practice',
    label: '练习台',
    description: '作答、继续与复盘',
    iconPaths: ['M8 3h8l2 2v16H6V5z', 'M9 9h6', 'M9 13h6', 'M9 17h3'],
  },
]
const activeTab = ref('bank')
const activeExamId = ref('')

function handlePracticeCreated(exam) {
  activeExamId.value = exam?.examId || ''
  activeTab.value = 'practice'
}

function handleBackToBank() {
  activeExamId.value = ''
  activeTab.value = 'bank'
}
</script>
