import type { IsoItem, Job, KeyItem, Listing, StartBody } from './types'

/** The backend understood the request and refused it (`{detail: {code, message}}`). */
export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
  }
}

/** The backend could not be reached, or answered with something that is not ours. */
export class NetworkError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NetworkError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw new NetworkError('cannot reach the server')
  }
  if (res.status === 204) return undefined as T
  let body: unknown
  try {
    body = await res.json()
  } catch {
    throw new NetworkError(`unexpected response (HTTP ${res.status})`)
  }
  if (res.ok) return body as T
  const detail = (body as { detail?: { code?: unknown; message?: unknown } } | null)?.detail
  if (detail && typeof detail.code === 'string' && typeof detail.message === 'string') {
    throw new ApiRequestError(res.status, detail.code, detail.message)
  }
  throw new NetworkError(`server error (HTTP ${res.status})`)
}

export const api = {
  isos: () => request<Listing<IsoItem>>('/api/isos'),
  keys: () => request<Listing<KeyItem>>('/api/keys'),
  job: () => request<Job | null>('/api/job'),
  start: (body: StartBody) =>
    request<Job>('/api/job', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  cancel: () => request<void>('/api/job', { method: 'DELETE' }),
}
