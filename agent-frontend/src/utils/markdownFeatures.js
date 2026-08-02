import katex from 'katex'
import { enableKatex, enableMermaid, setCustomComponents } from 'markstream-vue'
import HighlightedCodeBlock from '../components/HighlightedCodeBlock.vue'
import MarkdownMathNode from '../components/MarkdownMathNode.vue'

const MERMAID_CONFIG = Object.freeze({
  startOnLoad: false,
  securityLevel: 'strict',
  flowchart: { htmlLabels: false },
  suppressErrorRendering: true,
})

let mermaidLoaderPromise = null
let assistantMarkdownFeaturesRegistered = false

export function loadMermaid() {
  if (!mermaidLoaderPromise) {
    mermaidLoaderPromise = import('mermaid').then((module) => {
      const instance = module.default || module
      instance.initialize(MERMAID_CONFIG)
      return instance
    })
  }
  return mermaidLoaderPromise
}

export function loadKatex() {
  return Promise.resolve(katex)
}

enableMermaid(loadMermaid)
enableKatex(loadKatex)

export function registerAssistantMarkdownFeatures() {
  if (assistantMarkdownFeaturesRegistered) return
  registerMarkdownComponents('job-chat')
  assistantMarkdownFeaturesRegistered = true
}

export function registerMarkdownComponents(customId) {
  if (!String(customId || '').trim()) return
  const components = {
    math_block: MarkdownMathNode,
    math_inline: MarkdownMathNode,
  }
  if (customId === 'job-chat') components.code_block = HighlightedCodeBlock
  setCustomComponents(customId, components)
}

export function markdownMermaidProps(overrides = {}) {
  return {
    ...overrides,
    isStrict: true,
  }
}

export { MERMAID_CONFIG }
