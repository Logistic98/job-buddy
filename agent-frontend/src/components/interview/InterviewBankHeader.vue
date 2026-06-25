<template>
  <header v-if="!embedded" class="page-header">
    <div>
      <p class="eyebrow">{{ pageEyebrow }}</p>
      <h1>{{ pageTitle }}</h1>
      <p>{{ pageDescription }}</p>
    </div>
    <div class="history-header-actions">
      <template v-if="activeMode === 'bank'">
        <button class="primary-btn" @click="$emit('create')">新增题目</button>
        <button class="secondary-btn" :disabled="loading" @click="$emit('refresh-bank')">刷新</button>
      </template>
      <template v-else>
        <button class="secondary-btn" @click="$emit('back-to-bank')">返回题库</button>
        <button class="primary-btn" @click="$emit('practice')">随机组卷</button>
        <button class="secondary-btn" :disabled="recordsLoading" @click="$emit('refresh-exams')">刷新记录</button>
      </template>
    </div>
  </header>
  <div v-else class="embedded-actions">
    <nav v-if="activeMode === 'bank'" class="bank-type-tabs embedded-bank-tabs" aria-label="题库切换">
      <button
        v-for="item in bankTypeOptions"
        :key="item.value"
        :class="{ active: activeBankType === item.value }"
        @click="$emit('switch-bank', item.value)"
      >
        {{ item.label }}
      </button>
    </nav>
    <div class="history-header-actions">
      <template v-if="activeMode === 'bank'">
        <button class="primary-btn" @click="$emit('create')">新增题目</button>
        <button class="secondary-btn" :disabled="loading" @click="$emit('refresh-bank')">刷新</button>
      </template>
      <template v-else>
        <button class="secondary-btn" @click="$emit('back-to-bank')">返回题库</button>
        <button class="primary-btn" @click="$emit('practice')">随机组卷</button>
        <button class="secondary-btn" :disabled="recordsLoading" @click="$emit('refresh-exams')">刷新记录</button>
      </template>
    </div>
  </div>
</template>

<script setup>
defineProps({
  embedded: { type: Boolean, default: false },
  activeMode: { type: String, required: true },
  pageEyebrow: { type: String, required: true },
  pageTitle: { type: String, required: true },
  pageDescription: { type: String, required: true },
  loading: { type: Boolean, default: false },
  recordsLoading: { type: Boolean, default: false },
  bankTypeOptions: { type: Array, default: () => [] },
  activeBankType: { type: String, default: '' },
})

defineEmits(['create', 'refresh-bank', 'back-to-bank', 'practice', 'refresh-exams', 'switch-bank'])
</script>
