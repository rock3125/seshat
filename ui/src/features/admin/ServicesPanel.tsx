import { useCallback, useEffect, useState } from 'react'
import { fetchServices } from './api'
import type { TargetStatus } from './types'

/** Which parts of the stack Prometheus can currently reach. */
export default function ServicesPanel() {
  const [targets, setTargets] = useState<TargetStatus[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    setBusy(true)
    fetchServices()
      .then((r) => {
        setTargets(r.targets)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false))
  }, [])

  useEffect(() => {
    load()
    const timer = window.setInterval(load, 15_000)
    return () => window.clearInterval(timer)
  }, [load])

  const down = targets.filter((t) => !t.up)

  return (
    <div className="panel">
      <div className="filters">
        <div className="filter-row">
          <span className="hint">
            Scrape targets, as Prometheus last saw them. Refreshed every 15 seconds.
          </span>
          <div className="spacer" />
          <button type="button" className="chip" onClick={load} disabled={busy}>
            {busy ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error ? <div className="notice error">{error}</div> : null}
      {down.length > 0 ? (
        <div className="notice warn">
          {down.length} {down.length === 1 ? 'target is' : 'targets are'} down:{' '}
          {down.map((t) => t.job).join(', ')}
        </div>
      ) : null}

      <div className="panel-body">
        <div className="cards">
          {targets.map((t) => (
            <div key={`${t.job}:${t.instance}`} className={`card svc${t.up ? ' up' : ' down'}`}>
              <b>{t.job}</b>
              <code>{t.instance}</code>
              <span className={`badge ${t.up ? 'oc-ok' : 'oc-error'}`}>
                {t.up ? 'up' : 'down'}
              </span>
            </div>
          ))}
          {targets.length === 0 && !error ? (
            <p className="empty">
              Nothing is being scraped. Check that Prometheus is running:{' '}
              <code>docker compose ps prometheus</code>
            </p>
          ) : null}
        </div>

        <p className="hint" style={{ marginTop: '1rem' }}>
          A target being <em>up</em> means Prometheus reached its metrics endpoint on the
          last scrape — not that the service is healthy. <code>cadvisor</code> is the one
          most likely to be down on a host with SELinux enforcing; without it the CPU and
          memory panels are empty and everything else still works.
        </p>
      </div>
    </div>
  )
}
