import { useEffect, useRef, useState } from 'react'
import { CloseIcon } from '../../components/icons'
import { PRESETS, resolveRange, type Filter } from './filters'
import { LEVELS, OUTCOMES } from './types'

/**
 * Severity · who · when · where · search. One component, both views.
 *
 * The dimensions are the same for logs and for the audit trail because the
 * questions are: what went wrong, who did it, when, and what did it touch. The
 * only difference is the first control, which is a severity floor for logs and
 * an outcome for the audit trail — the same shape, so the muscle memory
 * carries over.
 */
export default function FilterBar({
  kind,
  filter,
  onChange,
  services = [],
  users = [],
  actions = [],
  busy,
  onRefresh,
  onExport,
  children,
}: {
  kind: 'logs' | 'audit'
  filter: Filter
  onChange: (next: Filter) => void
  services?: string[]
  users?: string[]
  actions?: string[]
  busy?: boolean
  onRefresh: () => void
  onExport?: () => void
  /** Extra controls for one panel only — the live-tail toggle. */
  children?: React.ReactNode
}) {
  const set = (patch: Partial<Filter>) => onChange({ ...filter, ...patch })

  // The search box is debounced locally so typing does not fire a query per
  // keystroke, while every other control applies immediately — they are single
  // clicks, and a delay on those just feels broken.
  const [q, setQ] = useState(filter.q)
  const firstRender = useRef(true)
  useEffect(() => {
    setQ(filter.q)
  }, [filter.q])
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    if (q === filter.q) return
    const timer = window.setTimeout(() => set({ q }), 300)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q])

  const range = resolveRange(filter)

  return (
    <div className="filters">
      <div className="filter-row">
        {/* Severity. A segmented control, not a dropdown: it is the control
            used most, it has five fixed values, and one click should move it. */}
        {kind === 'logs' ? (
          <div className="seg" role="group" aria-label="Minimum severity">
            <button
              type="button"
              className={filter.level === null ? 'on' : ''}
              onClick={() => set({ level: null })}
            >
              All
            </button>
            {LEVELS.map((l) => (
              <button
                key={l}
                type="button"
                className={`lv-${l}${filter.level === l ? ' on' : ''}`}
                onClick={() => set({ level: filter.level === l ? null : l })}
                // Spelled out, because the semantics are the one thing about a
                // log viewer that is routinely got wrong.
                title={`${l} and above`}
              >
                {l}
              </button>
            ))}
          </div>
        ) : (
          <div className="seg" role="group" aria-label="Outcome">
            <button
              type="button"
              className={filter.outcome === null ? 'on' : ''}
              onClick={() => set({ outcome: null })}
            >
              All
            </button>
            {OUTCOMES.map((o) => (
              <button
                key={o}
                type="button"
                className={`oc-${o}${filter.outcome === o ? ' on' : ''}`}
                onClick={() => set({ outcome: filter.outcome === o ? null : o })}
              >
                {o}
              </button>
            ))}
          </div>
        )}

        {filter.level !== null && kind === 'logs' ? (
          <span className="hint">{filter.level} and above</span>
        ) : null}

        <div className="spacer" />

        {children}

        <button type="button" className="chip" onClick={onRefresh} disabled={busy}>
          {busy ? 'Loading…' : 'Refresh'}
        </button>
        {onExport ? (
          <button type="button" className="chip" onClick={onExport}>
            Export CSV
          </button>
        ) : null}
      </div>

      <div className="filter-row">
        {/* When. Presets resolve to absolute instants in the browser; the
            resolved window is printed below so "last 1 hour" is never the only
            thing a reader has to go on. */}
        <div className="seg" role="group" aria-label="Time range">
          {PRESETS.map((p) => (
            <button
              key={p.id}
              type="button"
              className={filter.preset === p.id ? 'on' : ''}
              onClick={() => set({ preset: p.id })}
            >
              {p.label}
            </button>
          ))}
          <button
            type="button"
            className={filter.preset === 'custom' ? 'on' : ''}
            onClick={() => set({ preset: 'custom' })}
          >
            Custom
          </button>
        </div>

        {filter.preset === 'custom' ? (
          <>
            <input
              type="datetime-local"
              className="field"
              value={filter.from}
              onChange={(e) => set({ from: e.target.value })}
              aria-label="From"
            />
            <input
              type="datetime-local"
              className="field"
              value={filter.to}
              onChange={(e) => set({ to: e.target.value })}
              aria-label="To"
            />
          </>
        ) : null}

        <span className="hint mono">
          {new Date(range.from).toLocaleString()} → {new Date(range.to).toLocaleString()}
        </span>
      </div>

      <div className="filter-row">
        <Chips
          label="Who"
          placeholder="username"
          values={filter.users}
          options={users}
          onChange={(users) => set({ users })}
        />
        {kind === 'logs' ? (
          <Chips
            label="Service"
            placeholder="service"
            values={filter.services}
            options={services}
            onChange={(services) => set({ services })}
          />
        ) : (
          <Chips
            label="Action"
            placeholder="action"
            values={filter.actions}
            options={actions}
            onChange={(actions) => set({ actions })}
          />
        )}

        <input
          className="field grow"
          type="search"
          placeholder={kind === 'logs' ? 'Search the line…' : 'Search target and detail…'}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label="Search"
        />
      </div>

      {filter.req ? (
        <div className="filter-row">
          <span className="pin">
            <span className="hint">Pinned to request</span>
            <code>{filter.req}</code>
            <button
              type="button"
              className="kill"
              aria-label="Clear the request filter"
              onClick={() => set({ req: '' })}
            >
              <CloseIcon size={12} />
            </button>
          </span>
        </div>
      ) : null}
    </div>
  )
}

/**
 * A multi-select as chips, with the known values offered and free text allowed.
 *
 * Free text matters: the options come from what is actually present in the
 * window (Loki's label values, the audit trail's distinct users), so a user who
 * has done nothing in the last hour is not in the list — and "prove that
 * so-and-so did nothing" is a question this view has to be able to answer.
 */
function Chips({
  label, placeholder, values, options, onChange,
}: {
  label: string
  placeholder: string
  values: string[]
  options: string[]
  onChange: (next: string[]) => void
}) {
  const [draft, setDraft] = useState('')
  const listId = `opts-${label.toLowerCase()}`

  function add(value: string) {
    const v = value.trim()
    if (v && !values.includes(v)) onChange([...values, v])
    setDraft('')
  }

  return (
    <div className="chips">
      <span className="chips-label">{label}</span>
      {values.map((v) => (
        <span key={v} className="chip on">
          {v}
          <button
            type="button"
            className="kill"
            aria-label={`Remove ${v}`}
            onClick={() => onChange(values.filter((x) => x !== v))}
          >
            <CloseIcon size={11} />
          </button>
        </span>
      ))}
      <input
        className="field slim"
        list={listId}
        placeholder={placeholder}
        value={draft}
        aria-label={`Add ${label}`}
        onChange={(e) => {
          // Picking from the datalist fires change, not keydown — so a click on
          // an option has to commit it too, or the list looks broken.
          const v = e.target.value
          if (options.includes(v)) add(v)
          else setDraft(v)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            add(draft)
          } else if (e.key === 'Backspace' && !draft && values.length) {
            onChange(values.slice(0, -1))
          }
        }}
      />
      <datalist id={listId}>
        {options.filter((o) => !values.includes(o)).map((o) => (
          <option key={o} value={o} />
        ))}
      </datalist>
    </div>
  )
}
