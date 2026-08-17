import { useCallback, useEffect, useState } from 'react'
import AuditPanel from './AuditPanel'
import LogsPanel from './LogsPanel'
import MetricsPanel from './MetricsPanel'
import ServicesPanel from './ServicesPanel'
import {
  DEFAULT_FILTER, TABS, decodeHash, encodeHash, type AdminTab, type Filter,
} from './filters'
import type { AdminPolicy } from '../../types'

/**
 * The Admin tab: sub-tabs, and the filter state they share.
 *
 * The whole view lives in `location.hash` — `#admin/logs?level=warn&user=rock`.
 * An administrator investigating something needs to be able to send a colleague
 * the exact view they are looking at, and to have the back button do the
 * obvious thing. That is the reason the state is here and not in Redux: it has
 * a URL, and Redux does not.
 *
 * The filter is shared ACROSS sub-tabs on purpose. Narrowing the logs to one
 * user in the last fifteen minutes and then switching to Audit should show that
 * same user in that same window — the two views answer different halves of one
 * question.
 */
export default function AdminView({ policy }: { policy: AdminPolicy }) {
  const initial = decodeHash(window.location.hash)
  const [tab, setTab] = useState<AdminTab>(initial?.tab ?? 'logs')
  const [filter, setFilter] = useState<Filter>(initial?.filter ?? DEFAULT_FILTER)

  // Which sub-tabs exist at all is the server's answer, not a guess: a
  // deployment with no Loki behind it has an administrator with a real role and
  // no log store, and hiding the tab is honest where rendering one that 503s is
  // not.
  const available = TABS.filter((t) => {
    if (t.id === 'logs') return policy.features.logs
    if (t.id === 'audit') return policy.features.audit
    return policy.features.metrics
  })

  useEffect(() => {
    if (available.length && !available.some((t) => t.id === tab)) setTab(available[0].id)
  }, [available, tab])

  // Push the view into the URL, replacing rather than pushing: typing in the
  // search box should not put thirty entries in the back stack.
  useEffect(() => {
    const next = encodeHash({ tab, filter })
    if (next !== window.location.hash) {
      window.history.replaceState(null, '', next)
    }
  }, [tab, filter])

  // …but the back button should still work when it IS a navigation.
  useEffect(() => {
    const onPop = () => {
      const loc = decodeHash(window.location.hash)
      if (!loc) return
      setTab(loc.tab)
      setFilter(loc.filter)
    }
    window.addEventListener('popstate', onPop)
    window.addEventListener('hashchange', onPop)
    return () => {
      window.removeEventListener('popstate', onPop)
      window.removeEventListener('hashchange', onPop)
    }
  }, [])

  const onFilter = useCallback((next: Filter) => setFilter(next), [])

  if (available.length === 0) {
    return (
      <div className="admin">
        <div className="empty-state">
          <p>Nothing to administer here yet.</p>
          <p className="hint">
            The audit trail is disabled and neither Loki nor Prometheus is configured on
            this deployment. Start the whole stack with <code>docker compose up -d</code>,
            and check <code>LOKI_URL</code> and <code>PROMETHEUS_URL</code> on the gateway.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="admin">
      <nav className="subtabs" aria-label="Administration">
        {available.map((t) => (
          <button
            key={t.id}
            type="button"
            className={t.id === tab ? 'on' : ''}
            onClick={() => setTab(t.id)}
            aria-current={t.id === tab ? 'page' : undefined}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {tab === 'logs' ? <LogsPanel filter={filter} onFilter={onFilter} /> : null}
      {tab === 'audit' ? <AuditPanel filter={filter} onFilter={onFilter} /> : null}
      {tab === 'metrics' ? <MetricsPanel filter={filter} onFilter={onFilter} /> : null}
      {tab === 'services' ? <ServicesPanel /> : null}
    </div>
  )
}
