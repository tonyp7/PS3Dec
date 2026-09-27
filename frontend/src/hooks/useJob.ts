import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { Job } from '@/api/types'

export interface JobPolling {
  runningMs?: number
  idleMs?: number
  retryMs?: number
}

/**
 * Follow the backend's single job. Polls quickly while it runs and slowly otherwise (to notice
 * a job started from another tab); a failed poll is retried until the backend answers again.
 * `job` is `undefined` until the first answer, `null` when the backend has no job.
 */
export function useJob({ runningMs = 1000, idleMs = 3000, retryMs = 2000 }: JobPolling = {}) {
  const [job, setJob] = useState<Job | null | undefined>(undefined)
  const [error, setError] = useState<Error | null>(null)
  const tickRef = useRef<() => void>(() => {})

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      clearTimeout(timer)
      try {
        const next = await api.job()
        if (stopped) return
        setJob(next)
        setError(null)
        timer = setTimeout(tick, next?.state === 'running' ? runningMs : idleMs)
      } catch (e) {
        if (stopped) return
        setError(e instanceof Error ? e : new Error(String(e)))
        timer = setTimeout(tick, retryMs)
      }
    }
    tickRef.current = tick
    void tick()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [runningMs, idleMs, retryMs])

  const refresh = useCallback(() => tickRef.current(), [])
  return { job, error, refresh }
}
