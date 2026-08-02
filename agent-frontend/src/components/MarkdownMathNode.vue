<template>
  <!-- KaTeX 在 trust=false 下只生成受限的静态 HTML/MathML，不执行公式中的 HTML 或脚本。 -->
  <!-- eslint-disable vue/no-v-html -->
  <div v-if="displayMode" class="assistant-math-block" v-html="renderedMath" />
  <span v-else class="assistant-math-inline" v-html="renderedMath" />
  <!-- eslint-enable vue/no-v-html -->
</template>

<script setup>
import { computed } from 'vue'
import katex from 'katex'

const props = defineProps({
  node: {
    type: Object,
    required: true,
  },
})

const displayMode = computed(() => props.node?.type === 'math_block')

const renderedMath = computed(() =>
  katex.renderToString(String(props.node?.content || '').trim(), {
    displayMode: displayMode.value,
    throwOnError: false,
    strict: 'warn',
    trust: false,
    output: 'htmlAndMathml',
  }),
)
</script>
