<template>
  <div v-if="isPlainText" class="assistant-plain-text">{{ displayText }}</div>
  <MarkdownCodeBlockNode v-else v-bind="$attrs" :node="node" :show-copy-button="false">
    <template #header-right>
      <button
        type="button"
        :class="['assistant-code-copy', { failed: copyState === 'failed' }]"
        :aria-label="copyLabel"
        aria-live="polite"
        @click.stop="handleCopy"
      >
        {{ copyLabel }}
      </button>
    </template>
  </MarkdownCodeBlockNode>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { MarkdownCodeBlockNode } from 'markstream-vue'
import { copyText } from '../utils/clipboard'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  node: { type: Object, required: true },
})

const plainTextLanguages = new Set(['', 'plain', 'plaintext', 'text', 'txt'])
const copyState = ref('idle')
let feedbackTimer = null

const displayText = computed(() => String(props.node.code ?? '').replace(/\n$/, ''))
const isPlainText = computed(() =>
  plainTextLanguages.has(
    String(props.node.language ?? '')
      .trim()
      .toLowerCase(),
  ),
)

const copyLabel = computed(() => {
  if (copyState.value === 'copied') return '已复制'
  if (copyState.value === 'failed') return '复制失败'
  return '复制代码'
})

async function handleCopy() {
  const success = await copyText(displayText.value)
  copyState.value = success ? 'copied' : 'failed'
  window.clearTimeout(feedbackTimer)
  feedbackTimer = window.setTimeout(() => {
    copyState.value = 'idle'
  }, 1800)
}

onBeforeUnmount(() => window.clearTimeout(feedbackTimer))
</script>

<style scoped>
.assistant-plain-text {
  margin: 0 0 12px;
  color: inherit;
  font: inherit;
  line-height: inherit;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.assistant-plain-text:last-child {
  margin-bottom: 0;
}

.assistant-code-copy {
  min-height: 1.75rem;
  padding: 0.25rem 0.6rem;
  border: 1px solid var(--code-border);
  border-radius: 0.4rem;
  background: transparent;
  color: var(--code-action-fg);
  font: inherit;
  font-size: 0.75rem;
  line-height: 1;
  cursor: pointer;
  transition:
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.assistant-code-copy:hover {
  background: var(--code-action-hover-bg);
  color: var(--code-action-hover-fg);
}

.assistant-code-copy.failed {
  color: var(--danger, #b42318);
  border-color: currentColor;
}

.assistant-code-copy:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
</style>
