import { afterEach, describe, expect, it, vi } from 'vitest'
import { conversationsActions } from './conversationsSlice'
import { uiActions } from './uiSlice'

/**
 * Coming back to a tab that was closed.
 *
 * Conversations live only in the browser — the server keeps the corpus, not the
 * transcripts — so localStorage is the only copy there is, and this is the code
 * that decides whether a returning reader finds their threads or an empty rail.
 * The stated promise is that a corrupt key costs the saved threads and never a
 * blank screen, which means every one of these has to be tolerated: a truncated
 * write, an older shape, a hand-edited payload, a browser that refuses to hand
 * the key over at all.
 *
 * The store is a module singleton whose hydration happens at import, so each
 * case seeds localStorage and re-imports it.
 */

const KEY = 'seshat-state'

async function boot(saved?: unknown) {
  localStorage.clear()
  if (saved !== undefined) {
    localStorage.setItem(KEY, typeof saved === 'string' ? saved : JSON.stringify(saved))
  }
  vi.resetModules()
  return (await import('./index')).store
}

/** A saved payload of the shape the app actually writes. */
function savedThread(over: Record<string, unknown> = {}) {
  return {
    conversations: {
      order: ['c1'],
      activeId: 'c1',
      byId: {
        c1: {
          id: 'c1',
          title: 'An earlier question',
          createdAt: 1,
          messages: [
            { id: 'm1', role: 'user', content: 'An earlier question' },
            { id: 'm2', role: 'assistant', content: 'An earlier answer', ...over },
          ],
        },
      },
    },
    ui: { theme: 'dark' },
  }
}

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  vi.restoreAllMocks()
})

describe('a first visit', () => {
  it('starts empty, on the chat view, with the system theme', async () => {
    const state = (await boot()).getState()

    expect(state.conversations).toEqual({ order: [], byId: {}, activeId: null })
    expect(state.ui.theme).toBe('system')
    expect(state.ui.view).toBe('chat')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })
})

describe('coming back', () => {
  it('restores the threads and which one was open', async () => {
    const state = (await boot(savedThread())).getState()

    expect(state.conversations.order).toEqual(['c1'])
    expect(state.conversations.activeId).toBe('c1')
    expect(state.conversations.byId.c1.messages).toHaveLength(2)
    expect(state.conversations.byId.c1.title).toBe('An earlier question')
  })

  it('restores an answer that was mid-stream as a finished one', async () => {
    // Otherwise the thread reloads with a blinking caret and no stream behind
    // it — an answer permanently half-written, waiting for tokens that can
    // never arrive because the request died with the old page.
    const state = (await boot(savedThread({ streaming: true }))).getState()

    expect(state.conversations.byId.c1.messages[1].streaming).toBe(false)
  })

  it('applies the saved theme before the first paint', async () => {
    // Stamped at import rather than in an effect: an effect runs after the first
    // paint, which is a visible flash of the system palette on every load.
    await boot(savedThread())

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})

describe('a payload that cannot be trusted', () => {
  it('survives a truncated write', async () => {
    // What a tab killed mid-write leaves behind.
    const state = (await boot('{"conversations":{"byId":{"c1":')).getState()

    expect(state.conversations.byId).toEqual({})
    expect(state.ui.theme).toBe('system')
  })

  it('survives an empty or nonsense value', async () => {
    for (const raw of ['', 'null', 'undefined', '[]', '"a string"', '42']) {
      const state = (await boot(raw)).getState()
      expect(state.conversations.order, raw).toEqual([])
    }
  })

  it('keeps the theme when the conversations are the unreadable part', async () => {
    // An older shape, or a partial write: the threads are gone, but there is no
    // reason to also throw away the one setting that was readable.
    const state = (await boot({ conversations: { order: ['c1'] }, ui: { theme: 'light' } })).getState()

    expect(state.conversations.order).toEqual([])
    expect(state.ui.theme).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('survives a browser that refuses to hand the key over', async () => {
    // Safari in private mode, and any policy that makes storage throw on read
    // rather than return null.
    localStorage.setItem(KEY, JSON.stringify(savedThread()))
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    vi.resetModules()

    const state = (await import('./index')).store.getState()
    expect(state.conversations.order).toEqual([])
  })

  it('ignores a message list that is not one', async () => {
    // The loop that clears `streaming` walks every message of every thread, so a
    // byId entry with no messages array is the shape that would throw during
    // hydration — i.e. before anything is rendered at all.
    const state = (await boot({ conversations: { byId: { c1: { id: 'c1' } } } })).getState()

    expect(state.conversations.order).toEqual([])
  })
})

describe('what is written back', () => {
  it('carries the threads and nothing of the layout but the theme', async () => {
    // Layout is per-window, not per-user. Persisting `view` in particular would
    // mean an account whose admin role was revoked still reloads into a tab it
    // can no longer use.
    vi.useFakeTimers()
    try {
      const store = await boot()
      const setItem = vi.spyOn(Storage.prototype, 'setItem')

      store.dispatch(conversationsActions.started({ id: 'c9', prompt: 'a question', messageId: 'm9' }))
      store.dispatch(uiActions.setTheme('dark'))
      store.dispatch(uiActions.toggleRail())
      store.dispatch(uiActions.openSource(1409))
      store.dispatch(uiActions.setView('admin'))
      vi.advanceTimersByTime(250)

      const written = JSON.parse(setItem.mock.calls.at(-1)![1])
      expect(Object.keys(written).sort()).toEqual(['conversations', 'ui'])
      expect(written.ui).toEqual({ theme: 'dark' })
      expect(written.conversations.byId.c9.title).toBe('a question')
    } finally {
      vi.runOnlyPendingTimers()
      vi.useRealTimers()
    }
  })

  it('round-trips a thread through a reload', async () => {
    // The whole point, end to end: write, reload, and the thread is there and
    // the view is back to chat.
    vi.useFakeTimers()
    let payload: string
    try {
      const store = await boot()
      const setItem = vi.spyOn(Storage.prototype, 'setItem')
      store.dispatch(conversationsActions.started({ id: 'c8', prompt: 'What does it say?', messageId: 'm8' }))
      store.dispatch(conversationsActions.token({ id: 'c8', messageId: 'm8', text: 'It says this.' }))
      store.dispatch(uiActions.setView('admin'))
      vi.advanceTimersByTime(250)
      payload = setItem.mock.calls.at(-1)![1]
    } finally {
      vi.runOnlyPendingTimers()
      vi.useRealTimers()
      vi.restoreAllMocks()
    }

    const reloaded = (await boot(payload)).getState()
    expect(reloaded.conversations.byId.c8.messages[1].content).toBe('It says this.')
    expect(reloaded.conversations.byId.c8.messages[1].streaming).toBe(false)
    expect(reloaded.ui.view).toBe('chat')
  })
})
