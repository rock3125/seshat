import Mark from './Mark'
import Uploader from './Uploader'
import { PlusIcon, TrashIcon } from './icons'
import { displayEmail, displayName, signOut } from '../auth/keycloak'
import { conversationsActions } from '../store/conversationsSlice'
import { useAppDispatch, useAppSelector } from '../store/hooks'
import type { UploadPolicy } from '../types'

/** The conversation rail: the wordmark, the threads, the library, and who is
 *  signed in. */
export default function Rail({
  onNavigate,
  uploads,
  onUploaded,
}: {
  onNavigate?: () => void
  /** Null until GET /config lands, and for accounts that may not upload. */
  uploads: UploadPolicy | null
  onUploaded: () => void
}) {
  const dispatch = useAppDispatch()
  const { order, byId, activeId } = useAppSelector((s) => s.conversations)

  return (
    <aside className="rail">
      <div className="rail-head">
        {/* The title sits on the lockup rather than on either half, so the
            mark and the wordmark show one tooltip between them — they are one
            thing. The SVG's own `title` prop stays what it was: an aria-label,
            which a tooltip is not a substitute for. */}
        <div className="lockup" title="Seshat · Mistress of the House of Books">
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

      <Uploader policy={uploads} onUploaded={onUploaded} />

      <div className="rail-foot">
        <div className="who">
          <b>{displayName()}</b>
          <span>{displayEmail()}</span>
        </div>
        <div className="rail-actions">
          <button type="button" className="chip" onClick={signOut}>
            Sign out
          </button>
        </div>
      </div>
    </aside>
  )
}
