import { describe, expect, it } from 'vitest'
import reducer, { conversationsActions as a } from './conversationsSlice'
import type { ConversationsState } from './conversationsSlice'
import type { Passage } from '../types'

/**
 * The state machine a streamed answer drives.
 *
 * Every frame the gateway sends becomes one action here, and the order they
 * arrive in is the server's, not this app's — so the interesting cases are all
 * about frames that land somewhere unexpected: a token for a message that was
 * removed while it streamed, a tool result with no call in front of it, the same
 * chunk retrieved twice in one turn. None of those may throw, and none may
 * quietly corrupt the thread, because there is no reload that fixes it: the
 * transcript is the only copy (the server keeps the corpus, not the
 * conversation).
 */

const empty = (): ConversationsState => reducer(undefined, { type: '@@INIT' })

function passage(chunk_id: number, score: number): Passage {
  return { chunk_id, score, title: 'Ebers Papyrus', path: 'ebers.txt', text: 'a passage' }
}

/** A thread with one question asked and its answer still streaming. */
function asking(prompt = 'What does the library say?') {
  return reducer(empty(), a.started({ id: 'c1', prompt, messageId: 'm-answer' }))
}

describe('starting a thread', () => {
  it('creates the thread, selects it, and opens with the question and an empty answer', () => {
    const state = asking()

    expect(state.order).toEqual(['c1'])
    expect(state.activeId).toBe('c1')
    const messages = state.byId.c1.messages
    expect(messages).toHaveLength(2)
    expect(messages[0]).toMatchObject({ role: 'user', content: 'What does the library say?' })
    expect(messages[1]).toMatchObject({ id: 'm-answer', role: 'assistant', content: '', streaming: true })
  })

  it('titles the thread with its opening question', () => {
    expect(asking('Which document covers Seshat?').byId.c1.title).toBe('Which document covers Seshat?')
  })

  it('trims a long title rather than letting it push the rail wide', () => {
    const long = 'Summarise everything every document in this library has ever said about anything'
    const title = asking(long).byId.c1.title

    expect(title).toHaveLength(48)
    expect(title.endsWith('…')).toBe(true)
    expect(long.startsWith(title.slice(0, -1))).toBe(true)
  })

  it('takes only the first line of a pasted question', () => {
    expect(asking('First line\nsecond line\nthird').byId.c1.title).toBe('First line')
  })

  it('falls back to Untitled rather than showing a blank row', () => {
    expect(asking('   ').byId.c1.title).toBe('Untitled')
  })

  it('appends a follow-up to the existing thread without duplicating it', () => {
    const state = reducer(asking('First question'), a.started({
      id: 'c1', prompt: 'And a follow-up', messageId: 'm-answer-2',
    }))

    expect(state.order).toEqual(['c1'])
    expect(state.byId.c1.messages).toHaveLength(4)
    expect(state.byId.c1.title).toBe('First question')
  })

  it('puts the newest thread at the top of the rail', () => {
    let state = asking('Older')
    state = reducer(state, a.started({ id: 'c2', prompt: 'Newer', messageId: 'm2' }))

    expect(state.order).toEqual(['c2', 'c1'])
    expect(state.activeId).toBe('c2')
  })
})

describe('a streaming answer', () => {
  it('builds the answer from its tokens, in order', () => {
    let state = asking()
    for (const text of ['The ', 'library ', 'says…']) {
      state = reducer(state, a.token({ id: 'c1', messageId: 'm-answer', text }))
    }

    expect(state.byId.c1.messages[1].content).toBe('The library says…')
  })

  it('preserves whitespace exactly, including a token that is only a space', () => {
    // Trimming a token is how a streamed answer ends up with words run together.
    let state = asking()
    for (const text of ['one', ' ', 'two', '\n\n', 'three']) {
      state = reducer(state, a.token({ id: 'c1', messageId: 'm-answer', text }))
    }

    expect(state.byId.c1.messages[1].content).toBe('one two\n\nthree')
  })

  it('ignores a frame for a thread or message that is no longer there', () => {
    // A thread deleted mid-answer: the request is still in flight and its frames
    // keep arriving. They must land nowhere rather than resurrect it.
    const state = asking()
    const after = reducer(reducer(state,
      a.token({ id: 'gone', messageId: 'm-answer', text: 'x' })),
      a.token({ id: 'c1', messageId: 'gone', text: 'x' }))

    expect(after.byId.c1.messages[1].content).toBe('')
    expect(after.byId.gone).toBeUndefined()
  })

  it('marks the answer finished, and records a failure without losing the text so far', () => {
    let state = reducer(asking(), a.token({ id: 'c1', messageId: 'm-answer', text: 'Half an ans' }))
    state = reducer(state, a.failed({ id: 'c1', messageId: 'm-answer', error: 'the gateway went away' }))
    state = reducer(state, a.finished({ id: 'c1', messageId: 'm-answer' }))

    expect(state.byId.c1.messages[1]).toMatchObject({
      content: 'Half an ans',
      error: 'the gateway went away',
      streaming: false,
    })
  })
})

