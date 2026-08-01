<template>
  <article class="tool-execution-console">
    <header class="tool-execution-toolbar">
      <div class="tool-execution-heading">
        <strong>执行代码</strong>
        <span class="tool-execution-meta">
          <b>{{ detail.language }}</b>
          <i aria-hidden="true"></i>
          {{ executionSizeText(detail.source) }}
        </span>
      </div>
      <div class="tool-execution-actions">
        <button
          v-if="showCodeToggle"
          type="button"
          class="tool-execution-expand"
          :aria-expanded="codeExpanded"
          :aria-controls="codePanelId"
          @click="toggleCode"
        >
          {{ codeExpanded ? '收起代码' : '展开代码' }}
        </button>
        <button
          type="button"
          :class="['tool-execution-copy', { failed: copyState === 'failed' }]"
          :aria-label="copyLabel"
          aria-live="polite"
          @click="handleCopy"
        >
          <span>{{ copyLabel }}</span>
        </button>
      </div>
    </header>

    <div
      :id="codePanelId"
      ref="codeFrame"
      :class="[
        'tool-execution-code-frame',
        'tool-execution-source',
        {
          collapsed: showCodeToggle && !codeExpanded,
          expanded: codeExpanded,
        },
      ]"
    >
      <MarkdownCodeBlockNode
        class="tool-execution-code"
        :node="codeNode"
        :loading="false"
        :stream="false"
        :is-dark="false"
        :show-header="false"
        :show-copy-button="false"
        :show-expand-button="false"
        :show-preview-button="false"
        :show-collapse-button="false"
        :show-font-size-buttons="false"
      />
    </div>

    <section class="tool-execution-output">
      <header class="tool-execution-output-title">
        <div>
          <strong>运行输出</strong>
          <span>仅展示当前选中的输出流</span>
        </div>
        <div class="tool-execution-output-tabs" role="tablist" aria-label="运行输出切换">
          <button
            v-for="product in products"
            :id="outputTabId(product.id)"
            :key="product.id"
            type="button"
            role="tab"
            :class="[
              'tool-execution-output-tab',
              {
                active: activeOutput?.id === product.id,
                error: product.id === 'stderr' && product.content,
              },
            ]"
            :aria-selected="activeOutput?.id === product.id"
            :aria-controls="outputPanelId"
            :data-output-tab="product.id"
            :tabindex="activeOutput?.id === product.id ? 0 : -1"
            @click="selectOutput(product.id)"
            @keydown="handleOutputTabKeydown($event, product.id)"
          >
            <i aria-hidden="true"></i>
            <span>{{ product.label }}</span>
            <small>{{ executionSizeText(product) }}</small>
          </button>
        </div>
      </header>
      <div
        v-if="activeOutput"
        :id="outputPanelId"
        :class="[
          'tool-execution-output-body',
          {
            empty: !activeOutput.content,
            error: activeOutput.id === 'stderr' && activeOutput.content,
          },
        ]"
        role="tabpanel"
        :aria-labelledby="outputTabId(activeOutput.id)"
        :data-product="activeOutput.id"
      >
        <pre>{{ activeOutput.content || activeOutput.emptyText }}</pre>
      </div>
    </section>
  </article>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { MarkdownCodeBlockNode } from 'markstream-vue'
import { copyText } from '../utils/clipboard'

const props = defineProps({
  detail: { type: Object, required: true },
  executionKey: { type: String, required: true },
})

const codeFrame = ref(null)
const codeExpanded = ref(false)
const copyState = ref('idle')
const outputManuallySelected = ref(false)
let copyFeedbackTimer = null

const products = computed(() => (Array.isArray(props.detail.products) ? props.detail.products : []))
const codeText = computed(() => String(props.detail.source?.content || '未返回执行代码'))
const codeNode = computed(() => ({
  type: 'code_block',
  language: String(props.detail.language || 'text')
    .trim()
    .toLowerCase(),
  code: codeText.value,
  raw: codeText.value,
}))
const showCodeToggle = computed(() => {
  const chars = Number(props.detail.source?.chars || codeText.value.length)
  const lines = codeText.value.split(/\r?\n/).length
  return chars > 1200 || lines > 14
})
const domKey = computed(() => props.executionKey.replace(/[^a-zA-Z0-9_-]/g, '-'))
const codePanelId = computed(() => `execution-code-${domKey.value}`)
const outputPanelId = computed(() => `execution-output-${domKey.value}`)

