// The one filter both the log view and the audit view are driven by.
//
// Severity, who, when, where, and a text search. One shape, one component
// (FilterBar), one URL encoding — because a reader who has learnt the filters
// in one view has learnt them in the other, and because two implementations of
// a date-range control diverge within a month.

import type { AuditOutcome, LogLevel } from './types'
import { LEVELS, OUTCOMES } from './types'

export type Preset = '15m' | '1h' | '6h' | '24h' | '7d' | 'custom'

export const PRESETS: { id: Preset; label: string; seconds: number }[] = [
  { id: '15m', label: '15 min', seconds: 15 * 60 },
  { id: '1h', label: '1 hour', seconds: 3600 },
  { id: '6h', label: '6 hours', seconds: 6 * 3600 },
  { id: '24h', label: '24 hours', seconds: 24 * 3600 },
  // The gateway refuses anything longer, so this is the last preset there can
  // be. Offering a "30 days" that always errors would be worse than not
  // offering it.
  { id: '7d', label: '7 days', seconds: 7 * 24 * 3600 },
]

export interface Filter {
  /** Logs: the severity FLOOR — `warn` includes error and fatal. Null is all. */
  level: LogLevel | null
  /** Audit: the outcome. Null is all. */
  outcome: AuditOutcome | null
  users: string[]
  services: string[]
  actions: string[]
  preset: Preset
  /** Only read when preset is 'custom'. `datetime-local` shape, local time. */
  from: string
  to: string
  q: string
  /** Set by clicking a request id — pins both views to one request. */
  req: string
}

export const DEFAULT_FILTER: Filter = {
  level: null,
  outcome: null,
  users: [],
  services: [],
  actions: [],
  preset: '1h',
  from: '',
  to: '',
  q: '',
  req: '',
}

/**
 * The window as two absolute instants.
 *
 * Presets are resolved HERE, in the browser, at query time — and the absolute
 * pair is what goes on the wire. A preset resolved server-side would quietly
 * mean something different every time a paused tab was refocused, and an
 * administrator comparing two views would be comparing two different windows
 * without being told which.
 */
export function resolveRange(f: Filter, now = Date.now()): { from: string; to: string } {
  if (f.preset === 'custom') {
    const from = Date.parse(f.from)
    const to = Date.parse(f.to)
    // A half-filled or nonsensical custom range falls back to the last hour
    // rather than erroring: the control is still being typed into.
    if (Number.isFinite(from) && Number.isFinite(to) && from < to) {
      return { from: new Date(from).toISOString(), to: new Date(to).toISOString() }
    }
    return { from: new Date(now - 3600_000).toISOString(), to: new Date(now).toISOString() }
  }
  const seconds = PRESETS.find((p) => p.id === f.preset)?.seconds ?? 3600
  return {
    from: new Date(now - seconds * 1000).toISOString(),
    to: new Date(now).toISOString(),
  }
}

/** The filter as query parameters for `/admin/logs` or `/admin/audit`. */
export function toParams(f: Filter, kind: 'logs' | 'audit', now = Date.now()): URLSearchParams {
  const { from, to } = resolveRange(f, now)
  const p = new URLSearchParams({ from, to })

  if (kind === 'logs') {
    if (f.level) p.set('level', f.level)
    if (f.services.length) p.set('service', f.services.join(','))
  } else {
    if (f.outcome) p.set('outcome', f.outcome)
    if (f.actions.length) p.set('action', f.actions.join(','))
  }
  if (f.users.length) p.set('user', f.users.join(','))
  if (f.q.trim()) p.set('q', f.q.trim())
  if (f.req.trim()) p.set('req', f.req.trim())
  return p
}

// --- the URL ----------------------------------------------------------------
//
// The whole view lives in location.hash: `#admin/logs?level=warn&user=rock`.
// An administrator investigating something needs to be able to send a colleague
// the exact view they are looking at, and to have the back button do the
// obvious thing. That costs this much code and no router.

export type AdminTab = 'logs' | 'audit' | 'metrics' | 'services'

export const TABS: { id: AdminTab; label: string }[] = [
  { id: 'logs', label: 'Logs' },
  { id: 'audit', label: 'Audit' },
  { id: 'metrics', label: 'Metrics' },
  { id: 'services', label: 'Services' },
]

export interface AdminLocation {
  tab: AdminTab
  filter: Filter
}

/** Read `#admin/<tab>?<filter>`, or null when the hash is not ours. */
export function decodeHash(hash: string): AdminLocation | null {
  const raw = hash.replace(/^#/, '')
  if (!raw.startsWith('admin')) return null
  const [path, search = ''] = raw.split('?')
  const tab = (path.split('/')[1] ?? 'logs') as AdminTab
  const p = new URLSearchParams(search)

  const list = (key: string) =>
    (p.get(key) ?? '').split(',').map((s) => s.trim()).filter(Boolean)

  const level = p.get('level')
  const outcome = p.get('outcome')
  const preset = p.get('range') as Preset | null

  return {
    tab: TABS.some((t) => t.id === tab) ? tab : 'logs',
    filter: {
      ...DEFAULT_FILTER,
      level: LEVELS.includes(level as LogLevel) ? (level as LogLevel) : null,
      outcome: OUTCOMES.includes(outcome as AuditOutcome) ? (outcome as AuditOutcome) : null,
      users: list('user'),
      services: list('service'),
      actions: list('action'),
      preset: preset && (preset === 'custom' || PRESETS.some((x) => x.id === preset))
        ? preset
        : DEFAULT_FILTER.preset,
      from: p.get('from') ?? '',
      to: p.get('to') ?? '',
      q: p.get('q') ?? '',
      req: p.get('req') ?? '',
    },
  }
}

/** The inverse. Only non-default values are written, so the common case is a
 *  short, readable URL rather than a wall of empty parameters. */
export function encodeHash(loc: AdminLocation): string {
  const f = loc.filter
  const p = new URLSearchParams()
  if (f.level) p.set('level', f.level)
  if (f.outcome) p.set('outcome', f.outcome)
  if (f.users.length) p.set('user', f.users.join(','))
  if (f.services.length) p.set('service', f.services.join(','))
  if (f.actions.length) p.set('action', f.actions.join(','))
  if (f.preset !== DEFAULT_FILTER.preset) p.set('range', f.preset)
  if (f.preset === 'custom') {
    if (f.from) p.set('from', f.from)
    if (f.to) p.set('to', f.to)
  }
  if (f.q) p.set('q', f.q)
  if (f.req) p.set('req', f.req)
  const search = p.toString()
  return `#admin/${loc.tab}${search ? `?${search}` : ''}`
}

/** True when nothing is filtered — used to offer "widen" on an empty result
 *  only when there is in fact something to widen. */
export function isEmptyFilter(f: Filter): boolean {
  return (
    f.level === null && f.outcome === null && !f.users.length && !f.services.length &&
    !f.actions.length && !f.q.trim() && !f.req.trim()
  )
}
