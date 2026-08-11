import { configureStore } from '@reduxjs/toolkit'
import conversations, { type ConversationsState } from './conversationsSlice'
import ui, { applyTheme, type UiState } from './uiSlice'

const KEY = 'seshat-state'

interface Persisted {
  conversations?: ConversationsState
  ui?: Partial<UiState>
}

/** Rehydrate, tolerating anything: a partial write, an older shape, a user who
 *  edited it by hand. A corrupt key costs the saved threads, never a blank
 *  screen. */
function load(): Persisted | undefined {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return undefined
    const parsed = JSON.parse(raw) as Persisted
    if (!parsed.conversations?.byId) return { ui: parsed.ui }
    // A stream interrupted by a reload would otherwise reload as an answer
    // that is permanently mid-write, with a blinking caret and no stream
    // behind it.
    for (const c of Object.values(parsed.conversations.byId)) {
      for (const m of c.messages) m.streaming = false
    }
    return parsed
  } catch {
    return undefined
  }
}

const saved = load()

// Both slices are always supplied together when there is anything saved:
// configureStore types preloadedState as all-or-nothing per slice, and a
// partial object here is what makes the reducer map stop type-checking.
export const store = configureStore({
  reducer: { conversations, ui },
  preloadedState: saved
    ? {
        conversations: saved.conversations ?? emptyConversations(),
        ui: { ...emptyUi(), ...saved.ui },
      }
    : undefined,
})

function emptyConversations(): ConversationsState {
  return { order: [], byId: {}, activeId: null }
}

function emptyUi(): UiState {
  return {
    theme: 'system',
    railOpen: window.innerWidth > 960,
    drawerOpen: false,
    focusedChunk: null,
  }
}

// The theme has to be on <html> before the first paint, or the page flashes
// the system palette and then corrects itself.
applyTheme(store.getState().ui.theme)

// Persist on every change. The state is small (text, no attachments) and the
// write is debounced by a frame, so this is cheaper than reasoning about which
// actions are worth saving.
let queued = false
store.subscribe(() => {
  if (queued) return
  queued = true
  requestAnimationFrame(() => {
    queued = false
    const { conversations, ui } = store.getState()
    try {
      localStorage.setItem(KEY, JSON.stringify({
        conversations,
        // Layout state is per-window, not per-user — only the theme is worth
        // carrying across sessions.
        ui: { theme: ui.theme },
      }))
    } catch {
      // Quota exceeded on a very long history. Dropping the write keeps the
      // app running; the in-memory state is unaffected.
    }
  })
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
