<template>
  <div ref="containerRef" class="practice-markdown-wrap" @click="handleCodeCopy">
    <MarkdownRender
      v-if="normalizedContent"
      :key="`${customId}-${normalizedContent.length}`"
      :class="['practice-markdown', { compact }]"
      :custom-id="customId"
      :content="normalizedContent"
      :final="true"
      html-policy="escape"
      :max-live-nodes="0"
      :fade="false"
      :typewriter="false"
      :smooth-streaming="false"
    />
    <p v-else-if="emptyText" class="practice-markdown-empty">{{ emptyText }}</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownRender from 'markstream-vue'
import { copyText } from '../../utils/clipboard'

const props = defineProps({
  content: { type: [String, Number], default: '' },
  customId: { type: String, required: true },
  compact: { type: Boolean, default: false },
  emptyText: { type: String, default: '' },
})

const normalizedContent = computed(() => String(props.content ?? '').trim())
const containerRef = ref(null)
let observer = null
let feedbackTimer = null

function decorateCodeBlocks() {
  const container = containerRef.value
  if (!container) return
  container.querySelectorAll('pre').forEach((pre) => {
    if (pre.querySelector(':scope > .practice-code-copy')) return
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'practice-code-copy'
    button.setAttribute('aria-label', '复制代码')
    button.textContent = '复制代码'
    pre.appendChild(button)
  })
}

async function handleCodeCopy(event) {
  const button = event.target.closest?.('.practice-code-copy')
  if (!button || !containerRef.value?.contains(button)) return
  const pre = button.closest('pre')
  const code = pre?.querySelector('code')
  const success = await copyText(code?.textContent || '')
  button.textContent = success ? '已复制' : '复制失败'
  button.classList.toggle('failed', !success)
  window.clearTimeout(feedbackTimer)
  feedbackTimer = window.setTimeout(() => {
    button.textContent = '复制代码'
    button.classList.remove('failed')
  }, 1800)
}

watch(normalizedContent, () => nextTick(decorateCodeBlocks))

onMounted(() => {
  nextTick(decorateCodeBlocks)
  observer = new MutationObserver(decorateCodeBlocks)
  if (containerRef.value) observer.observe(containerRef.value, { childList: true, subtree: true })
})

onBeforeUnmount(() => {
  observer?.disconnect()
  window.clearTimeout(feedbackTimer)
})
</script>
