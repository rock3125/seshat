import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoryMessage, StreamHandlers } from '../../api/chatStream'
import type { AppDispatch, RootState } from '../../store'
import type { Conversation, Message, Passage } from '../../types'

/**
 * One turn, from a typed question to a finished answer.
 *
 * The two invariants named in this module's own comment are the ones asserted
 * here, because both fail only in front of someone who did something reasonable:
 *
 *   the ids are decided BEFORE the stream starts, so tokens still arriving after
 *   the reader switched threads land on the thread that asked the question rather
 *   than on the one they are now looking at;
 *
 *   `finished` is dispatched after the stream returns whatever happened, because
 *   an abort and a transport error never send a `done` frame — and a message left
 *   marked `streaming` shows a caret that never stops blinking.
 *
 * The transport is mocked: what it does with a socket is chatStream's business
 * (and is tested there), and what matters here is which actions come out.
 */
const streamChat = vi.fn()
vi.mock('../../api/chatStream', () => ({ streamChat }))

const { abortTurn, sendMessage } = await import('./sendMessage')

/** The captured arguments of the stream call this turn started. */
function started() {
  const [prompt, history, handlers, signal] = streamChat.mock.calls[0] as
    [string, HistoryMessage[], StreamHandlers, AbortSignal]
  return { prompt, history, handlers, signal }
}

function message(over: Partial<Message>): Message {
  return { id: over.id ?? 'm', role: over.role ?? 'user', content: over.content ?? 'text', ...over }
}

function thread(messages: Message[]): Conversation {
  return { id: 'c1', title: 'A thread', createdAt: 0, messages }
}

/** Only the slice sendMessage reads — the rest of the store is irrelevant here
 *  and constructing it would only be a second thing to keep in step. */
function state(conversation?: Conversation): RootState {
  return {
    conversations: {
      order: conversation ? [conversation.id] : [],
      byId: conversation ? { [conversation.id]: conversation } : {},
      activeId: conversation?.id ?? null,
    },
  } as unknown as RootState
}

function turn(conversation?: Conversation) {
  const dispatch = vi.fn() as unknown as AppDispatch
  const done = sendMessage('What does the library say?', dispatch, () => state(conversation))
  return { dispatch: dispatch as unknown as ReturnType<typeof vi.fn>, done }
}

/** The action types dispatched, in order. */
function types(dispatch: ReturnType<typeof vi.fn>) {
  return dispatch.mock.calls.map((c) => (c[0] as { type: string }).type)
}

function payloads(dispatch: ReturnType<typeof vi.fn>, type: string) {
  return dispatch.mock.calls
    .map((c) => c[0] as { type: string; payload: Record<string, unknown> })
    .filter((action) => action.type === type)
    .map((action) => action.payload)
}

beforeEach(() => {
  streamChat.mockReset()
  streamChat.mockResolvedValue(undefined)
})

describe('sending', () => {
  it('says nothing at all when the composer is empty', async () => {
    // Whitespace is what a stray Enter produces. It must not open a thread, and
    // must not reach the gateway.
    const dispatch = vi.fn() as unknown as AppDispatch
    await sendMessage('   \n  ', dispatch, () => state())

    expect(streamChat).not.toHaveBeenCalled()
    expect((dispatch as unknown as ReturnType<typeof vi.fn>).mock.calls).toEqual([])
  })

  it('sends the question trimmed', async () => {
    const dispatch = vi.fn() as unknown as AppDispatch
    await sendMessage('  Which document covers Seshat?  ', dispatch, () => state())

    expect(started().prompt).toBe('Which document covers Seshat?')
  })

  it('opens the thread before the first token can arrive', async () => {
    const { dispatch, done } = turn()
    await done

    expect(types(dispatch)[0]).toBe('conversations/started')
    expect(payloads(dispatch, 'conversations/started')[0].prompt).toBe('What does the library say?')
  })

  it('marks the answer finished even when the stream sends no done frame', async () => {
    // Abort and transport failure both return without a `done`.
    const { dispatch, done } = turn()
    await done

    expect(types(dispatch)).toContain('conversations/finished')
  })
})

