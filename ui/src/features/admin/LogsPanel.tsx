import { useCallback, useEffect, useRef, useState } from 'react'
import FilterBar from './FilterBar'
import RecordTable, { Value, type Column } from './RecordTable'
import { stamp } from './format'
import { downloadCsv, fetchLogFacets, fetchLogs, tailLogs } from './api'
import { isEmptyFilter, toParams, type Filter } from './filters'
import type { LogRecord } from './types'

const MAX_TAILED = 2000

/** Every container's logs, in one place. */
export default function LogsPanel({
  filter, onFilter,
}: {
  filter: Filter
  onFilter: (next: Filter) => void
}) {
  const [records, setRecords] = useState<LogRecord[]>([])
  const [query, setQuery] = useState('')
  const [services, setServices] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [live, setLive] = useState(false)
  const [pending, setPending] = useState(0)

  const bodyRef = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)

  const load = useCallback(() => {
    setBusy(true)
    const params = toParams(filter, 'logs')
    params.set('limit', '500')
    fetchLogs(params)
      .then((page) => {
        setRecords(page.records)
        setQuery(page.query)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false))
  }, [filter])

  useEffect(() => {
    load()
  }, [load])

  // The service list is offered from what is actually shipping logs, not from a
  // list compiled into the bundle months ago.
  useEffect(() => {
    fetchLogFacets(toParams(filter, 'logs'))
      .then((f) => setServices(f.services))
      .catch(() => setServices([]))
    // Only the window matters for which services existed; refetching on every
    // keystroke in the search box would be pure noise.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter.preset, filter.from, filter.to])

  // --- the live tail --------------------------------------------------------
  //
  // Records are PREPENDED, newest first, matching the static view. The reader's
  // scroll position decides whether the view follows: auto-scrolling out from
  // under someone reading an error is the thing every log viewer gets wrong,
  // so when they have scrolled away the new records are counted instead.
  useEffect(() => {
    if (!live) return
    const controller = new AbortController()
    setError(null)
    void tailLogs(
      toParams(filter, 'logs'),
      (raw) => {
        const r = raw as LogRecord
        setRecords((prev) => {
          if (prev.some((p) => p.ts === r.ts && p.msg === r.msg && p.service === r.service)) {
            return prev
          }
          return [r, ...prev].slice(0, MAX_TAILED)
        })
        if (!pinned.current) setPending((n) => n + 1)
      },
      (message) => {
        setError(message)
        setLive(false)
      },
      controller.signal,
    )
    return () => controller.abort()
  }, [live, filter])

  function onScroll() {
    const el = bodyRef.current
    if (!el) return
    pinned.current = el.scrollTop < 24
    if (pinned.current) setPending(0)
  }

  const columns: Column<LogRecord>[] = [
    {
      key: 'ts', label: 'Time', width: '7.5rem', className: 'mono dim',
      render: (r) => stamp(r.ts),
    },
    {
      key: 'level', label: 'Level', width: '4.5rem',
      render: (r) => <span className={`badge lv-${r.level}`}>{r.level}</span>,
    },
    {
      key: 'service', label: 'Service', width: '7rem', className: 'mono',
      render: (r) => r.service,
    },
    {
      key: 'user', label: 'Who', width: '7rem', className: 'mono',
      render: (r) => r.user || <span className="hint">—</span>,
    },
    {
      key: 'msg', label: 'Message', width: 'minmax(0, 1fr)', className: 'msg',
      render: (r) => (
        <>
          {r.format === 'unstructured' ? (
            // Marked, never dressed up. cAdvisor logs through glog and nginx's
            // error log is not JSON either; pretending otherwise would mean
            // showing invented fields.
            <span className="badge raw" title="This service does not emit JSON">raw</span>
          ) : null}
          {r.msg}
        </>
      ),
    },
  ]

  return (
    <div className="panel">
      <FilterBar
        kind="logs"
        filter={filter}
        onChange={onFilter}
        services={services}
        busy={busy}
        onRefresh={load}
        onExport={() => {
          const p = toParams(filter, 'logs')
          p.set('limit', '1000')
          void downloadCsv('/admin/logs.csv', p, 'seshat-logs.csv').catch((e: Error) =>
            setError(e.message))
        }}
      >
        <button
          type="button"
          className={`chip${live ? ' on' : ''}`}
          onClick={() => {
            setLive((v) => !v)
            setPending(0)
            pinned.current = true
          }}
        >
          {live ? '● Live' : 'Live tail'}
        </button>
      </FilterBar>

      {error ? <div className="notice error">{error}</div> : null}

      {live && pending > 0 ? (
        <button
          type="button"
          className="notice pending"
          onClick={() => {
            bodyRef.current?.scrollTo({ top: 0 })
            pinned.current = true
            setPending(0)
          }}
        >
          Paused — {pending} new {pending === 1 ? 'record' : 'records'}. Click to follow again.
        </button>
      ) : null}

      <div className="panel-body" ref={bodyRef} onScroll={onScroll}>
        <RecordTable
          rows={records}
          columns={columns}
          rowKey={(r, i) => `${r.ts}:${i}`}
          accent={(r) => `lv-${r.level}`}
          detail={(r) => [
            ['time', <code key="t">{r.ts}</code>],
            ['service', <code key="s">{r.service}</code>],
            ['level', <code key="l">{r.level}</code>],
            ...(r.req
              ? [['request', (
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
            ...Object.entries(r.fields)
              .filter(([k]) => k !== 'req')
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([k, v]) => [k, <Value key={k} value={v} />] as [string, React.ReactNode]),
            ['message', <Value key="m" value={r.msg} />],
          ]}
          empty={
            <>
              <p>No log records match these filters.</p>
              {isEmptyFilter(filter) ? (
                <p className="hint">
                  Nothing was logged in this window at all. Check that Alloy is running:
                  {' '}<code>docker compose ps alloy</code>
                </p>
              ) : (
                <button
                  type="button"
                  className="chip"
                  onClick={() => onFilter({
                    ...filter, level: null, users: [], services: [], q: '', req: '',
                  })}
                >
                  Clear the filters
                </button>
              )}
            </>
          }
          truncated={records.length >= 500 && !live
            ? `Showing the newest ${records.length}. Narrow the range or the filters to see further back.`
            : undefined}
        />
      </div>

      {query ? (
        // The query is shown, always. An administrator should be able to see
        // exactly what was asked — both to trust the answer, and to reproduce
        // it in Grafana or curl later.
        <div className="panel-foot">
          <span className="hint">LogQL</span> <code className="qry">{query}</code>
        </div>
      ) : null}
    </div>
  )
}
