import Mark from './Mark'
import { PlusIcon, TrashIcon } from './icons'
import { displayEmail, displayName, hasRole, signOut } from '../auth/keycloak'
import { conversationsActions } from '../store/conversationsSlice'
import { useAppDispatch, useAppSelector } from '../store/hooks'
import { reindex } from '../api/gateway'

/** The conversation rail: the wordmark, the threads, and who is signed in. */
export default function Rail({ onNavigate }: { onNavigate?: () => void }) {
  const dispatch = useAppDispatch()
  const { order, byId, activeId } = useAppSelector((s) => s.conversations)

  return (
    <aside className="rail">
      <div className="rail-head">
        <div className="lockup">
          <Mark className="glyph" title="Seshat" />
          <span className="wordmark">
            SE<span className="dot">·</span>SHAT
          </span>
        </div>
      </div>

      <button
        type="button"
        className="new-thread"
        onClick={() => {
          dispatch(conversationsActions.reset())
          onNavigate?.()
        }}
      >
        <PlusIcon size={14} />
        New thread
      </button>

      <div className="rail-label">Threads</div>
      <nav className="threads">
        {order.length === 0 ? (
          <p className="empty" style={{ padding: '0.25rem 0.5rem' }}>
            Nothing kept yet.
          </p>
        ) : (
          order.map((id) => {
            const c = byId[id]
            if (!c) return null
            return (
              <div key={id} className={`thread${id === activeId ? ' active' : ''}`}>
                <button
                  type="button"
                  className="title"
                  onClick={() => {
                    dispatch(conversationsActions.select(id))
                    onNavigate?.()
                  }}
                  title={c.title}
                  style={{ background: 'none', padding: 0, textAlign: 'left', color: 'inherit' }}
                >
                  {c.title}
                </button>
                <button
                  type="button"
                  className="kill"
                  aria-label={`Delete ${c.title}`}
                  title="Delete"
                  onClick={() => dispatch(conversationsActions.remove(id))}
                >
                  <TrashIcon size={13} />
                </button>
              </div>
            )
          })
        )}
      </nav>

      <div className="rail-foot">
        <div className="who">
          <b>{displayName()}</b>
          <span>{displayEmail()}</span>
        </div>
        <div className="rail-actions">
          <button type="button" className="chip" onClick={signOut}>
            Sign out
          </button>
          {hasRole('admin') ? (
            <button
              type="button"
              className="chip"
              title="Re-embed every paragraph from Postgres"
              onClick={() => {
                reindex().catch((e: Error) => window.alert(`Reindex failed: ${e.message}`))
              }}
            >
              Reindex
            </button>
          ) : null}
        </div>
      </div>
    </aside>
  )
}
