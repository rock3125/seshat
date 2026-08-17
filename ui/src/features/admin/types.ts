// The shapes the admin API answers with.
//
// Logs and audit records are deliberately different types rather than one
// union: they answer different questions (what the system said, versus what a
// person did), and the one place they must agree — the filter that selects
// them — is `Filter` in ./filters.ts, not this file.

/** One severity ladder for nine services that each have their own. A floor,
 *  not an equality: `warn` means warn and everything above it. */
export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'fatal'

export const LEVELS: LogLevel[] = ['debug', 'info', 'warn', 'error', 'fatal']

/** An audit record is not more or less severe — it either happened, was
 *  refused, or broke. Rendered by the same control as [LogLevel]. */
export type AuditOutcome = 'ok' | 'denied' | 'error'

export const OUTCOMES: AuditOutcome[] = ['ok', 'denied', 'error']

export interface LogRecord {
  /** RFC 3339, as the service itself stamped it — not when it was collected. */
  ts: string
  service: string
  level: LogLevel
  msg: string
  /** Who caused the request this line was written during, from the gateway's
   *  MDC. Empty for lines written outside a request. */
  user: string
  /** The request id shared with the audit row for the same request. */
  req: string
  /** `unstructured` marks a service whose logs are not JSON (cAdvisor, nginx's
   *  error log). Shown as such rather than dressed up with invented fields. */
  format: 'json' | 'unstructured'
  fields: Record<string, unknown>
}

export interface AuditRecord {
  id: number
  ts: string
  user: string
  subject: string
  session: string
  req: string
  action: string
  target: string
  outcome: AuditOutcome
  status: number
  ip: string
  duration_ms: number
  detail: Record<string, unknown>
}

export interface Range {
  from: string | null
  to: string | null
}

export interface LogPage {
  /** The LogQL the gateway built. Shown in the UI: an administrator should be
   *  able to see exactly what was asked, both to trust the answer and to
   *  reproduce it elsewhere. */
  query: string
  count: number
  records: LogRecord[]
  range: Range
}

export interface AuditPage {
  count: number
  records: AuditRecord[]
  /** Opaque; pass back as `cursor` for the next page. Null at the end. */
  cursor: string | null
  range: Range
}

export interface LogFacets {
  services: string[]
  levels: LogLevel[]
}

export interface AuditFacets {
  users: string[]
  actions: string[]
  outcomes: AuditOutcome[]
}

export interface PanelMeta {
  name: string
  title: string
  unit: PanelUnit
}

export type PanelUnit = 'percent' | 'bytes' | 'rate' | 'seconds' | 'count'

/** A point is `[unixSeconds, value]`, and the value is null where Prometheus
 *  reported a gap — drawn as a break in the line, not a line straight across
 *  the outage. */
export type Point = [number, number | null]

export interface Series {
  name: string
  labels: Record<string, string>
  points: Point[]
}

export interface PanelData {
  panel: string
  title: string
  unit: PanelUnit
  query: string
  series: Series[]
  range: Range
}

export interface TargetStatus {
  job: string
  instance: string
  up: boolean
}
