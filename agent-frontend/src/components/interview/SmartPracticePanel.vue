<template>
  <section class="smart-practice-panel glass-card" aria-label="智能组卷要求">
    <form class="smart-practice-form" @submit.prevent="compose">
      <label for="smart-practice-requirements">组卷要求</label>
      <textarea
        id="smart-practice-requirements"
        v-model="requirements"
        minlength="10"
        maxlength="1000"
        rows="7"
        :disabled="loading"
        aria-describedby="smart-practice-hint"
        placeholder="例如：选择 8 道生产级 Agent 工程面试题，重点覆盖 Agent Loop、工具治理、权限边界和 Checkpoint，以中高难度为主，45 分钟考试模式。"
      />
      <div class="smart-practice-input-meta">
        <small id="smart-practice-hint">至少 10 个字符，最多 1000 个字符。未说明题量和时长时由 AI 根据题型估算。</small>
        <span>{{ requirements.length }} / 1000</span>
      </div>

      <div class="smart-practice-examples" aria-label="组卷要求示例">
        <span>试试这些要求</span>
        <button
          v-for="example in examples"
          :key="example"
          type="button"
          class="smart-practice-example"
          :disabled="loading"
          @click="requirements = example"
        >
          {{ example }}
        </button>
      </div>

      <div class="smart-practice-boundary">
        <div>
          <strong>只从现有题库选题</strong>
          <span>不会自动生成或写入新题</span>
        </div>
        <div>
          <strong>自动理解组合条件</strong>
          <span>识别知识范围、难度、题型与题量</span>
        </div>
        <div>
          <strong>组卷后直接练习</strong>
          <span>自动设置标题、时长和答案模式</span>
        </div>
      </div>

      <p v-if="error" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
        {{ error }}
      </p>
      <div class="smart-practice-actions">
        <p v-if="loading" role="status" aria-live="polite">AI 正在理解要求并匹配题目，请稍候。</p>
        <button type="submit" class="primary-btn" :disabled="loading || requirements.trim().length < 10">
          {{ loading ? '智能组卷中…' : '智能组卷并开始练习' }}
        </button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { createSmartExam } from '../../api/interview'

const emit = defineEmits(['created'])
const examples = [
  '选择 8 道生产级 Agent 工程中高难度题，重点覆盖 Agent Loop、Planner、工具治理和权限边界，45 分钟考试模式。',
  '给我一套 5 道 Agent 可靠性简答题，覆盖 Checkpoint、上下文压缩、Trace、评测与故障降级，学习模式。',
  '选择 3 道数组与哈希表算法题，从简单到困难，60 分钟完成。',
]
const requirements = ref('')
const loading = ref(false)
const error = ref('')

async function compose() {
  const normalized = requirements.value.trim()
  if (loading.value || normalized.length < 10) return
  loading.value = true
  error.value = ''
  try {
    const exam = await createSmartExam({ requirements: normalized })
    emit('created', exam)
  } catch (err) {
    error.value = err?.message || '智能组卷失败，请调整要求后重试'
  } finally {
    loading.value = false
  }
}
</script>
