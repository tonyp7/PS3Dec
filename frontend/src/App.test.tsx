import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { iso, job, key, mockBackend, refusal } from './test/mockApi'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const gameBackend = (over = {}) =>
  mockBackend({
    isos: {
      available: true,
      folder: '/data/iso',
      items: [
        iso({ name: 'Game.iso', suggested_key: 'Game.dkey' }),
        iso({ name: 'Other.iso' }),
        iso({ name: 'Enc.iso', kind: '3k3y-encrypted', size: 2048 }),
        iso({ name: 'Dec.iso', kind: '3k3y-decrypted', size: 2048 }),
      ],
    },
    keys: { available: true, folder: '/data/keys', items: [key('Game.dkey'), key('Other.dkey'), key('bad.dkey', false)] },
    ...over,
  })

const start = () => screen.getByRole('button', { name: /^start/i })
const pick = async (name: string) => userEvent.click(await screen.findByRole('radio', { name }))
const pressed = (name: string) => screen.getByRole('button', { name }).getAttribute('aria-pressed')

describe('selection and start gating', () => {
  it('pre-selects the suggested key and defaults to decrypt', async () => {
    gameBackend()
    render(<App />)
    expect(await screen.findByText('No job has run yet. Pick an image and press Start.')).toBeInTheDocument()
    expect(start()).toBeDisabled()
    expect(screen.getByText('Select an ISO to convert.')).toBeInTheDocument()

    await pick('Game.iso')
    expect(screen.getByRole('combobox', { name: /key/i })).toHaveTextContent('Game.dkey')
    expect(pressed('Decrypt')).toBe('true')
    expect(start()).toBeEnabled()
  })

  it('blocks Start until a key is chosen for a standard image', async () => {
    gameBackend()
    render(<App />)
    await pick('Other.iso')
    expect(start()).toBeDisabled()
    expect(screen.getByText('Choose a .dkey key for this image.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('combobox', { name: /key/i }))
    expect(screen.getByRole('option', { name: /bad\.dkey/ })).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(screen.getByRole('option', { name: 'Other.dkey' }))
    expect(start()).toBeEnabled()
  })

  it('disables the key select for 3k3y images and allows Start without a key', async () => {
    gameBackend()
    render(<App />)
    await pick('Enc.iso')
    expect(screen.getByRole('combobox', { name: /key/i })).toBeDisabled()
    expect(screen.getByText(/carry their own key/)).toBeInTheDocument()
    expect(start()).toBeEnabled()
    expect(pressed('Decrypt')).toBe('true')

    await userEvent.click(screen.getByRole('button', { name: 'Encrypt' }))
    expect(start()).toBeDisabled()
    expect(screen.getByText('This 3k3y image is already encrypted.')).toBeInTheDocument()
  })

  it('defaults a decrypted 3k3y image to encrypt', async () => {
    gameBackend()
    render(<App />)
    await pick('Dec.iso')
    expect(pressed('Encrypt')).toBe('true')
    expect(start()).toBeEnabled()
  })

  it('disables Start while a job is running, with the reason', async () => {
    gameBackend({ job: job() })
    render(<App />)
    await pick('Game.iso')
    expect(start()).toBeDisabled()
    expect(screen.getByText('A job is already running.')).toBeInTheDocument()
  })

  it('sends the selected iso, mode and key (and no key for 3k3y)', async () => {
    // jobs finish immediately here so that Start is available again for the second request
    const backend = gameBackend({ onStart: () => ({ status: 202, body: job({ state: 'succeeded' }) }) })
    render(<App />)
    await pick('Game.iso')
    await userEvent.click(start())
    expect(await screen.findByText('Decrypted successfully')).toBeInTheDocument()
    await pick('Enc.iso')
    await userEvent.click(start())
    expect(backend.starts).toEqual([
      { iso: 'Game.iso', mode: 'decrypt', key: 'Game.dkey', overwrite: false },
      { iso: 'Enc.iso', mode: 'decrypt', overwrite: false },
    ])
  })
})

describe('start rejections and overwrite confirmation', () => {
  it('asks before overwriting and only sends overwrite after confirmation', async () => {
    const backend = gameBackend({
      onStart: (body: { overwrite?: boolean }) =>
        body.overwrite ? { status: 202, body: job() } : refusal(409, 'output_exists', 'Game.iso already exists'),
    })
    render(<App />)
    await pick('Game.iso')
    await userEvent.click(start())

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(/already exists in the output folder/)).toBeInTheDocument()
    expect(backend.starts).toHaveLength(1)
    expect(backend.starts[0].overwrite).toBe(false)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Replace' }))
    expect(backend.starts).toHaveLength(2)
    expect(backend.starts[1].overwrite).toBe(true)
    expect(await screen.findByText(/Decrypting Game\.iso/)).toBeInTheDocument()
  })

  it('does not send anything more when the confirmation is cancelled', async () => {
    const backend = gameBackend({ onStart: () => refusal(409, 'output_exists', 'exists') })
    render(<App />)
    await pick('Game.iso')
    await userEvent.click(start())
    const dialog = await screen.findByRole('alertdialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(backend.starts).toHaveLength(1)
    expect(start()).toBeEnabled() // ready for another attempt
  })

  it.each([
    [507, 'insufficient_space', 'not enough free space in the output folder: need 3.0 GiB, have 1.0 GiB'],
    [409, 'job_running', 'another job is already running'],
    [500, 'output_not_writable', 'output folder /data/output is not writable'],
  ])('shows the backend message for %s %s and stays usable', async (status, code, message) => {
    gameBackend({ onStart: () => refusal(status, code, message) })
    render(<App />)
    await pick('Game.iso')
    await userEvent.click(start())
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(start()).toBeEnabled()
  })
})

describe('job display', () => {
  it('shows a running job on load without any action (reload case)', async () => {
    gameBackend({ job: job() })
    render(<App />)
    expect(await screen.findByText('Decrypting Game.iso')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Progress' })).toHaveAttribute('aria-valuenow', String((1 / 3) * 100))
    expect(screen.getByText(/33% · 1\.0 GiB of 3\.0 GiB/)).toBeInTheDocument()
    expect(screen.getByText('1m 05s')).toBeInTheDocument()
    expect(screen.getByText('50.0 MiB/s')).toBeInTheDocument()
    expect(screen.getByText('42s')).toBeInTheDocument()
    expect(screen.getByText('Decrypting sectors 0 to 100')).toBeInTheDocument()
  })

  it('cancels through the API', async () => {
    const backend = gameBackend({ job: job() })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(backend.cancels).toBe(1)
  })

  it('shows the last finished job on load', async () => {
    gameBackend({ job: job({ state: 'succeeded', fraction: 1, bytes_done: 3 * 1024 ** 3, elapsed_s: 252 }) })
    render(<App />)
    expect(await screen.findByText('Decrypted successfully')).toBeInTheDocument()
    expect(screen.getByText(/is in the output folder\. Took 4m 12s/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })
})

describe('connection errors', () => {
  it('shows a banner while the backend is unreachable, keeps the selection, and recovers', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const backend = gameBackend()
    render(<App />)
    await pick('Game.iso')
    expect(screen.queryByText(/Can’t reach/)).not.toBeInTheDocument()

    backend.down = true
    await act(async () => vi.advanceTimersByTimeAsync(3500))
    expect(screen.getByText(/Can’t reach the PS3Dec backend/)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Game.iso' })).toBeChecked()

    backend.down = false
    await act(async () => vi.advanceTimersByTimeAsync(4000))
    expect(screen.queryByText(/Can’t reach/)).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Game.iso' })).toBeChecked()
    expect(start()).toBeEnabled()
  })
})