function outputTabId(productId) {
  return `execution-output-${domKey.value}-${productId}-tab`
}

function defaultOutputId() {
  const stderr = products.value.find((product) => product.id === 'stderr' && String(product.content || '').trim())
  if (stderr) return stderr.id
  return products.value.find((product) => product.id === 'stdout')?.id || products.value[0]?.id || ''
}

const activeOutputId = ref(defaultOutputId())
const activeOutput = computed(
  () => products.value.find((product) => product.id === activeOutputId.value) || products.value[0] || null,
)

watch(
  products,
  () => {
    if (!outputManuallySelected.value || !products.value.some((product) => product.id === activeOutputId.value)) {
      activeOutputId.value = defaultOutputId()
    }
  },
  { deep: true },
)

const copyLabel = computed(() => {
  if (copyState.value === 'copied') return '已复制'
  if (copyState.value === 'failed') return '复制失败'
  return '复制代码'
})

function executionSizeText(value) {
  const chars = Number(value?.chars || 0)
  return `${Number.isFinite(chars) ? chars : 0} 字符${value?.truncated ? ' · 已截断' : ''}`
}

function selectOutput(productId) {
  if (!products.value.some((product) => product.id === productId)) return
  activeOutputId.value = productId
  outputManuallySelected.value = true
}

function handleOutputTabKeydown(event, productId) {
  const productIds = products.value.map((product) => product.id)
  const currentIndex = productIds.indexOf(productId)
  if (currentIndex < 0 || productIds.length < 2) return

  let nextIndex = currentIndex
  if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % productIds.length
  else if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + productIds.length) % productIds.length
  else if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = productIds.length - 1
  else return

  event.preventDefault()
  const nextProductId = productIds[nextIndex]
  selectOutput(nextProductId)
  nextTick(() => document.getElementById(outputTabId(nextProductId))?.focus())
}

function toggleCode() {
  codeExpanded.value = !codeExpanded.value
  if (codeExpanded.value) return
  nextTick(() => {
    const scrollContainer = codeFrame.value?.querySelector('.code-block-content')
    if (scrollContainer) scrollContainer.scrollTop = 0
  })
}

async function handleCopy() {
  const success = await copyText(codeText.value)
  copyState.value = success ? 'copied' : 'failed'
  window.clearTimeout(copyFeedbackTimer)
  copyFeedbackTimer = window.setTimeout(() => {
    copyState.value = 'idle'
  }, 1800)
}

onBeforeUnmount(() => window.clearTimeout(copyFeedbackTimer))
</script>

<style scoped>
.tool-execution-console {
  overflow: hidden;
  border: 1px solid #d9e2ef;
  border-radius: 9px;
  background: #fff;
}

.tool-execution-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 40px;
  padding: 6px 8px 6px 12px;
  border-bottom: 1px solid #dce4ef;
  background: #f8fafc;
}

.tool-execution-heading,
.tool-execution-actions,
.tool-execution-output-title,
.tool-execution-output-title > div,
.tool-execution-output-tabs,
.tool-execution-output-tab {
  display: flex;
  align-items: center;
}

.tool-execution-heading {
  min-width: 0;
  gap: 8px;
}

.tool-execution-heading strong {
  color: #26344d;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

.tool-execution-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #7b8ba1;
  font-size: 9.5px;
  line-height: 1;
  white-space: nowrap;
}

.tool-execution-meta b {
  color: inherit;
  font-weight: 600;
}

.tool-execution-meta i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #b4c0cf;
}

.tool-execution-actions {
  flex: 0 0 auto;
  gap: 2px;
}

.tool-execution-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 27px;
  border: 0;
  border-radius: 5px;
  padding: 5px 7px;
  background: transparent;
  color: #66758a;
  cursor: pointer;
  font: inherit;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  transition:
    background-color 0.15s ease,
    color 0.15s ease;
}

.tool-execution-actions button:hover {
  background: #e9eef5;
  color: #26344d;
}

.tool-execution-actions button:focus-visible,
.tool-execution-output-tab:focus-visible {
  outline: 2px solid #84adff;
  outline-offset: 2px;
}

.tool-execution-copy.failed {
  color: #b42318;
}

.tool-execution-code-frame {
  position: relative;
  overflow: hidden;
  background: #fbfcfe;
}

