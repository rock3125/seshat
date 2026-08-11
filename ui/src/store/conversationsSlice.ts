import { createSlice, nanoid, type PayloadAction } from '@reduxjs/toolkit'
import type { Conversation, Message, Passage, Trace } from '../types'

/**
 * Conversations live entirely in the browser (and in localStorage, see
 * store/index.ts). The server keeps the corpus; it does not keep transcripts.
 *
 * That is a deliberate consequence of the brief — Postgres stores the chunks —
 * and it has a pleasant property: a question someone asked is never written to
 * a disk they don't own. The cost is that a thread does not follow you to
 * another machine, and signing out clears it.
 */

export interface ConversationsState {
  order: string[]
  byId: Record<string, Conversation>
  activeId: string | null
}

const initialState: ConversationsState = { order: [], byId: {}, activeId: null }

/** A thread's name is its opening question, trimmed to something that fits the
 *  rail. Rewriting it later with a model-generated title would be another API
 *  call to say what the first line already says. */
function titleFrom(prompt: string): string {
  const line = prompt.trim().split('\n')[0]
  return line.length > 48 ? `${line.slice(0, 47)}…` : line || 'Untitled'
}

const slice = createSlice({
  name: 'conversations',
  initialState,
  reducers: {
    started(state, action: PayloadAction<{ id: string; prompt: string; messageId: string }>) {
      const { id, prompt, messageId } = action.payload
      if (!state.byId[id]) {
        state.byId[id] = { id, title: titleFrom(prompt), createdAt: Date.now(), messages: [] }
        state.order.unshift(id)
      }
      state.activeId = id
      state.byId[id].messages.push({ id: nanoid(), role: 'user', content: prompt })
      state.byId[id].messages.push({
        id: messageId, role: 'assistant', content: '', trace: [], passages: [], streaming: true,
      })
    },

    token(state, action: PayloadAction<{ id: string; messageId: string; text: string }>) {
      const m = find(state, action.payload.id, action.payload.messageId)
      if (m) m.content += action.payload.text
    },

    toolCall(state, action: PayloadAction<{ id: string; messageId: string; trace: Trace }>) {
      const m = find(state, action.payload.id, action.payload.messageId)
      if (m) (m.trace ??= []).push(action.payload.trace)
    },

    toolResult(
      state,
      action: PayloadAction<{
        id: string; messageId: string; ok: boolean; resultCount?: number; passages?: Passage[]
      }>,
    ) {
      const m = find(state, action.payload.id, action.payload.messageId)
      if (!m) return
      // The result belongs to the most recent call that has not resolved —
      // calls and results arrive strictly in order within a turn.
      const pending = [...(m.trace ?? [])].reverse().find((t) => t.ok === undefined)
      if (pending) {
        pending.ok = action.payload.ok
        pending.resultCount = action.payload.resultCount
      }
      if (action.payload.passages?.length) {
        // Same chunk retrieved by two different searches in one turn is one
        // source, not two — dedupe, keeping the better score.
        const seen = new Map((m.passages ?? []).map((p) => [p.chunk_id, p]))
        for (const p of action.payload.passages) {
          const prior = seen.get(p.chunk_id)
          if (!prior || p.score > prior.score) seen.set(p.chunk_id, p)
        }
        m.passages = [...seen.values()].sort((a, b) => b.score - a.score)
      }
    },

    failed(state, action: PayloadAction<{ id: string; messageId: string; error: string }>) {
      const m = find(state, action.payload.id, action.payload.messageId)
      if (m) m.error = action.payload.error
    },

    finished(state, action: PayloadAction<{ id: string; messageId: string }>) {
      const m = find(state, action.payload.id, action.payload.messageId)
      if (m) m.streaming = false
    },

    select(state, action: PayloadAction<string>) {
      if (state.byId[action.payload]) state.activeId = action.payload
    },

    /** Start a new thread: no row is created until the first question, so an
     *  abandoned "new chat" leaves nothing behind in the rail. */
    reset(state) {
      state.activeId = null
    },

    remove(state, action: PayloadAction<string>) {
      const id = action.payload
      delete state.byId[id]
      state.order = state.order.filter((x) => x !== id)
      if (state.activeId === id) state.activeId = state.order[0] ?? null
    },
  },
})

function find(state: ConversationsState, id: string, messageId: string): Message | undefined {
  return state.byId[id]?.messages.find((m) => m.id === messageId)
}

export const conversationsActions = slice.actions
export default slice.reducer
