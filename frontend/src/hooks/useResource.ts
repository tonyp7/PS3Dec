import { useCallback, useEffect, useRef, useState } from 'react'

/** Load once, expose `reload`, and keep retrying automatically while the request fails. */
export function useResource<T>(load: () => Promise<T>, retryMs = 3000) {
  const [data, setData] = useState<T | undefined>(undefined)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState(true)
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  })
  const runRef = useRef<() => void>(() => {})

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const run = async () => {
      clearTimeout(timer)
      setLoading(true)
      try {
        const value = await loadRef.current()
        if (stopped) return
        setData(value)
        setError(null)
      } catch (e) {
        if (stopped) return
        setError(e instanceof Error ? e : new Error(String(e)))
        timer = setTimeout(run, retryMs)
      } finally {
        if (!stopped) setLoading(false)
      }
    }
    runRef.current = run
    void run()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [retryMs])

  const reload = useCallback(() => runRef.current(), [])
  return { data, error, loading, reload }
}
