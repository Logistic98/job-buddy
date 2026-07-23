<!-- 高亮内容由 codeHighlight.js 完整转义后生成，仅插入受控 token span。 -->
<!-- eslint-disable vue/no-v-html -->
<template>
  <div class="code-highlight-editor" :data-language="normalizedLanguage">
    <pre
      ref="highlightRef"
      class="code-highlight-layer"
      aria-hidden="true"
      :style="{ transform: `translate(${-scrollLeft}px, ${-scrollTop}px)` }"
    ><code v-html="highlightedCode" /></pre>
    <textarea
      :id="id"
      ref="textareaRef"
      :class="textareaClass"
      :value="modelValue"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      :aria-required="required ? 'true' : undefined"
      :spellcheck="false"
      wrap="off"
      @input="handleInput"
      @scroll="syncScroll"
    />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { highlightCode, normalizeHighlightLanguage } from '../../utils/codeHighlight'

const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: '' },
  id: { type: String, default: undefined },
  placeholder: { type: String, default: '' },
  ariaLabel: { type: String, default: '代码编辑器' },
  required: { type: Boolean, default: false },
  textareaClass: { type: [String, Array, Object], default: '' },
})

const emit = defineEmits(['update:modelValue'])
const textareaRef = ref(null)
const highlightRef = ref(null)
const scrollTop = ref(0)
const scrollLeft = ref(0)
const normalizedLanguage = computed(() => normalizeHighlightLanguage(props.language) || 'plain')
const highlightedCode = computed(() => `${highlightCode(props.modelValue, props.language)}\n`)

function handleInput(event) {
  emit('update:modelValue', event.target.value)
}

function syncScroll(event) {
  scrollTop.value = event.target.scrollTop
  scrollLeft.value = event.target.scrollLeft
}

defineExpose({ textareaRef, highlightRef })
</script>
