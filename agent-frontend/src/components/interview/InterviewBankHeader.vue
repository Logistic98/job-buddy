<template>
  <header v-if="!embedded" class="page-header">
    <div>
      <p class="eyebrow">{{ pageEyebrow }}</p>
      <h1>{{ pageTitle }}</h1>
      <p>{{ pageDescription }}</p>
    </div>
    <div v-if="showActions" class="history-header-actions">
      <template v-if="activeMode === 'bank'">
        <button class="primary-btn" @click="$emit('create')">新增题目</button>
      </template>
      <template v-else>
        <button class="secondary-btn" @click="$emit('back-to-bank')">返回题库</button>
        <button class="secondary-btn" @click="$emit('show-records')">练习记录</button>
        <button class="primary-btn" @click="$emit('practice')">随机组卷</button>
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
    <div v-if="showActions" class="history-header-actions">
      <template v-if="activeMode === 'bank'">
        <button class="primary-btn" @click="$emit('create')">新增题目</button>
      </template>
      <template v-else>
        <button class="secondary-btn" @click="$emit('back-to-bank')">返回题库</button>
        <button class="secondary-btn" @click="$emit('show-records')">练习记录</button>
        <button class="primary-btn" @click="$emit('practice')">随机组卷</button>
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
  bankTypeOptions: { type: Array, default: () => [] },
  activeBankType: { type: String, default: '' },
  showActions: { type: Boolean, default: true },
})

defineEmits(['create', 'back-to-bank', 'practice', 'show-records', 'switch-bank'])
</script>
