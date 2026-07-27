<template>
  <MarkdownRender
    v-bind="$attrs"
    :content="normalizedContent"
    :custom-id="customId"
    :final="final"
    html-policy="escape"
    :mermaid-props="resolvedMermaidProps"
    @copy="handleCopy"
  />
</template>

<script setup>
import { computed } from 'vue'
import MarkdownRender from 'markstream-vue'
import { copyText } from '../utils/clipboard'
import { markdownMermaidProps, normalizeMarkdownFeatures } from '../utils/markdownFeatures'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  content: { type: [String, Number], default: '' },
  customId: { type: String, required: true },
  final: { type: Boolean, default: true },
  mermaidProps: { type: Object, default: () => ({}) },
})

const normalizedContent = computed(() => normalizeMarkdownFeatures(props.content))
const resolvedMermaidProps = computed(() => markdownMermaidProps(props.mermaidProps))

async function handleCopy(text) {
  if (typeof globalThis.navigator?.clipboard?.writeText === 'function') return
  await copyText(text)
}
</script>
