import { vi } from 'vitest'
import type { IsoItem, Job, KeyItem, Listing, StartBody } from '@/api/types'

export const iso = (over: Partial<IsoItem> = {}): IsoItem => ({
  name: 'Game.iso',
  size: 3 * 1024 ** 3,
  kind: 'standard',
  valid: true,
  reason: null,
  suggested_key: null,
  ...over,
})

export const key = (name: string, valid = true): KeyItem => ({ name, valid, reason: valid ? null : 'bad' })

export const job = (over: Partial<Job> = {}): Job => ({
  id: 'j1',
  state: 'running',
  iso: 'Game.iso',
  mode: 'decrypt',
  output: 'Game.iso',
  bytes_done: 1024 ** 3,
  bytes_total: 3 * 1024 ** 3,
  fraction: 1 / 3,
  elapsed_s: 65,
  throughput_bps: 50 * 1024 ** 2,
  eta_s: 42,
  log: ['Decrypting sectors 0 to 100'],
  error: null,
  ...over,
})

export interface Backend {
  isos: Listing<IsoItem>
  keys: Listing<KeyItem>
  job: Job | null
  /** When set, every request rejects like an unreachable server. */
  down: boolean
  /** Decides the answer to POST /api/job. */
  onStart: (body: StartBody) => { status: number; body: unknown }
  starts: StartBody[]
  cancels: number
}

export function mockBackend(over: Partial<Backend> = {}): Backend {
  const backend: Backend = {
    isos: { available: true, folder: '/data/iso', items: [] },
    keys: { available: true, folder: '/data/keys', items: [] },
    job: null,
    down: false,
    onStart: () => ({ status: 202, body: job() }),
    starts: [],
    cancels: 0,
    ...over,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (backend.down) throw new TypeError('failed to fetch')
      const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status })
      if (path === '/api/isos') return json(200, backend.isos)
      if (path === '/api/keys') return json(200, backend.keys)
      if (path === '/api/job' && (!init || !init.method || init.method === 'GET')) return json(200, backend.job)
      if (path === '/api/job' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as StartBody
        backend.starts.push(body)
        const answer = backend.onStart(body)
        if (answer.status === 202) backend.job = answer.body as Job
        return json(answer.status, answer.body)
      }
      if (path === '/api/job' && init?.method === 'DELETE') {
        backend.cancels++
        return new Response(null, { status: 204 })
      }
      return json(404, { detail: 'nope' })
    }),
  )
  return backend
}

export const refusal = (status: number, code: string, message: string) => ({
  status,
  body: { detail: { code, message } },
})
