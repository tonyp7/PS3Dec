import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Job } from '@/api/types'
import { useJob } from './useJob'
import { useResource } from './useResource'

const job = (state: Job['state']): Job => ({
  id: 'j1',
  state,
  iso: 'g.iso',
  mode: 'decrypt',
  output: 'g.iso',
  bytes_done: 1,
  bytes_total: 2,
  fraction: 0.5,
  elapsed_s: 1,
  throughput_bps: null,
  eta_s: null,
  log: [],
  error: null,
})

function jobFetch(sequence: Array<Job | null | 'fail'>) {
  let i = 0
  const fn = vi.fn(async () => {
    const next = sequence[Math.min(i++, sequence.length - 1)]
    if (next === 'fail') throw new TypeError('down')
    return new Response(JSON.stringify(next), { status: 200 })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useJob polling', () => {
  it('polls every second while running and every 3 seconds otherwise', async () => {
    const fetchFn = jobFetch([job('running'), job('running'), job('running'), job('succeeded'), job('succeeded')])
    const { result } = renderHook(() => useJob())
    await act(async () => {})
    expect(fetchFn).toHaveBeenCalledTimes(1)
    expect(result.current.job?.state).toBe('running')

    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(fetchFn).toHaveBeenCalledTimes(2)
    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(fetchFn).toHaveBeenCalledTimes(3)
    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(fetchFn).toHaveBeenCalledTimes(4)
    expect(result.current.job?.state).toBe('succeeded')

    // now idle: nothing after 2s, next poll at 3s
    await act(async () => vi.advanceTimersByTimeAsync(2000))
    expect(fetchFn).toHaveBeenCalledTimes(4)
    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(fetchFn).toHaveBeenCalledTimes(5)
  })

  it('recovers by itself after failed requests and clears the error', async () => {
    jobFetch([job('running'), 'fail', 'fail', job('running')])
    const { result } = renderHook(() => useJob())
    await act(async () => {})
    expect(result.current.error).toBeNull()

    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(result.current.error).toBeInstanceOf(Error)
    expect(result.current.job?.state).toBe('running') // last known job is kept

    await act(async () => vi.advanceTimersByTimeAsync(2000)) // retry #1 fails
    expect(result.current.error).toBeInstanceOf(Error)
    await act(async () => vi.advanceTimersByTimeAsync(2000)) // retry #2 succeeds
    expect(result.current.error).toBeNull()
  })

  it('refresh() polls immediately', async () => {
    const fetchFn = jobFetch([null])
    const { result } = renderHook(() => useJob())
    await act(async () => {})
    expect(result.current.job).toBeNull()
    await act(async () => result.current.refresh())
    expect(fetchFn).toHaveBeenCalledTimes(2)
  })

  it('stops polling on unmount', async () => {
    const fetchFn = jobFetch([job('running')])
    const { unmount } = renderHook(() => useJob())
    await act(async () => {})
    unmount()
    await act(async () => vi.advanceTimersByTimeAsync(10000))
    expect(fetchFn).toHaveBeenCalledTimes(1)
  })
})

describe('useResource', () => {
  it('retries after failure and keeps the previous data', async () => {
    const load = vi.fn<() => Promise<string>>()
    load.mockResolvedValueOnce('a').mockRejectedValueOnce(new Error('x')).mockResolvedValue('b')
    const { result } = renderHook(() => useResource(load, 1000))
    await act(async () => {})
    expect(result.current.data).toBe('a')

    await act(async () => result.current.reload())
    expect(result.current.error).toBeInstanceOf(Error)
    expect(result.current.data).toBe('a')

    await act(async () => vi.advanceTimersByTimeAsync(1000))
    expect(result.current.error).toBeNull()
    expect(result.current.data).toBe('b')
  })
})
