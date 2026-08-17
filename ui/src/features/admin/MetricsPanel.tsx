import { useCallback, useEffect, useState } from 'react'
import Sparkline from '../../components/Sparkline'
import { PRESETS, resolveRange, type Filter } from './filters'
import { fetchPanel, fetchPanels } from './api'
import type { PanelData, PanelMeta } from './types'

/**
 * The named panels, as small multiples.
 *
 * The browser asks for a panel BY NAME and never composes PromQL. That is the
 * cost of not shipping Grafana — a dozen queries written server-side in
 * Panels.kt — and the benefit is that this tab cannot be turned into an
 * arbitrary PromQL console by anything the browser sends, and that the panels
 * keep working when the UI is rebuilt.
 *
 * The panel LIST comes from the gateway too, so this renders what the build
 * actually offers rather than a list compiled into the bundle months ago.
 */
export default function MetricsPanel({
  filter, onFilter,
}: {
  filter: Filter
  onFilter: (next: Filter) => void
}) {
  const [panels, setPanels] = useState<PanelMeta[]>([])
  const [data, setData] = useState<Record<string, PanelData>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    fetchPanels()
      .then((r) => setPanels(r.panels))
      .catch((e: Error) => setError(e.message))
  }, [])

  const load = useCallback(() => {
    if (panels.length === 0) return
    setBusy(true)
    const params = toRangeParams(filter)
    // One request per panel, in parallel. A panel that fails leaves the others
    // alone — a Prometheus without cAdvisor should show the eight panels it can
    // answer, not one error where eight charts belong.
    void Promise.all(panels.map((p) =>
      fetchPanel(p.name, params)
        .then((d) => [p.name, d] as const)
        .catch(() => null),
    ))
      .then((entries) => {
        const next: Record<string, PanelData> = {}
        for (const e of entries) if (e) next[e[0]] = e[1]
        setData(next)
        setError(entries.every((e) => e === null) && entries.length > 0
          ? 'No panel could be loaded — Prometheus may be unreachable.'
          : null)
      })
      .finally(() => setBusy(false))
  }, [panels, filter])

  useEffect(() => {
    load()
  }, [load])

  // Metrics are the one view where a periodic refresh is right: nobody watches
  // a chart and presses Refresh, and unlike the log tail this is four cheap
  // range queries rather than an open stream.
  useEffect(() => {
    const timer = window.setInterval(load, 30_000)
    return () => window.clearInterval(timer)
  }, [load])

  const range = resolveRange(filter)

  return (
    <div className="panel">
      <div className="filters">
        <div className="filter-row">
          <div className="seg" role="group" aria-label="Time range">
            {PRESETS.map((p) => (
              <button
                key={p.id}
                type="button"
                className={filter.preset === p.id ? 'on' : ''}
                onClick={() => onFilter({ ...filter, preset: p.id })}
              >
                {p.label}
              </button>
            ))}
          </div>
          <span className="hint mono">
            {new Date(range.from).toLocaleString()} → {new Date(range.to).toLocaleString()}
          </span>
          <div className="spacer" />
          <button type="button" className="chip" onClick={load} disabled={busy}>
            {busy ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error ? <div className="notice error">{error}</div> : null}

      <div className="panel-body">
        <div className="charts">
          {panels.map((p) => {
            const d = data[p.name]
            return (
              <figure key={p.name} className="chart">
                <figcaption>
                  <h4>{p.title}</h4>
                  {d ? <code className="qry" title={d.query}>{d.query}</code> : null}
                </figcaption>
                {d
                  ? <Sparkline series={d.series} unit={d.unit} />
                  : <p className="empty">
                      Unavailable — this panel's source is not reporting.
                    </p>}
              </figure>
            )
          })}
          {panels.length === 0 && !error ? <p className="empty">Loading panels…</p> : null}
        </div>
      </div>
    </div>
  )
}

/** Metrics take only the window — there is nothing to filter by user or
 *  severity in a time series. */
function toRangeParams(f: Filter): URLSearchParams {
  const { from, to } = resolveRange(f)
  return new URLSearchParams({ from, to })
}
