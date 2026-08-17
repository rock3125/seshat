import { useCallback, useEffect, useState } from 'react'
import FilterBar from './FilterBar'
import RecordTable, { Value, type Column } from './RecordTable'
import { stamp } from './format'
import { downloadCsv, fetchAudit, fetchAuditFacets } from './api'
import { isEmptyFilter, toParams, type Filter } from './filters'
import type { AuditRecord } from './types'

/**
 * What everyone did.
 *
 * Worth being plain about, because the reader is looking at other people's
 * activity: this shows every action taken through the gateway, including the
 * searches the model ran against the shared corpus on someone's behalf. Chat
 * prompts are recorded as a hash and a length unless AUDIT_CHAT_PROMPTS is on —
 * the banner below says which, because "is anyone reading my questions" should
 * not require reading the Kotlin.
 */
export default function AuditPanel({
  filter, onFilter,
}: {
  filter: Filter
  onFilter: (next: Filter) => void
}) {
  const [records, setRecords] = useState<AuditRecord[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [users, setUsers] = useState<string[]>([])
  const [actions, setActions] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [more, setMore] = useState(false)

  const load = useCallback(() => {
    setBusy(true)
    const params = toParams(filter, 'audit')
    params.set('limit', '200')
    fetchAudit(params)
      .then((page) => {
        setRecords(page.records)
        setCursor(page.cursor)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false))
  }, [filter])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    fetchAuditFacets(toParams(filter, 'audit'))
      .then((f) => {
        setUsers(f.users)
        setActions(f.actions)
      })
      .catch(() => {
        setUsers([])
        setActions([])
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter.preset, filter.from, filter.to])

  function loadMore() {
    if (!cursor) return
    setMore(true)
    const params = toParams(filter, 'audit')
    params.set('limit', '200')
    params.set('cursor', cursor)
    fetchAudit(params)
      .then((page) => {
        setRecords((prev) => [...prev, ...page.records])
        setCursor(page.cursor)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setMore(false))
  }

  const columns: Column<AuditRecord>[] = [
    {
      key: 'ts', label: 'Time', width: '7.5rem', className: 'mono dim',
      render: (r) => stamp(r.ts),
    },
    {
      key: 'outcome', label: 'Outcome', width: '5.5rem',
      render: (r) => <span className={`badge oc-${r.outcome}`}>{r.outcome}</span>,
    },
    {
      key: 'user', label: 'Who', width: '8rem', className: 'mono',
      render: (r) => r.user,
    },
    {
      key: 'action', label: 'Action', width: '11rem', className: 'mono',
      render: (r) => r.action,
    },
    {
      key: 'target', label: 'Target', width: 'minmax(0, 1fr)', className: 'msg',
      render: (r) => r.target || <span className="hint">—</span>,
    },
    {
      key: 'duration', label: 'Took', width: '5rem', className: 'mono dim num',
      render: (r) => (r.duration_ms ? `${r.duration_ms} ms` : ''),
    },
  ]

  return (
    <div className="panel">
      <FilterBar
        kind="audit"
        filter={filter}
        onChange={onFilter}
        users={users}
        actions={actions}
        busy={busy}
        onRefresh={load}
        onExport={() => {
          void downloadCsv('/admin/audit.csv', toParams(filter, 'audit'), 'seshat-audit.csv')
            .catch((e: Error) => setError(e.message))
        }}
      />

      {error ? <div className="notice error">{error}</div> : null}

      <div className="panel-body">
        <RecordTable
          rows={records}
          columns={columns}
          rowKey={(r) => String(r.id)}
          accent={(r) => `oc-${r.outcome}`}
          detail={(r) => [
            ['time', <code key="t">{r.ts}</code>],
            ['user', <code key="u">{r.user}</code>],
            ['subject', r.subject
              ? <code key="s">{r.subject}</code>
              : <span key="s" className="hint">—</span>],
            ['session', r.session
              ? <code key="ss">{r.session}</code>
              : <span key="ss" className="hint">—</span>],
            ['status', <code key="st">{r.status || '—'}</code>],
            ['from', r.ip ? <code key="ip">{r.ip}</code> : <span key="ip" className="hint">—</span>],
            ...(r.req
              ? [['request', (
                  // The join between the two views. One id, written onto the
                  // audit row and onto every log line the same request produced.
                  <button
                    key="r"
                    type="button"
                    className="link"
                    onClick={() => onFilter({ ...filter, req: r.req })}
                  >
                    {r.req} — show everything from this request
                  </button>
                )] as [string, React.ReactNode]]
              : []),
            ...Object.entries(r.detail)
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([k, v]) => [k, <Value key={k} value={v} />] as [string, React.ReactNode]),
          ]}
          empty={
            <>
              <p>No actions match these filters.</p>
              {isEmptyFilter(filter) ? (
                <p className="hint">
                  Nothing was recorded in this window. Auditing may be off —
                  check <code>AUDIT_ENABLED</code> on the gateway.
                </p>
              ) : (
                <button
                  type="button"
                  className="chip"
                  onClick={() => onFilter({
                    ...filter, outcome: null, users: [], actions: [], q: '', req: '',
                  })}
                >
                  Clear the filters
                </button>
              )}
            </>
          }
          onLoadMore={cursor ? loadMore : undefined}
          loadingMore={more}
        />
      </div>
    </div>
  )
}
