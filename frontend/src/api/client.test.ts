import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError, NetworkError, api } from './client'

function respond(status: number, body?: unknown, raw?: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(status === 204 ? null : (raw ?? JSON.stringify(body)), { status })),
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('returns parsed JSON and null jobs', async () => {
    respond(200, { available: true, folder: '/x', items: [] })
    expect((await api.isos()).folder).toBe('/x')
    respond(200, null)
    expect(await api.job()).toBeNull()
  })

  it('maps backend refusals to ApiRequestError with code and message', async () => {
    respond(409, { detail: { code: 'output_exists', message: 'already there' } })
    const err = await api.start({ iso: 'a.iso', mode: 'decrypt' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiRequestError)
    expect(err).toMatchObject({ status: 409, code: 'output_exists', message: 'already there' })
  })

  it('sends the start request as JSON', async () => {
    respond(202, { id: 'x' })
    await api.start({ iso: 'a.iso', mode: 'encrypt', key: 'k.dkey', overwrite: true })
    const [path, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(path).toBe('/api/job')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ iso: 'a.iso', mode: 'encrypt', key: 'k.dkey', overwrite: true })
  })

  it('treats 204 as success', async () => {
    respond(204)
    await expect(api.cancel()).resolves.toBeUndefined()
  })

  it('treats unreachable servers and foreign errors as NetworkError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('failed to fetch')))
    await expect(api.isos()).rejects.toBeInstanceOf(NetworkError)
    respond(502, undefined, '<html>bad gateway</html>')
    await expect(api.isos()).rejects.toBeInstanceOf(NetworkError)
    respond(500, { detail: 'boom' })
    await expect(api.job()).rejects.toBeInstanceOf(NetworkError)
  })
})
