import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UploadResult } from '../types'

/**
 * The request each non-streaming call actually puts on the wire.
 *
 * Two things here are only ever exercised by a real upload, and both fail in a
 * way that looks like a server problem: the file name goes in the QUERY STRING,
 * so a name containing `#`, `&` or `+` silently arrives truncated or mangled
 * unless it is encoded; and an error body is JSON with an `error` field, which
 * is the only place the reader's explanation comes from — fall back to the bare
 * status and "over the 25 MB limit" becomes "HTTP 413".
 *
 * keycloak-js is mocked out: constructing it reaches for a realm at import time,
 * and what matters here is that a bearer token is attached at all.
 */
vi.mock('../auth/keycloak', () => ({
  authHeaders: () => Promise.resolve({ Authorization: 'Bearer test-token' }),
}))

const { fetchChunk, fetchConfig, uploadDocument } = await import('./gateway')

type Call = { url: string; init: RequestInit }

let calls: Call[] = []

/** A fetch that answers with `body` and records what it was asked for. */
function answer(body: unknown, { ok = true, status = 200 } = {}) {
  const fetch = vi.fn((url: string, init: RequestInit) => {
    calls.push({ url, init })
    return Promise.resolve({
      ok,
      status,
      json: () => (body instanceof Error ? Promise.reject(body) : Promise.resolve(body)),
    })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

beforeEach(() => {
  calls = []
})

describe('reading from the gateway', () => {
  it('asks for the config with a bearer token', async () => {
    answer({ documents: 3 })

    await fetchConfig()

    expect(calls[0].url).toBe('/api/config')
    expect(calls[0].init.headers).toMatchObject({ Authorization: 'Bearer test-token' })
  })

  it('asks for a chunk with the window size it wants', async () => {
    answer({ count: 3 })

    await fetchChunk(1409, 2)

    expect(calls[0].url).toBe('/api/chunk/1409?around=2')
  })

  it('surfaces the gateway explanation rather than the status code', async () => {
    answer({ error: 'chunk 1409 is not in the library' }, { ok: false, status: 404 })

    await expect(fetchChunk(1409)).rejects.toThrow('chunk 1409 is not in the library')
  })

  it('falls back to the status when there is no explanation to show', async () => {
    // nginx refusing the body, a proxy timing out: the response is not JSON at
    // all, and `res.json()` rejects.
    answer(new Error('not json'), { ok: false, status: 502 })

    await expect(fetchConfig()).rejects.toThrow('HTTP 502')
  })
})

describe('uploading a document', () => {
  const result: Partial<UploadResult> = { source: 'a.txt', path: 'a.txt', status: 'indexed', chunks: 4 }

  it('sends the bytes as the body and the name in the query', async () => {
    answer(result)
    const file = new File(['the whole document'], 'notes.txt', { type: 'text/plain' })

    await uploadDocument(file)

    expect(calls[0].url).toBe('/api/upload?name=notes.txt')
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].init.body).toBe(file)
    expect(calls[0].init.headers).toMatchObject({
      Authorization: 'Bearer test-token',
      'Content-Type': 'application/octet-stream',
    })
  })

  it('encodes a name that would otherwise break the query string', async () => {
    // '#' is the one that loses data silently: everything after it never leaves
    // the browser, and the gateway stores the file under a truncated name.
    answer(result)

    for (const [name, encoded] of [
      ['quarterly report #2.pdf', 'quarterly%20report%20%232.pdf'],
      ['a+b&c=d.txt', 'a%2Bb%26c%3Dd.txt'],
      ['té—ka.md', 't%C3%A9%E2%80%94ka.md'],
      ['100%.csv', '100%25.csv'],
    ]) {
      calls = []
      await uploadDocument(new File(['x'], name))
      expect(calls[0].url).toBe(`/api/upload?name=${encoded}`)
    }
  })

  it('returns the verdict the gateway reported', async () => {
    answer({ ...result, converted_from: 'PDF', truncated: true })

    await expect(uploadDocument(new File(['x'], 'a.pdf'))).resolves.toMatchObject({
      status: 'indexed',
      chunks: 4,
      converted_from: 'PDF',
      truncated: true,
    })
  })

  it('surfaces a refusal in the words the reader needs', async () => {
    answer({ error: 'notes.exe is over the 25 MB limit' }, { ok: false, status: 413 })

    await expect(uploadDocument(new File(['x'], 'notes.exe')))
      .rejects.toThrow('notes.exe is over the 25 MB limit')
  })

  it('falls back to the status when the refusal carries no body', async () => {
    // What nginx's own 413 looks like: an HTML page, not the gateway's JSON.
    answer(new Error('not json'), { ok: false, status: 413 })

    await expect(uploadDocument(new File(['x'], 'big.pdf'))).rejects.toThrow('HTTP 413')
  })
})
