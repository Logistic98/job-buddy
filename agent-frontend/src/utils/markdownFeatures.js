import katex from 'katex'
import { enableKatex, enableMermaid, setCustomComponents } from 'markstream-vue'
import HighlightedCodeBlock from '../components/HighlightedCodeBlock.vue'

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
  setCustomComponents('job-chat', { code_block: HighlightedCodeBlock })
  assistantMarkdownFeaturesRegistered = true
}

export function markdownMermaidProps(overrides = {}) {
  return {
    ...overrides,
    isStrict: true,
  }
}

export { MERMAID_CONFIG }
