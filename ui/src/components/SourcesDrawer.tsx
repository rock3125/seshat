import { useEffect, useRef, useState } from 'react'
import { CloseIcon } from './icons'
import { fetchChunk } from '../api/gateway'
import { uiActions } from '../store/uiSlice'
import { useAppDispatch } from '../store/hooks'
import type { ChunkWindow, Passage } from '../types'

/**
 * What retrieval actually returned for this thread, with its scores.
 *
 * The drawer shows the retrieved preview by default and expands to the full
 * paragraph plus its neighbours on demand (GET /chunk/:id), so checking a
 * citation never means leaving the answer. The score is displayed because a
 * reader deciding whether to trust a claim is better served by "this was the
 * fifth-best match" than by a list that implies all five were equally good.
 */
export default function SourcesDrawer({
  passages, focused,
}: {
  passages: Passage[]
  /** A chunk id a citation asked to open — scrolled to and expanded. */
  focused: number | null
}) {
  const dispatch = useAppDispatch()
  const [expanded, setExpanded] = useState<Record<number, ChunkWindow | 'loading' | 'error'>>({})
  const items = useRef(new Map<number, HTMLDivElement>())

  async function expand(chunkId: number) {
    if (expanded[chunkId]) return
    setExpanded((e) => ({ ...e, [chunkId]: 'loading' }))
    try {
      const win = await fetchChunk(chunkId, 1)
      setExpanded((e) => ({ ...e, [chunkId]: win }))
    } catch {
      setExpanded((e) => ({ ...e, [chunkId]: 'error' }))
    }
  }

  // A citation click both scrolls the passage into view and opens it — the
  // reader asked to see that source, not to be shown where it is in a list.
  useEffect(() => {
    if (focused === null) return
    items.current.get(focused)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    void expand(focused)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focused])

  return (
    <aside className="drawer">
      <div className="drawer-head">
        <h2>Sources</h2>
        <button
          type="button"
          className="icon-button"
          onClick={() => dispatch(uiActions.closeDrawer())}
          aria-label="Close sources"
        >
          <CloseIcon size={16} />
        </button>
      </div>

      <div className="drawer-body">
        {passages.length === 0 ? (
          <p className="empty">
            Nothing retrieved yet. Ask a question and the paragraphs the answer was
            built from appear here, best match first.
          </p>
        ) : (
          passages.map((p) => {
            const state = expanded[p.chunk_id]
            const window = typeof state === 'object' ? state : null
            return (
              <div
                key={p.chunk_id}
                className="passage"
                ref={(el) => {
                  if (el) items.current.set(p.chunk_id, el)
                  else items.current.delete(p.chunk_id)
                }}
              >
                <div className="hd">
                  <span className="id">{p.chunk_id}</span>
                  <span className="doc" title={p.title}>{p.title}</span>
                  <span className="score" title="Retrieval score">{p.score.toFixed(3)}</span>
                </div>
                <div className="path">{p.path}</div>

                {window ? (
                  <div className="body full">
                    {window.paragraphs.map((para) => (
                      <p
                        key={para.chunk_id}
                        style={{
                          marginBottom: '0.6rem',
                          // The neighbours are context, not the citation —
                          // dim them so the cited paragraph stays the subject.
                          color: para.chunk_id === p.chunk_id ? 'var(--ink)' : 'var(--ink-3)',
                        }}
                      >
                        {para.text}
                      </p>
                    ))}
                  </div>
                ) : (
                  <div className="body">{p.text}</div>
                )}

                {state === 'loading' ? (
                  <span className="more" style={{ color: 'var(--ink-3)' }}>Loading…</span>
                ) : state === 'error' ? (
                  <span className="more" style={{ color: 'var(--rubric)' }}>Could not load</span>
                ) : !window ? (
                  <button type="button" className="more" onClick={() => expand(p.chunk_id)}>
                    Read in context
                  </button>
                ) : null}
              </div>
            )
          })
        )}
      </div>
    </aside>
  )
}
