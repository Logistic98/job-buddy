const AUTOCOMPLETE_ELIGIBLE_INPUT_TYPES = new Set(['', 'text', 'search', 'email', 'tel', 'url'])
const AUTOFILL_SUPPRESSION_VALUE = 'new-password'

function isAutocompleteEligible(element) {
  if (element instanceof HTMLTextAreaElement) return true
  if (!(element instanceof HTMLInputElement)) return false
  return AUTOCOMPLETE_ELIGIBLE_INPUT_TYPES.has(element.getAttribute('type')?.toLowerCase() || '')
}

function suppressElementAutocomplete(element) {
  if (!isAutocompleteEligible(element)) return
  element.setAttribute('autocomplete', AUTOFILL_SUPPRESSION_VALUE)
}

function suppressAutocompleteWithin(node) {
  if (!(node instanceof Element) && !(node instanceof Document)) return
  if (node instanceof Element) suppressElementAutocomplete(node)
  node.querySelectorAll('input, textarea').forEach(suppressElementAutocomplete)
}

export function installBrowserAutocompleteSuppression(root = document) {
  if (!(root instanceof Element) && !(root instanceof Document)) return () => {}
  suppressAutocompleteWithin(root)

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      mutation.addedNodes.forEach(suppressAutocompleteWithin)
    })
  })
  observer.observe(root, { childList: true, subtree: true })

  return () => observer.disconnect()
}
