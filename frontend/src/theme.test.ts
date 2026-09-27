import { describe, expect, it } from 'vitest'
import { followSystemTheme } from './theme'

function fakeMedia(initial: boolean) {
  let matches = initial
  const listeners = new Set<() => void>()
  const mql = {
    get matches() {
      return matches
    },
    addEventListener: (_: string, fn: () => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: () => void) => listeners.delete(fn),
  } as unknown as MediaQueryList
  return {
    matchMedia: () => mql,
    set: (v: boolean) => {
      matches = v
      listeners.forEach((fn) => fn())
    },
    listeners,
  }
}

describe('followSystemTheme', () => {
  it('applies the current preference and tracks changes', () => {
    const root = document.createElement('html')
    const media = fakeMedia(true)
    const stop = followSystemTheme(root, media.matchMedia)
    expect(root.classList.contains('dark')).toBe(true)
    media.set(false)
    expect(root.classList.contains('dark')).toBe(false)
    media.set(true)
    expect(root.classList.contains('dark')).toBe(true)
    stop()
    expect(media.listeners.size).toBe(0)
  })
})
