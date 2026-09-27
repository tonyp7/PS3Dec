import type { IsoItem, KeyItem, Mode } from '@/api/types'

export function defaultMode(iso: IsoItem): Mode {
  return iso.kind === '3k3y-decrypted' ? 'encrypt' : 'decrypt'
}

export function isThreeK3y(iso: IsoItem): boolean {
  return iso.kind === '3k3y-encrypted' || iso.kind === '3k3y-decrypted'
}

/** Why Start must be disabled, or null when a job may be started. */
export function startBlocker(args: {
  iso: IsoItem | undefined
  mode: Mode
  keyName: string | null
  keys: KeyItem[]
  jobRunning: boolean
}): string | null {
  const { iso, mode, keyName, keys, jobRunning } = args
  if (jobRunning) return 'A job is already running.'
  if (!iso) return 'Select an ISO to convert.'
  if (!iso.valid) return 'This image is not usable.'
  if (iso.kind === '3k3y-decrypted' && mode === 'decrypt') return 'This 3k3y image is already decrypted.'
  if (iso.kind === '3k3y-encrypted' && mode === 'encrypt') return 'This 3k3y image is already encrypted.'
  if (iso.kind === 'standard') {
    if (!keyName) return 'Choose a .dkey key for this image.'
    const key = keys.find((k) => k.name === keyName)
    if (!key || !key.valid) return 'The selected key is not valid.'
  }
  return null
}