describe('tool activity', () => {
  it('pairs each result with the call it belongs to', () => {
    let state = asking()
    state = reducer(state, a.toolCall({ id: 'c1', messageId: 'm-answer', trace: { name: 'search', detail: 'obstruction' } }))
    state = reducer(state, a.toolResult({ id: 'c1', messageId: 'm-answer', ok: true, resultCount: 6 }))
    state = reducer(state, a.toolCall({ id: 'c1', messageId: 'm-answer', trace: { name: 'load_chunk', detail: '1409' } }))
    state = reducer(state, a.toolResult({ id: 'c1', messageId: 'm-answer', ok: false, resultCount: 0 }))

    expect(state.byId.c1.messages[1].trace).toEqual([
      { name: 'search', detail: 'obstruction', ok: true, resultCount: 6 },
      { name: 'load_chunk', detail: '1409', ok: false, resultCount: 0 },
    ])
  })

  it('does not overwrite a call that has already resolved', () => {
    // A stray extra result frame must not rewrite the verdict on a finished call.
    let state = asking()
    state = reducer(state, a.toolCall({ id: 'c1', messageId: 'm-answer', trace: { name: 'search', detail: 'q' } }))
    state = reducer(state, a.toolResult({ id: 'c1', messageId: 'm-answer', ok: true, resultCount: 6 }))
    state = reducer(state, a.toolResult({ id: 'c1', messageId: 'm-answer', ok: false, resultCount: 0 }))

    expect(state.byId.c1.messages[1].trace).toEqual([
      { name: 'search', detail: 'q', ok: true, resultCount: 6 },
    ])
  })

  it('survives a result with no call in front of it', () => {
    const state = reducer(asking(), a.toolResult({ id: 'c1', messageId: 'm-answer', ok: true, resultCount: 1 }))

    expect(state.byId.c1.messages[1].trace).toEqual([])
  })
})

describe('the passages behind an answer', () => {
  it('sorts them best first', () => {
    const state = reducer(asking(), a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true,
      passages: [passage(2, 0.3), passage(3, 0.9), passage(1, 0.6)],
    }))

    expect(state.byId.c1.messages[1].passages?.map((p) => p.chunk_id)).toEqual([3, 1, 2])
  })

  it('counts a chunk retrieved by two searches once, at its better score', () => {
    // Two searches in one turn routinely overlap. Two rows for one paragraph in
    // the sources drawer reads as two sources.
    let state = reducer(asking(), a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true, passages: [passage(7, 0.4)],
    }))
    state = reducer(state, a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true, passages: [passage(7, 0.8), passage(8, 0.1)],
    }))

    const passages = state.byId.c1.messages[1].passages ?? []
    expect(passages).toHaveLength(2)
    expect(passages[0]).toMatchObject({ chunk_id: 7, score: 0.8 })
    expect(passages[1].chunk_id).toBe(8)
  })

  it('keeps the better score even when the worse one arrives second', () => {
    let state = reducer(asking(), a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true, passages: [passage(7, 0.8)],
    }))
    state = reducer(state, a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true, passages: [passage(7, 0.2)],
    }))

    expect(state.byId.c1.messages[1].passages?.[0].score).toBe(0.8)
  })

  it('leaves the list alone when a result carries no passages', () => {
    let state = reducer(asking(), a.toolResult({
      id: 'c1', messageId: 'm-answer', ok: true, passages: [passage(7, 0.8)],
    }))
    state = reducer(state, a.toolResult({ id: 'c1', messageId: 'm-answer', ok: true, resultCount: 0 }))

    expect(state.byId.c1.messages[1].passages).toHaveLength(1)
  })
})

describe('the rail', () => {
  it('selects an existing thread and ignores an unknown id', () => {
    let state = asking()
    state = reducer(state, a.started({ id: 'c2', prompt: 'Newer', messageId: 'm2' }))

    expect(reducer(state, a.select('c1')).activeId).toBe('c1')
    expect(reducer(state, a.select('nope')).activeId).toBe('c2')
  })

  it('a new chat clears the selection without creating a row', () => {
    // Nothing is written until the first question, so an abandoned "new chat"
    // leaves nothing behind.
    const state = reducer(asking(), a.reset())

    expect(state.activeId).toBeNull()
    expect(state.order).toEqual(['c1'])
  })

  it('deleting the open thread falls back to the top of the rail', () => {
    let state = asking('Older')
    state = reducer(state, a.started({ id: 'c2', prompt: 'Newer', messageId: 'm2' }))
    state = reducer(state, a.remove('c2'))

    expect(state.order).toEqual(['c1'])
    expect(state.byId.c2).toBeUndefined()
    expect(state.activeId).toBe('c1')
  })

  it('deleting a thread that is not open leaves the selection alone', () => {
    let state = asking('Older')
    state = reducer(state, a.started({ id: 'c2', prompt: 'Newer', messageId: 'm2' }))
    state = reducer(state, a.remove('c1'))

    expect(state.activeId).toBe('c2')
  })

  it('deleting the last thread leaves nothing selected', () => {
    const state = reducer(asking(), a.remove('c1'))

    expect(state.order).toEqual([])
    expect(state.activeId).toBeNull()
  })
})
