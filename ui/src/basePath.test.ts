import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The prefix every in-app and API URL is built from.
 *
 * Three lines of code that decide whether the deployed app can talk to its
 * gateway at all: the app is served from `/` under `npm run dev` and from
 * `/seshat/` in production, and Vite's BASE_URL always ends in a slash. Leave
 * that slash on and every request goes to `//api/...`, which the browser reads
 * as the host `api` — a cross-origin request to a machine that does not exist,
 * and it only ever happens in the built artefact.
 *
 * Each case re-imports the module, because BASE is computed once at import.
 */
async function basePath(baseUrl: string) {
  vi.stubEnv('BASE_URL', baseUrl)
  vi.resetModules()
  return import('./basePath')
}

afterEach(() => vi.unstubAllEnvs())

describe('served from a sub-path', () => {
  it('has no trailing slash on the prefix, and one slash in the API path', async () => {
    const { API, BASE, withBase } = await basePath('/seshat/')

    expect(BASE).toBe('/seshat')
    expect(API).toBe('/seshat/api')
    expect(withBase('/')).toBe('/seshat/')
    expect(withBase('/callback')).toBe('/seshat/callback')
  })

  it('tolerates a base with several trailing slashes', async () => {
    const { API } = await basePath('/seshat//')

    expect(API).toBe('/seshat/api')
  })

  it('handles a nested sub-path', async () => {
    const { API, withBase } = await basePath('/tools/seshat/')

    expect(API).toBe('/tools/seshat/api')
    expect(withBase('/')).toBe('/tools/seshat/')
  })
})

describe('served from the site root', () => {
  it('builds root-relative URLs with no empty segment', async () => {
    const { API, BASE, withBase } = await basePath('/')

    expect(BASE).toBe('')
    expect(API).toBe('/api')
    // Not '' — the router and the logout redirect both need a real path here.
    expect(withBase('/')).toBe('/')
    expect(withBase('/callback')).toBe('/callback')
  })
})
