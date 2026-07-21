import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { installBrowserAutocompleteSuppression } from '../src/utils/browserAutocomplete'

let dispose

function waitForMutationObserver() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('browser autocomplete suppression', () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <main id="app">
        <input id="name" />
        <input id="city" autocomplete="off" />
        <textarea id="summary"></textarea>
        <input id="password" type="password" autocomplete="new-password" />
        <input id="upload" type="file" />
      </main>
    `
  })

  afterEach(() => {
    dispose?.()
    dispose = null
    document.body.innerHTML = ''
  })

  it('uses the Chromium-compatible suppression value for every existing text control', () => {
    dispose = installBrowserAutocompleteSuppression(document.querySelector('#app'))

    expect(document.querySelector('#name').getAttribute('autocomplete')).toBe('new-password')
    expect(document.querySelector('#city').getAttribute('autocomplete')).toBe('new-password')
    expect(document.querySelector('#summary').getAttribute('autocomplete')).toBe('new-password')
    expect(document.querySelector('#password').getAttribute('autocomplete')).toBe('new-password')
    expect(document.querySelector('#upload').hasAttribute('autocomplete')).toBe(false)
  })

  it('disables autocomplete for text controls rendered later', async () => {
    const root = document.querySelector('#app')
    dispose = installBrowserAutocompleteSuppression(root)

    const modal = document.createElement('section')
    modal.innerHTML = '<input id="company" type="text"><textarea id="description"></textarea>'
    root.append(modal)
    await waitForMutationObserver()

    expect(document.querySelector('#company').getAttribute('autocomplete')).toBe('new-password')
    expect(document.querySelector('#description').getAttribute('autocomplete')).toBe('new-password')
  })
})
