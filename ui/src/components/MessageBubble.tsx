import { memo, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useAppDispatch } from '../store/hooks'
import { uiActions } from '../store/uiSlice'
import type { Message } from '../types'

/**
 * One turn: the question, or the answer with its tool trace and citations.
 *
 * Citations are the whole point of the interface. The model is instructed to
 * mark every claim `[chunk:123]`; those markers are rewritten here into
 * clickable superscript references that open the source in the drawer. A
 * marker whose chunk was not among this turn's retrieved passages is rendered
 * dead (grey, not clickable) rather than hidden — a citation the model invented
 * is exactly the thing a reader needs to be able to see.
 */
function MessageBubble({ message, knownChunks }: { message: Message; knownChunks: Set<number> }) {
  const dispatch = useAppDispatch()

  if (message.role === 'user') {
    return (
      <div className="turn">
        <div className="gloss">
          <span className="who-said">Asked</span>
        </div>
        <div className="said user">{message.content}</div>
      </div>
    )
  }

  return (
    <div className="turn">
      <div className="gloss">
        <span className="who-said">Seshat</span>
        <div className="rule" />
        {message.passages?.length ? <span>{message.passages.length} sources</span> : null}
      </div>

      <div className="said assistant">
        {message.trace?.length ? (
          <div className="trace">
            {message.trace.map((t, i) => (
              <div key={i} className={`trace-line${t.ok === false ? ' bad' : ''}`}>
                <span className="verb">{t.name === 'search' ? 'Search' : 'Read'}</span>
                <span className="arg">{t.detail}</span>
                {t.resultCount !== undefined ? (
                  <span className="count">{t.resultCount}</span>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}

        {message.content ? (
          <div className="prose">
            <Markdown
              remarkPlugins={[remarkGfm]}
              components={{
                // Citations are rewritten inside text nodes, which means every
                // element that can CONTAIN text has to route through the same
                // transform — not just <p>. Missing one silently drops the
                // markers inside list items and table cells, where a lot of
                // cited facts end up.
                p: ({ children }) => <p>{cite(children, knownChunks, open)}</p>,
                li: ({ children }) => <li>{cite(children, knownChunks, open)}</li>,
                td: ({ children }) => <td>{cite(children, knownChunks, open)}</td>,
                strong: ({ children }) => <strong>{cite(children, knownChunks, open)}</strong>,
                em: ({ children }) => <em>{cite(children, knownChunks, open)}</em>,
              }}
            >
              {message.content}
            </Markdown>
          </div>
        ) : null}

        {message.streaming ? <span className="caret" /> : null}

        {message.error ? (
          <div className="failed" style={{ marginTop: message.content ? '0.75rem' : 0 }}>
            <b>Could not answer</b>
            {message.error}
          </div>
        ) : null}
      </div>
    </div>
  )

  function open(chunkId: number) {
    dispatch(uiActions.openSource(chunkId))
  }
}

/** `[chunk:123]`, `[chunk:123, 456]` and bare `[123]` — the model is asked for
 *  the first form and reliably produces the other two as well.
 *
 *  The leading `\s*` is captured so it can be DROPPED: the model writes
 *  `...document" [chunk:4].` and a superscript marker floating a word-space
 *  away from the claim it supports reads as a stray number. Footnote markers
 *  sit tight against the text. */
const CITATION = /\s*\[(?:chunk[:\s]*)?(\d+(?:\s*,\s*\d+)*)\]/g

/** Rewrite citation markers inside a rendered node's children. Only string
 *  children are touched; anything already an element (a nested <code>, a link)
 *  passes through untouched, so a chunk id inside a code span stays literal. */
function cite(children: ReactNode, known: Set<number>, open: (id: number) => void): ReactNode {
  return mapText(children, (text, key) => {
    const out: ReactNode[] = []
    let last = 0
    let m: RegExpExecArray | null
    CITATION.lastIndex = 0
    while ((m = CITATION.exec(text)) !== null) {
      if (m.index > last) out.push(text.slice(last, m.index))
      const ids = m[1].split(',').map((s) => Number(s.trim()))
      for (const id of ids) {
        const live = known.has(id)
        out.push(
          <button
            key={`${key}-${m.index}-${id}`}
            type="button"
            className={`cite${live ? '' : ' dead'}`}
            disabled={!live}
            title={live ? `Open source ${id}` : `Source ${id} was not among this turn's results`}
            onClick={live ? () => open(id) : undefined}
          >
            {id}
          </button>,
        )
      }
      last = m.index + m[0].length
    }
    if (last === 0) return text
    if (last < text.length) out.push(text.slice(last))
    return out
  })
}

/** Apply a transform to every string leaf of a React children tree. */
function mapText(
  children: ReactNode,
  transform: (text: string, key: string) => ReactNode,
): ReactNode {
  if (typeof children === 'string') return transform(children, '0')
  if (!Array.isArray(children)) return children
  return children.map((child, i) =>
    typeof child === 'string' ? (
      <span key={i}>{transform(child, String(i))}</span>
    ) : (
      child
    ),
  )
}

// Answers only grow at the tail while streaming, and a long thread re-renders
// every turn on each token without this.
export default memo(MessageBubble)
