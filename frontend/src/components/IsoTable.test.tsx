import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { IsoItem, Listing } from '@/api/types'
import { IsoTable } from './IsoTable'

const item = (over: Partial<IsoItem>): IsoItem => ({
  name: 'a.iso',
  size: 3 * 1024 ** 3,
  kind: 'standard',
  valid: true,
  reason: null,
  suggested_key: null,
  ...over,
})

const listing = (items: IsoItem[], over: Partial<Listing<IsoItem>> = {}): Listing<IsoItem> => ({
  available: true,
  folder: '/data/iso',
  items,
  ...over,
})

const setup = (l: Listing<IsoItem> | undefined, selected: string | null = null) => {
  const onSelect = vi.fn()
  const onRefresh = vi.fn()
  render(<IsoTable listing={l} selected={selected} onSelect={onSelect} onRefresh={onRefresh} />)
  return { onSelect, onRefresh }
}

describe('IsoTable', () => {
  it('shows name, size, classification and key info for each image', async () => {
    const { onSelect } = setup(
      listing([
        item({ name: 'Game.iso', suggested_key: 'Game.dkey' }),
        item({ name: 'K.iso', kind: '3k3y-encrypted', size: 1024 }),
        item({ name: 'Other.iso' }),
      ]),
    )
    const rows = screen.getAllByRole('row')
    expect(rows).toHaveLength(4) // header + 3
    expect(screen.getByText('Game.dkey')).toBeInTheDocument()
    expect(screen.getAllByText('3.0 GiB', { selector: 'td' })).toHaveLength(2)
    expect(screen.getByText('1.0 KiB', { selector: 'td' })).toBeInTheDocument()
    expect(screen.getByText('3k3y · encrypted')).toBeInTheDocument()
    expect(screen.getByText('not needed')).toBeInTheDocument()
    expect(screen.getByText('no matching key')).toBeInTheDocument()
    expect(screen.getAllByText('Standard')).toHaveLength(2)

    await userEvent.click(screen.getByRole('radio', { name: 'Game.iso' }))
    expect(onSelect).toHaveBeenCalledWith('Game.iso')
  })

  it('shows invalid images with their reason and does not let them be selected', async () => {
    const { onSelect } = setup(listing([item({ name: 'bad.iso', kind: 'invalid', valid: false, reason: 'file is smaller than 4096 bytes' })]))
    expect(screen.getByText('Invalid')).toBeInTheDocument()
    expect(screen.getByText('file is smaller than 4096 bytes')).toBeInTheDocument()
    const radio = screen.getByRole('radio', { name: 'bad.iso' })
    expect(radio).toBeDisabled()
    await userEvent.click(radio)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('marks the selected image', () => {
    setup(listing([item({ name: 'a.iso' }), item({ name: 'b.iso' })]), 'b.iso')
    expect(screen.getByRole('radio', { name: 'b.iso' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'a.iso' })).not.toBeChecked()
  })

  it('names the folder when it is empty', () => {
    setup(listing([]))
    expect(screen.getByText(/No/)).toHaveTextContent('/data/iso')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('explains when the folder is not mounted', () => {
    setup(listing([], { available: false }))
    expect(screen.getByText(/is not available/)).toHaveTextContent('/data/iso')
  })

  it('shows a loading state and refreshes on demand', async () => {
    const { onRefresh } = setup(undefined)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /refresh/i }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })
})
