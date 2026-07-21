import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from '../src/utils/clipboard'

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const originalExecCommand = document.execCommand

afterEach(() => {
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
  else delete navigator.clipboard
  document.execCommand = originalExecCommand
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('copyText', () => {
  it('uses the clipboard API when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })

    await expect(copyText('print("ok")')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('print("ok")')
  })

  it('falls back to execCommand when clipboard access fails', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    })
    document.execCommand = vi.fn().mockReturnValue(true)

    await expect(copyText('fallback')).resolves.toBe(true)
    expect(document.execCommand).toHaveBeenCalledWith('copy')
    expect(document.querySelector('textarea')).toBeNull()
  })

  it('does not copy empty content', async () => {
    await expect(copyText('')).resolves.toBe(false)
  })
})
