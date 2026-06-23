import { describe, it, expect } from 'vitest'
import { clampPhotoNumber, formatDecimal, normalizeFontSizeValue, normalizeLineHeightValue, escapeHtmlText, stripFileExt } from '../src/utils/resumeWriterFormat'

describe('clampPhotoNumber', () => {
  it('clamps within range', () => {
    expect(clampPhotoNumber(5, 0, 10, 1)).toBe(5)
    expect(clampPhotoNumber(-3, 0, 10, 1)).toBe(0)
    expect(clampPhotoNumber(99, 0, 10, 1)).toBe(10)
  })
  it('falls back on non-finite input', () => {
    expect(clampPhotoNumber('abc', 0, 10, 7)).toBe(7)
    expect(clampPhotoNumber(undefined, 0, 10, 2)).toBe(2)
  })
})

describe('formatDecimal', () => {
  it('trims trailing zeros to 3 decimals', () => {
    expect(formatDecimal(1.5)).toBe('1.5')
    expect(formatDecimal(1.23456)).toBe('1.235')
    expect(formatDecimal(2)).toBe('2')
  })
})

describe('normalizeFontSizeValue', () => {
  it('appends px and clamps', () => {
    expect(normalizeFontSizeValue('14')).toBe('14px')
    expect(normalizeFontSizeValue('14px')).toBe('14px')
    expect(normalizeFontSizeValue('99')).toBe('32px')
    expect(normalizeFontSizeValue('2')).toBe('8px')
  })
  it('falls back on invalid input', () => {
    expect(normalizeFontSizeValue('abc')).toBe('12.5px')
    expect(normalizeFontSizeValue('')).toBe('12.5px')
  })
})

describe('normalizeLineHeightValue', () => {
  it('clamps numeric values', () => {
    expect(normalizeLineHeightValue('1.5')).toBe('1.5')
    expect(normalizeLineHeightValue('5')).toBe('3')
    expect(normalizeLineHeightValue('0.1')).toBe('0.8')
  })
  it('falls back on invalid input', () => {
    expect(normalizeLineHeightValue('px')).toBe('1.72')
    expect(normalizeLineHeightValue('')).toBe('1.72')
  })
})

describe('escapeHtmlText', () => {
  it('escapes html-sensitive characters', () => {
    expect(escapeHtmlText('<a href="x">&')).toBe('&lt;a href=&quot;x&quot;&gt;&amp;')
    expect(escapeHtmlText(null)).toBe('')
  })
})

describe('stripFileExt', () => {
  it('removes the trailing extension', () => {
    expect(stripFileExt('resume.pdf')).toBe('resume')
    expect(stripFileExt('a.b.txt')).toBe('a.b')
    expect(stripFileExt('noext')).toBe('noext')
  })
})
