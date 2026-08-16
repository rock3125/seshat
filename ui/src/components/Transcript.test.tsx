import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Conversation, Message, Passage } from '../types'

/**
 * The regression: `knownChunks` was rebuilt as a fresh `Set` on every render.
 *
 * `MessageBubble` is wrapped in `memo` specifically so that a long thread does
 * not re-render in full on every streamed token — but a new Set is a new
 * object, every memoised bubble saw a changed prop, and the memo never once
 * held. The cost grew with the length of the thread and with the length of the
 * answer, which is the worst possible shape for it.
 *
 * What is asserted is the thing that was broken: the IDENTITY of the prop
 * across renders. React's memo is React's to get right; keeping its input
 * stable is this component's job.
 */

// Replace the bubble with something that records the props it was handed. This
// is the seam the memo sits on, so it is the seam worth watching.
const seen: { knownChunks: Set<number>; message: Message }[] = []
vi.mock('./MessageBubble', () => ({
  default: (props: { message: Message; knownChunks: Set<number> }) => {
    seen.push(props)
    return <div data-testid="bubble">{props.message.content}</div>
  },
}))

const { default: Transcript } = await import('./Transcript')

const passage = (chunk_id: number, score = 0.9): Passage => ({
  chunk_id, score, title: 'Ebers Papyrus', path: 'ebers.txt', text: 'a passage',
})

function thread(answer: string, passages: Passage[]): Conversation {
  return {
    id: 'c1',
    title: 'A question',
    createdAt: 0,
    messages: [
      { id: 'm1', role: 'user', content: 'What does the library say?' },
      { id: 'm2', role: 'assistant', content: answer, passages, streaming: true },
    ],
  }
}

describe('Transcript', () => {
  beforeEach(() => {
    seen.length = 0
  })

  it('hands every bubble the same knownChunks set while only tokens arrive', () => {
    const passages = [passage(4), passage(7)]
    const view = render(
      <Transcript conversation={thread('The record', passages)} config={null} onAsk={() => {}} />,
    )
    const first = seen.at(-1)!.knownChunks

    // Three more tokens land. Retrieval returned nothing new, so the set of
    // citable chunks cannot have changed — and neither should its identity.
    for (const answer of ['The record s', 'The record sa', 'The record says']) {
      view.rerender(
        <Transcript conversation={thread(answer, passages)} config={null} onAsk={() => {}} />,
      )
      expect(seen.at(-1)!.knownChunks).toBe(first)
    }
  })

  it('gives a new set when retrieval actually returns something new', () => {
    const view = render(
      <Transcript conversation={thread('answer', [passage(4)])} config={null} onAsk={() => {}} />,
    )
    const before = seen.at(-1)!.knownChunks
    expect(before.has(4)).toBe(true)
    expect(before.has(9)).toBe(false)

    view.rerender(
      <Transcript
        conversation={thread('answer', [passage(4), passage(9)])}
        config={null}
        onAsk={() => {}}
      />,
    )
    const after = seen.at(-1)!.knownChunks
    expect(after).not.toBe(before)
    expect(after.has(9)).toBe(true)
  })

  it('collects citable chunks across the whole thread, not just the last turn', () => {
    // A follow-up routinely cites a source found two turns ago; a citation to
    // it must not render dead.
    const conversation: Conversation = {
      id: 'c1', title: 't', createdAt: 0,
      messages: [
        { id: 'm1', role: 'user', content: 'first' },
        { id: 'm2', role: 'assistant', content: 'a', passages: [passage(1)] },
        { id: 'm3', role: 'user', content: 'second' },
        { id: 'm4', role: 'assistant', content: 'b', passages: [passage(2)] },
      ],
    }
    render(<Transcript conversation={conversation} config={null} onAsk={() => {}} />)
    const known = seen.at(-1)!.knownChunks
    expect([...known].sort()).toEqual([1, 2])
  })

  it('shows the openers when there is no conversation yet', () => {
    const view = render(<Transcript conversation={null} config={null} onAsk={() => {}} />)
    expect(view.queryAllByTestId('bubble')).toHaveLength(0)
    expect(view.getByText(/Seshat keeps the/)).toBeDefined()
  })
})
