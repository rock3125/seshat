import { nanoid } from '@reduxjs/toolkit'
import { streamChat, type HistoryMessage } from '../../api/chatStream'
import { conversationsActions } from '../../store/conversationsSlice'
import type { AppDispatch, RootState } from '../../store'
import type { Passage } from '../../types'

/** How many prior messages travel with a question. Each one is replayed to the
 *  model verbatim, so this trades context against tokens; 20 covers a normal
 *  working thread without turning every question into a full transcript. */
const HISTORY_WINDOW = 20

/** The in-flight turn, so the composer's Stop button has something to abort. */
let inFlight: AbortController | null = null

export function abortTurn(): void {
  inFlight?.abort()
  inFlight = null
}

/**
 * Send one question and stream the answer into the store.
 *
 * The conversation id is decided here rather than in the reducer so the stream
 * handlers, which fire long after dispatch, all write to the same thread even
 * if the user switches threads mid-answer. Same for the assistant message id:
 * every event carries it, so a late token can never land on the wrong message.
 */
export async function sendMessage(
  prompt: string,
  dispatch: AppDispatch,
  getState: () => RootState,
): Promise<void> {
  const trimmed = prompt.trim()
  if (!trimmed) return

  const state = getState()
  const conversationId = state.conversations.activeId ?? nanoid()
  const messageId = nanoid()

  // The window is taken BEFORE the new turn is pushed, so the prompt being
  // sent isn't also replayed as history.
  const history: HistoryMessage[] = (state.conversations.byId[conversationId]?.messages ?? [])
    .filter((m) => m.content.trim() && !m.error)
    .slice(-HISTORY_WINDOW)
    .map((m) => ({ role: m.role, content: m.content }))

  dispatch(conversationsActions.started({ id: conversationId, prompt: trimmed, messageId }))

  const controller = new AbortController()
  inFlight = controller

  await streamChat(
    trimmed,
    history,
    {
      onToken: (text) =>
        dispatch(conversationsActions.token({ id: conversationId, messageId, text })),

      onToolCall: (name, args) =>
        dispatch(conversationsActions.toolCall({
          id: conversationId, messageId, trace: { name, detail: describe(name, args) },
        })),

      onToolResult: (_name, ok, results?: Passage[]) =>
        dispatch(conversationsActions.toolResult({
          id: conversationId, messageId, ok, resultCount: results?.length, passages: results,
        })),

      onError: (error) =>
        dispatch(conversationsActions.failed({ id: conversationId, messageId, error })),

      onDone: () =>
        dispatch(conversationsActions.finished({ id: conversationId, messageId })),
    },
    controller.signal,
  )

  // Also on abort and on a transport error, neither of which sends `done`.
  dispatch(conversationsActions.finished({ id: conversationId, messageId }))
  if (inFlight === controller) inFlight = null
}

/** The one argument worth showing a reader, per tool. */
function describe(name: string, args: Record<string, unknown>): string {
  if (name === 'search') {
    const mode = args.mode && args.mode !== 'hybrid' ? ` (${String(args.mode)})` : ''
    return `${String(args.query ?? '')}${mode}`
  }
  if (name === 'load_chunk') return `chunk ${String(args.chunk_id ?? '?')}`
  return Object.keys(args).length ? JSON.stringify(args) : ''
}
