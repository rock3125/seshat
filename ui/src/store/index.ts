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
    view: 'chat',
  }
}

// The theme has to be on <html> before the first paint, or the page flashes
// the system palette and then corrects itself.
applyTheme(store.getState().ui.theme)

// Persist on every change, at most once every SAVE_MS.
//
// A frame used to be the interval, which meant serialising every thread in the
// app up to sixty times a second for the whole length of an answer — and the
// thing being serialised GROWS with each token, so the cost climbed as the
// answer got longer. Nothing reads this file until the next page load, so the
// only thing a shorter interval buys is a smaller loss if the tab dies mid
// answer, and a quarter of a second of typing is not worth that price.
//
// The trailing write is the one that matters: whatever the interval, the last
// state always reaches disk, so an answer that finishes is always saved whole.
const SAVE_MS = 250
let timer: number | null = null

function persist(): void {
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
}

store.subscribe(() => {
  if (timer !== null) return
  timer = window.setTimeout(() => {
    timer = null
    persist()
  }, SAVE_MS)
})

// A tab closed or hidden mid-answer would otherwise lose up to SAVE_MS of it.
// `pagehide` fires on close and on a bfcache navigation, where `unload` does
// not; `visibilitychange` covers a phone being locked or the tab backgrounded.
if (typeof window !== 'undefined') {
  const flush = () => {
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
    persist()
  }
  window.addEventListener('pagehide', flush)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flush()
  })
}

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
