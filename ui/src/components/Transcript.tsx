import { useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import MessageBubble from './MessageBubble'
import Mark from './Mark'
import type { Conversation, ServerConfig } from '../types'

const OPENERS = [
  { tag: 'Ask', ask: 'What does the library say about Seshat?' },
  { tag: 'Summarise', ask: 'Summarise the main points across every document.' },
  { tag: 'Locate', ask: 'Which document covers Seshat, and where in it?' },
  { tag: 'Compare', ask: 'Where do the documents disagree with each other?' },
]

export default function Transcript({
  conversation, config, onAsk,
}: {
  conversation: Conversation | null
  config: ServerConfig | null
  onAsk: (prompt: string) => void
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)

  // Follow the answer as it streams, but only while the reader is already at
  // the bottom. Scrolling up to re-read something and being yanked back down
  // by the next token is the single most irritating thing a chat UI can do.
  const onScroll = () => {
    const el = scroller.current
    if (!el) return
    pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120
  }

  // Which chunk ids this thread actually retrieved — a citation to anything
  // else is rendered dead. Built across the whole conversation, not per
  // message, because a follow-up routinely cites a source found two turns ago.
  //
  // The identity of this Set is what makes memo(MessageBubble) work. Rebuilt
  // unconditionally, as it was, it is a new object on every render, every
  // memoised bubble sees a changed prop, and a long thread re-renders in full
  // on every streamed token — which is precisely what the memo was added to
  // prevent. The key is cheap to recompute and only changes when retrieval
  // actually returns something new.
  const citationKey = (conversation?.messages ?? [])
    .flatMap((m) => m.passages?.map((p) => p.chunk_id) ?? [])
    .join(',')
  const known = useMemo(
    () => new Set(citationKey ? citationKey.split(',').map(Number) : []),
    [citationKey],
  )

  const last = conversation?.messages.at(-1)
  useLayoutEffect(() => {
    if (pinned.current) scroller.current?.scrollTo({ top: scroller.current.scrollHeight })
  }, [last?.content, last?.trace?.length, conversation?.messages.length])

  // A thread switch always lands at the bottom, wherever the last one was left.
  useEffect(() => {
    pinned.current = true
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight })
  }, [conversation?.id])

  if (!conversation || conversation.messages.length === 0) {
    return (
      <div className="transcript" ref={scroller}>
        <div className="opening">
          <Mark className="mark" draw title="The Seshat mark" />
          <h1>
            Seshat keeps the <em>record</em>.
          </h1>
          <p className="standfirst">
            {config
              ? `Ask a question and it is answered only from the ${config.documents.toLocaleString()} document${config.documents === 1 ? '' : 's'} in the library — ${config.chunks.toLocaleString()} paragraphs, each searchable by exact term and by meaning. Every claim is cited back to the paragraph it came from.`
              : 'Ask a question and it is answered only from the documents in the library. Every claim is cited back to the paragraph it came from.'}
          </p>
          <div className="openers">
            {OPENERS.map((o) => (
              <button key={o.tag} type="button" className="opener" onClick={() => onAsk(o.ask)}>
                <span className="tag">{o.tag}</span>
                <span className="ask">{o.ask}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="transcript" ref={scroller} onScroll={onScroll}>
      <div className="column">
        {conversation.messages.map((m) => (
          <MessageBubble key={m.id} message={m} knownChunks={known} />
        ))}
      </div>
    </div>
  )
}
