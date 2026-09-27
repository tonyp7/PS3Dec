import { describe, expect, it } from 'vitest'
import type { IsoItem, KeyItem } from '@/api/types'
import { defaultMode, startBlocker } from './gate'

const iso = (kind: IsoItem['kind'], valid = kind !== 'invalid'): IsoItem => ({
  name: 'g.iso',
  size: 1,
  kind,
  valid,
  reason: null,
  suggested_key: null,
})
const keys: KeyItem[] = [
  { name: 'ok.dkey', valid: true, reason: null },
  { name: 'bad.dkey', valid: false, reason: 'nope' },
]
const base = { mode: 'decrypt' as const, keyName: 'ok.dkey', keys, jobRunning: false }

describe('defaultMode', () => {
  it('encrypts decrypted 3k3y images and decrypts everything else', () => {
    expect(defaultMode(iso('3k3y-decrypted'))).toBe('encrypt')
    expect(defaultMode(iso('3k3y-encrypted'))).toBe('decrypt')
    expect(defaultMode(iso('standard'))).toBe('decrypt')
  })
})

describe('startBlocker', () => {
  it('allows a standard image with a valid key', () => {
    expect(startBlocker({ ...base, iso: iso('standard') })).toBeNull()
  })
  it('allows 3k3y without a key', () => {
    expect(startBlocker({ ...base, iso: iso('3k3y-encrypted'), keyName: null })).toBeNull()
  })
  it('explains each block', () => {
    expect(startBlocker({ ...base, iso: undefined })).toMatch(/Select an ISO/)
    expect(startBlocker({ ...base, iso: iso('standard'), jobRunning: true })).toMatch(/already running/)
    expect(startBlocker({ ...base, iso: iso('standard'), keyName: null })).toMatch(/key/)
    expect(startBlocker({ ...base, iso: iso('standard'), keyName: 'bad.dkey' })).toMatch(/not valid/)
    expect(startBlocker({ ...base, iso: iso('standard'), keyName: 'gone.dkey' })).toMatch(/not valid/)
    expect(startBlocker({ ...base, iso: iso('3k3y-decrypted') })).toMatch(/already decrypted/)
    expect(startBlocker({ ...base, iso: iso('3k3y-encrypted'), mode: 'encrypt' })).toMatch(/already encrypted/)
    expect(startBlocker({ ...base, iso: iso('invalid') })).toMatch(/not usable/)
  })
})
