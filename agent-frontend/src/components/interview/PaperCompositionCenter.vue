<template>
  <div :class="modal ? 'modal-mask paper-composition-mask' : 'paper-composition-host'" @click.self="handleBackdrop">
    <section
      :class="['paper-composition-center', { 'modal-card paper-composition-dialog': modal }]"
      :role="modal ? 'dialog' : undefined"
      :aria-modal="modal ? 'true' : undefined"
      :aria-labelledby="modal ? 'paper-composition-title' : undefined"
    >
      <button v-if="modal" type="button" class="close" aria-label="关闭组卷弹窗" @click="$emit('close')">×</button>
      <header class="paper-composition-header glass-card">
        <div>
          <p class="eyebrow">Paper Composition</p>
          <h2 id="paper-composition-title">选择组卷方式</h2>
          <p>让 AI 理解自然语言要求，或按题库、分类、难度和题型配置确定规则。</p>
        </div>
        <nav class="paper-composition-tabs" aria-label="组卷方式切换" role="tablist">
          <button
            v-for="mode in modes"
            :key="mode.key"
            role="tab"
            :aria-selected="activeMode === mode.key"
            :class="{ active: activeMode === mode.key }"
            @click="activeMode = mode.key"
          >
            <strong>{{ mode.label }}</strong>
            <small>{{ mode.description }}</small>
          </button>
        </nav>
      </header>

      <KeepAlive>
        <SmartPracticePanel v-if="activeMode === 'smart'" key="smart-composition" @created="$emit('created', $event)" />
        <PracticeConfigModal
          v-else
          key="rule-composition"
          embedded
          :bank-type-options="bankTypeOptions"
          :categories="categories"
          :difficulties="difficulties"
          :question-types="questionTypes"
          @created="$emit('created', $event)"
        />
      </KeepAlive>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getQuestionMeta } from '../../api/interview'
import PracticeConfigModal from './PracticeConfigModal.vue'
import SmartPracticePanel from './SmartPracticePanel.vue'

const props = defineProps({
  initialMode: {
    type: String,
    default: 'smart',
    validator: (value) => ['smart', 'rule'].includes(value),
  },
  modal: {
    type: Boolean,
    default: false,
  },
})
const emit = defineEmits(['created', 'close'])

const modes = [
  {
    key: 'smart',
    label: '智能组卷',
    description: '描述要求，AI 自动选题',
  },
  {
    key: 'rule',
    label: '规则组卷',
    description: '按明确条件组合抽题',
  },
]
const activeMode = ref(props.initialMode)
const bankTypeOptions = ref([])
const categories = ref([])
const difficulties = ref([])
const questionTypes = ref([])

watch(
  () => props.initialMode,
  (mode) => {
    activeMode.value = mode
  },
)

function handleBackdrop() {
  if (props.modal) emit('close')
}

function handleKeydown(event) {
  if (props.modal && event.key === 'Escape') emit('close')
}

onMounted(async () => {
  document.addEventListener('keydown', handleKeydown)
  try {
    const meta = await getQuestionMeta()
    bankTypeOptions.value = Array.isArray(meta?.bankTypeOptions) ? meta.bankTypeOptions : []
    categories.value = Array.isArray(meta?.categories) ? meta.categories : []
    difficulties.value = Array.isArray(meta?.difficulties) ? meta.difficulties : []
    questionTypes.value = Array.isArray(meta?.questionTypes) ? meta.questionTypes : []
  } catch {
    bankTypeOptions.value = []
    categories.value = []
    difficulties.value = []
    questionTypes.value = []
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>
