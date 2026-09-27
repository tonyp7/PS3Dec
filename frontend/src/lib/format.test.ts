import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatRate } from './format'

describe('format', () => {
  it('formats bytes', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KiB')
    expect(formatBytes(1.5 * 1024 ** 3)).toBe('1.5 GiB')
    expect(formatBytes(30 * 1024 ** 4)).toBe('30.0 TiB')
  })
  it('formats rates', () => {
    expect(formatRate(52.4 * 1024 * 1024)).toBe('52.4 MiB/s')
  })
  it('formats durations', () => {
    expect(formatDuration(0)).toBe('0s')
    expect(formatDuration(59.6)).toBe('1m 00s')
    expect(formatDuration(125)).toBe('2m 05s')
    expect(formatDuration(3723)).toBe('1h 02m 03s')
    expect(formatDuration(-5)).toBe('0s')
  })
})
