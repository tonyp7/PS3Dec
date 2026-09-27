export type IsoKind = '3k3y-encrypted' | '3k3y-decrypted' | 'standard' | 'invalid'
export type Mode = 'decrypt' | 'encrypt'
export type JobState = 'running' | 'succeeded' | 'failed' | 'cancelled'

export interface IsoItem {
  name: string
  size: number
  kind: IsoKind
  valid: boolean
  reason: string | null
  suggested_key: string | null
}

export interface KeyItem {
  name: string
  valid: boolean
  reason: string | null
}

export interface Listing<T> {
  available: boolean
  folder: string
  items: T[]
}

export interface Job {
  id: string
  state: JobState
  iso: string
  mode: Mode
  output: string
  bytes_done: number
  bytes_total: number
  fraction: number
  elapsed_s: number
  throughput_bps: number | null
  eta_s: number | null
  log: string[]
  error: string | null
}

export interface StartBody {
  iso: string
  mode: Mode
  key?: string
  overwrite?: boolean
}
