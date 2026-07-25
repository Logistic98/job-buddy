import { enableKatex, enableMermaid } from 'markstream-vue'

const MERMAID_CONFIG = Object.freeze({
  startOnLoad: false,
  securityLevel: 'strict',
  flowchart: { htmlLabels: false },
  suppressErrorRendering: true,
})

let mermaidLoaderPromise = null
let katexLoaderPromise = null

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
  if (!katexLoaderPromise) katexLoaderPromise = import('katex').then((module) => module.default || module)
  return katexLoaderPromise
}

enableMermaid(loadMermaid)
enableKatex(loadKatex)

export function markdownMermaidProps(overrides = {}) {
  return {
    isStrict: true,
    ...overrides,
  }
}

export function normalizeMarkdownFeatures(content) {
  const lines = String(content ?? '').split('\n')
  let fence = null
  return lines
    .map((line) => {
      const fenceMatch = line.match(/^[ \t]{0,3}(`{3,}|~{3,})/)
      if (fenceMatch) {
        const marker = fenceMatch[1]
        if (!fence) fence = { character: marker[0], length: marker.length }
        else if (marker[0] === fence.character && marker.length >= fence.length) fence = null
        return line
      }
      if (fence) return line
      return line
        .split(/(`+[^`]*`+)/g)
        .map((part, index) =>
          index % 2 === 0
            ? part.replace(/(^|[^\\$])\$(?!\$)([^$\n]+?)\$(?!\$)/g, (_, prefix, expression) => {
                return `${prefix}\\(${expression}\\)`
              })
            : part,
        )
        .join('')
    })
    .join('\n')
}

export { MERMAID_CONFIG }