describe('the history that travels with a question', () => {
  it('does not replay the question being asked', async () => {
    // The window is taken before the new turn is pushed. Sending it as history
    // too would have the model answer the question twice over.
    const { done } = turn(thread([message({ id: 'm1', content: 'An earlier question' })]))
    await done

    expect(started().history).toEqual([{ role: 'user', content: 'An earlier question' }])
  })

  it('leaves out failed and still-empty messages', async () => {
    // A message that errored has no content worth replaying, and the assistant
    // message of a turn that is still streaming is empty.
    const { done } = turn(thread([
      message({ id: 'm1', content: 'A real question' }),
      message({ id: 'm2', role: 'assistant', content: 'Half an answer', error: 'the gateway went away' }),
      message({ id: 'm3', role: 'assistant', content: '   ' }),
      message({ id: 'm4', role: 'assistant', content: 'A complete answer' }),
    ]))
    await done

    expect(started().history).toEqual([
      { role: 'user', content: 'A real question' },
      { role: 'assistant', content: 'A complete answer' },
    ])
  })

  it('sends at most the last twenty messages', async () => {
    const { done } = turn(thread(
      Array.from({ length: 50 }, (_, i) => message({ id: `m${i}`, content: `message ${i}` })),
    ))
    await done

    const history = started().history
    expect(history).toHaveLength(20)
    expect(history[0].content).toBe('message 30')
    expect(history[19].content).toBe('message 49')
  })

  it('sends no history for a brand new thread', async () => {
    const { done } = turn()
    await done

    expect(started().history).toEqual([])
  })
})

describe('frames arriving from the stream', () => {
  it('all carry the ids decided when the turn began', async () => {
    // The invariant: a reader who switches threads mid-answer must not have the
    // rest of the answer written into the thread they switched to.
    const { dispatch, done } = turn(thread([]))
    await done
    const { handlers } = started()

    handlers.onToken?.('Some text')
    handlers.onToolResult?.('search', true, [])

    const conversationIds = new Set(dispatch.mock.calls
      .map((c) => (c[0] as { payload?: { id?: string } }).payload?.id))
    expect(conversationIds).toEqual(new Set(['c1']))

    const messageIds = new Set(dispatch.mock.calls
      .map((c) => (c[0] as { payload?: { messageId?: string } }).payload?.messageId)
      .filter(Boolean))
    expect(messageIds.size).toBe(1)
  })

  it('turns each frame into its action', async () => {
    const { dispatch, done } = turn()
    await done
    const { handlers } = started()
    const passages: Passage[] = [
      { chunk_id: 1, score: 0.9, title: 'Ebers', path: 'e.txt', text: 'a passage' },
    ]

    handlers.onToken?.('a token')
    handlers.onToolCall?.('search', { query: 'obstruction' })
    handlers.onToolResult?.('search', true, passages)
    handlers.onError?.('something went wrong')
    handlers.onDone?.()

    expect(types(dispatch)).toEqual([
      'conversations/started',
      'conversations/finished',       // dispatched when the mocked stream returned
      'conversations/token',
      'conversations/toolCall',
      'conversations/toolResult',
      'conversations/failed',
      'conversations/finished',
    ])
    expect(payloads(dispatch, 'conversations/toolResult')[0]).toMatchObject({
      ok: true, resultCount: 1, passages,
    })
  })

  it('shows a reader the argument that matters, per tool', async () => {
    const { dispatch, done } = turn()
    await done
    const { handlers } = started()

    handlers.onToolCall?.('search', { query: 'obstruction of the heart' })
    handlers.onToolCall?.('search', { query: 'obstruction', mode: 'keyword' })
    handlers.onToolCall?.('search', { query: 'obstruction', mode: 'hybrid' })
    handlers.onToolCall?.('load_chunk', { chunk_id: 1409 })
    handlers.onToolCall?.('load_chunk', { chunk_id: 'not a number' })
    handlers.onToolCall?.('some_new_tool', { a: 1 })
    handlers.onToolCall?.('some_new_tool', {})

    expect(payloads(dispatch, 'conversations/toolCall').map((p) => (p.trace as { detail: string }).detail))
      .toEqual([
        'obstruction of the heart',
        'obstruction (keyword)',
        'obstruction',            // the default mode is not worth saying
        'chunk 1409',
        'chunk ?',                // a malformed id is never rendered as NaN
        '{"a":1}',
        '',
      ])
  })
})

describe('stopping', () => {
  it('aborts the signal the turn is streaming on', () => {
    // A stream that never returns — which is what the Stop button is for.
    streamChat.mockReturnValue(new Promise(() => {}))
    void turn()

    expect(started().signal.aborted).toBe(false)
    abortTurn()
    expect(started().signal.aborted).toBe(true)
  })

  it('does nothing when there is no turn in flight', async () => {
    // The turn has already finished, so its controller has been let go of and
    // Stop has nothing to abort — pressing it must not throw.
    const { done } = turn()
    await done

    expect(() => abortTurn()).not.toThrow()
    expect(started().signal.aborted).toBe(false)
  })
})