.tool-execution-code-frame.collapsed::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 46px;
  background: linear-gradient(to bottom, rgba(251, 252, 254, 0), rgba(251, 252, 254, 0.98));
  content: '';
  pointer-events: none;
}

.tool-execution-code.code-block-container {
  border: 0;
  border-radius: 0;
  background: #fbfcfe;
}

.tool-execution-code-frame :deep(.code-block-content) {
  max-height: 220px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-color: #b7c3d2 transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.tool-execution-code-frame.expanded :deep(.code-block-content) {
  max-height: min(68vh, 720px);
}

.tool-execution-code :deep(.code-block-render),
.tool-execution-code :deep(.code-fallback-plain) {
  min-width: max-content;
}

.tool-execution-code :deep(.shiki) {
  margin: 0;
  padding: 12px 0 14px;
  font-size: 11.5px;
  line-height: 1.72;
  tab-size: 2;
}

.tool-execution-code :deep(.shiki code) {
  display: block;
  min-width: max-content;
  counter-reset: execution-line;
}

.tool-execution-code :deep(.shiki .line) {
  display: inline-block;
  min-width: 100%;
  padding-right: 18px;
}

.tool-execution-code :deep(.shiki .line::before) {
  display: inline-block;
  position: sticky;
  left: 0;
  width: 38px;
  margin-right: 13px;
  padding-right: 10px;
  border-right: 1px solid #e2e8f0;
  background: #fbfcfe;
  color: #98a6b9;
  content: counter(execution-line);
  counter-increment: execution-line;
  text-align: right;
  user-select: none;
}

.tool-execution-output {
  border-top: 1px solid #e4eaf2;
  background: #fff;
}

.tool-execution-output-title {
  justify-content: space-between;
  gap: 14px;
  min-height: 48px;
  padding: 7px 10px 7px 12px;
  background: #f8fafc;
}

.tool-execution-output-title > div:first-child {
  min-width: 118px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.tool-execution-output-title strong {
  color: #26344d;
  font-size: 11px;
  font-weight: 750;
}

.tool-execution-output-title > div:first-child span {
  color: #8a99ae;
  font-size: 9px;
}

.tool-execution-output-tabs {
  justify-content: flex-end;
  gap: 4px;
  padding: 3px;
  border: 1px solid #e1e7f0;
  border-radius: 9px;
  background: #eef2f7;
}

.tool-execution-output-tab {
  gap: 5px;
  min-height: 27px;
  border: 0;
  border-radius: 6px;
  padding: 4px 8px;
  background: transparent;
  color: #66758a;
  cursor: pointer;
  font: inherit;
  font-size: 10px;
  line-height: 1;
}

.tool-execution-output-tab > i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #98a2b3;
}

.tool-execution-output-tab.error > i {
  background: #f04438;
  box-shadow: 0 0 0 3px rgba(240, 68, 56, 0.1);
}

.tool-execution-output-tab small {
  color: #98a2b3;
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.tool-execution-output-tab.active {
  background: #fff;
  color: #26344d;
  font-weight: 700;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.1);
}

.tool-execution-output-tab.active > i {
  background: #3157f5;
}

.tool-execution-output-tab.active.error > i {
  background: #f04438;
}

.tool-execution-output-body {
  min-height: 86px;
  border-top: 1px solid #edf1f6;
  background: #fbfcfe;
}

.tool-execution-output-body.error {
  background: #fffafa;
}

.tool-execution-output-body pre {
  max-height: 220px;
  overflow: auto;
  overflow-wrap: anywhere;
  margin: 0;
  padding: 12px 14px;
  color: #344054;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  line-height: 1.65;
  tab-size: 2;
  white-space: pre-wrap;
  word-break: break-word;
}

.tool-execution-output-body.error pre {
  color: #8b2c28;
}

.tool-execution-output-body.empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.tool-execution-output-body.empty pre {
  color: #98a2b3;
  font-family: inherit;
  font-style: italic;
}

@media (max-width: 760px) {
  .tool-execution-toolbar,
  .tool-execution-output-title {
    align-items: flex-start;
    flex-direction: column;
  }

  .tool-execution-actions,
  .tool-execution-output-tabs {
    width: 100%;
    justify-content: flex-start;
  }

  .tool-execution-output-tabs {
    overflow-x: auto;
  }
}
</style>
