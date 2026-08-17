import { useState } from 'react'

/**
 * A record, not a line.
 *
 * Both views render through here: fixed columns for the things every record
 * has, and an expandable key/value view for everything else. Two rules the
 * codebase already holds elsewhere and this keeps:
 *
 *   - the reader never sees a raw JSON blob (see types.ts on `Trace`), so the
 *     detail opens as a definition list;
 *   - a truncated or empty result says so explicitly, because a silently short
 *     list reads as an answer.
 */

export interface Column<T> {
  key: string
  label: string
  /** A CSS grid track. The timestamp is fixed and monospace so a column of
   *  times can actually be scanned; the message takes what is left. */
  width: string
  render: (row: T) => React.ReactNode
  className?: string
}

export default function RecordTable<T>({
  rows,
  columns,
  rowKey,
  detail,
  accent,
  empty,
  truncated,
  onLoadMore,
  loadingMore,
}: {
  rows: T[]
  columns: Column<T>[]
  rowKey: (row: T, index: number) => string
  /** The expanded body. Returned as pairs so it renders as a definition list
   *  rather than as pretty-printed JSON. */
  detail: (row: T) => [string, React.ReactNode][]
  /** A modifier class per row — the severity or outcome stripe. */
  accent?: (row: T) => string
  empty: React.ReactNode
  /** Shown when the server capped the page. */
  truncated?: React.ReactNode
  onLoadMore?: () => void
  loadingMore?: boolean
}) {
  const [open, setOpen] = useState<Set<string>>(new Set())

  function toggle(key: string) {
    setOpen((prev) => {
      const next = new Set(prev)
      if (!next.delete(key)) next.add(key)
      return next
    })
  }

  if (rows.length === 0) return <div className="empty-state">{empty}</div>

  const template = columns.map((c) => c.width).join(' ')

  return (
    <div className="records">
      <div className="rec-head" style={{ gridTemplateColumns: template }}>
        {columns.map((c) => (
          <span key={c.key} className={c.className}>{c.label}</span>
        ))}
      </div>

      <div className="rec-body">
        {rows.map((row, i) => {
          const key = rowKey(row, i)
          const isOpen = open.has(key)
          return (
            <div key={key} className={`rec${accent ? ` ${accent(row)}` : ''}`}>
              <button
                type="button"
                className={`rec-line${isOpen ? ' open' : ''}`}
                style={{ gridTemplateColumns: template }}
                onClick={() => toggle(key)}
                aria-expanded={isOpen}
              >
                {columns.map((c) => (
                  <span key={c.key} className={c.className}>{c.render(row)}</span>
                ))}
              </button>

              {isOpen ? (
                <dl className="rec-detail">
                  {detail(row).map(([k, v]) => (
                    <div key={k}>
                      <dt>{k}</dt>
                      <dd>{v}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}
            </div>
          )
        })}
      </div>

      {truncated ? <div className="rec-note">{truncated}</div> : null}

      {onLoadMore ? (
        <div className="rec-more">
          <button type="button" className="chip" onClick={onLoadMore} disabled={loadingMore}>
            {loadingMore ? 'Loading…' : 'Load more'}
          </button>
        </div>
      ) : null}
    </div>
  )
}

/** A value from a record's `fields` or `detail` object, rendered readably.
 *
 *  Objects and arrays are stringified — they are the leaves of an already
 *  flattened structure, and at that depth a nested definition list is harder to
 *  read than one line of JSON, not easier. */
export function Value({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span className="hint">—</span>
  if (typeof value === 'boolean') return <code>{value ? 'true' : 'false'}</code>
  if (typeof value === 'number') return <code>{value.toLocaleString()}</code>
  if (typeof value === 'string') {
    return value.includes('\n')
      ? <pre className="rec-pre">{value}</pre>
      : <span>{value}</span>
  }
  return <code className="rec-json">{JSON.stringify(value)}</code>
}
