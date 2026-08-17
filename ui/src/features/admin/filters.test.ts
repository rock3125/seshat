import { describe, expect, it } from 'vitest'
import {
  DEFAULT_FILTER, decodeHash, encodeHash, isEmptyFilter, resolveRange, toParams,
} from './filters'

// A fixed instant, so "last 15 minutes" is a fact rather than a race.
const NOW = Date.parse('2026-08-17T12:00:00.000Z')

describe('the time range', () => {
  it('resolves a preset to two absolute instants', () => {
    // Resolved in the BROWSER and sent as instants. A preset resolved
    // server-side would quietly mean something different every time a paused
    // tab was refocused, and two views would silently disagree about "now".
    const r = resolveRange({ ...DEFAULT_FILTER, preset: '15m' }, NOW)
    expect(r.to).toBe('2026-08-17T12:00:00.000Z')
    expect(r.from).toBe('2026-08-17T11:45:00.000Z')
  })

  it('resolves the longest preset the gateway will accept', () => {
    const r = resolveRange({ ...DEFAULT_FILTER, preset: '7d' }, NOW)
    expect(r.from).toBe('2026-08-10T12:00:00.000Z')
  })

  it('uses a custom range when one is given', () => {
    const r = resolveRange({
      ...DEFAULT_FILTER,
      preset: 'custom',
      from: '2026-08-01T00:00',
      to: '2026-08-02T00:00',
    }, NOW)
    expect(Date.parse(r.from)).toBeLessThan(Date.parse(r.to))
  })

  it('falls back to the last hour while a custom range is half typed', () => {
    // The control is still being typed into; erroring on every keystroke would
    // be worse than showing something.
    const r = resolveRange({ ...DEFAULT_FILTER, preset: 'custom', from: '2026-08-01T00:00' }, NOW)
    expect(r.from).toBe('2026-08-17T11:00:00.000Z')
  })

  it('falls back when the custom range is backwards', () => {
    const r = resolveRange({
      ...DEFAULT_FILTER, preset: 'custom', from: '2026-08-02T00:00', to: '2026-08-01T00:00',
    }, NOW)
    expect(r.from).toBe('2026-08-17T11:00:00.000Z')
  })
})

describe('the query parameters', () => {
  it('sends level and service for logs, outcome and action for the audit trail', () => {
    const f = {
      ...DEFAULT_FILTER,
      level: 'warn' as const,
      outcome: 'denied' as const,
      services: ['gateway', 'ui'],
      actions: ['chat.turn'],
      users: ['rock'],
    }

    const logs = toParams(f, 'logs', NOW)
    expect(logs.get('level')).toBe('warn')
    expect(logs.get('service')).toBe('gateway,ui')
    expect(logs.get('user')).toBe('rock')
    // The audit trail has no severity and logs have no action; sending either
    // to the wrong endpoint would be a 400.
    expect(logs.get('action')).toBeNull()
    expect(logs.get('outcome')).toBeNull()

    const audit = toParams(f, 'audit', NOW)
    expect(audit.get('outcome')).toBe('denied')
    expect(audit.get('action')).toBe('chat.turn')
    expect(audit.get('user')).toBe('rock')
    expect(audit.get('service')).toBeNull()
    expect(audit.get('level')).toBeNull()
  })

  it('always carries an absolute window', () => {
    const p = toParams(DEFAULT_FILTER, 'logs', NOW)
    expect(p.get('from')).toBe('2026-08-17T11:00:00.000Z')
    expect(p.get('to')).toBe('2026-08-17T12:00:00.000Z')
  })

  it('omits an empty search rather than sending a blank one', () => {
    expect(toParams({ ...DEFAULT_FILTER, q: '   ' }, 'logs', NOW).get('q')).toBeNull()
    expect(toParams({ ...DEFAULT_FILTER, q: ' hi ' }, 'logs', NOW).get('q')).toBe('hi')
  })
})

describe('the URL', () => {
  it('round-trips a filter through the hash', () => {
    const loc = {
      tab: 'audit' as const,
      filter: {
        ...DEFAULT_FILTER,
        outcome: 'error' as const,
        users: ['rock', 'guest'],
        actions: ['library.upload'],
        preset: '24h' as const,
        q: 'ebers',
        req: 'abc123',
      },
    }
    const decoded = decodeHash(encodeHash(loc))
    expect(decoded).not.toBeNull()
    expect(decoded!.tab).toBe('audit')
    expect(decoded!.filter).toEqual(loc.filter)
  })

  it('writes a short URL for the common case', () => {
    // Only non-default values are written, so a link is readable rather than a
    // wall of empty parameters.
    expect(encodeHash({ tab: 'logs', filter: DEFAULT_FILTER })).toBe('#admin/logs')
  })

  it('encodes a search term that would otherwise break the query string', () => {
    const hash = encodeHash({
      tab: 'logs',
      filter: { ...DEFAULT_FILTER, q: 'a&b=c d' },
    })
    expect(decodeHash(hash)!.filter.q).toBe('a&b=c d')
  })

  it('ignores a hash that is not ours', () => {
    expect(decodeHash('')).toBeNull()
    expect(decodeHash('#')).toBeNull()
    expect(decodeHash('#something-else')).toBeNull()
  })

  it('falls back rather than trusting a hand-edited URL', () => {
    // Someone will paste a truncated link, and the view should open on
    // something sensible instead of rendering a tab that does not exist.
    const loc = decodeHash('#admin/nonsense?level=LOUD&range=forever&outcome=maybe')
    expect(loc!.tab).toBe('logs')
    expect(loc!.filter.level).toBeNull()
    expect(loc!.filter.outcome).toBeNull()
    expect(loc!.filter.preset).toBe(DEFAULT_FILTER.preset)
  })

  it('reads a bare admin hash as the default view', () => {
    const loc = decodeHash('#admin')
    expect(loc!.tab).toBe('logs')
    expect(loc!.filter).toEqual(DEFAULT_FILTER)
  })
})

describe('isEmptyFilter', () => {
  it('is true only when nothing narrows the result', () => {
    // Decides whether an empty result offers "clear the filters" or explains
    // that nothing was logged at all — two very different messages.
    expect(isEmptyFilter(DEFAULT_FILTER)).toBe(true)
    expect(isEmptyFilter({ ...DEFAULT_FILTER, preset: '7d' })).toBe(true)
    expect(isEmptyFilter({ ...DEFAULT_FILTER, level: 'error' })).toBe(false)
    expect(isEmptyFilter({ ...DEFAULT_FILTER, users: ['rock'] })).toBe(false)
    expect(isEmptyFilter({ ...DEFAULT_FILTER, q: 'x' })).toBe(false)
    expect(isEmptyFilter({ ...DEFAULT_FILTER, req: 'abc' })).toBe(false)
  })
})
