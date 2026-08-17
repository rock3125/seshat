import { afterEach, describe, expect, it, vi } from 'vitest'
import reducer, { applyTheme, uiActions as a, type UiState } from './uiSlice'

/**
 * The chrome's state, and the two invariants that are easy to lose.
 *
 * The first is that a closed sources drawer has no focused chunk: leaving one
 * behind means the next citation someone clicks opens the drawer already
 * scrolled to a paragraph they did not ask for. The second is that the Admin
 * view and the sources drawer are mutually exclusive — a panel of passages
 * beside a log viewer belongs to neither.
 */

const state = (over: Partial<UiState> = {}): UiState => ({
  ...reducer(undefined, { type: '@@INIT' }),
  ...over,
})

describe('the sources drawer', () => {
  it('opens on a citation, focused on the paragraph that was clicked', () => {
    const next = reducer(state({ drawerOpen: false }), a.openSource(1409))

    expect(next.drawerOpen).toBe(true)
    expect(next.focusedChunk).toBe(1409)
  })

  it('refocuses when it is already open', () => {
    const next = reducer(state({ drawerOpen: true, focusedChunk: 1 }), a.openSource(2))

    expect(next.focusedChunk).toBe(2)
  })

  it('forgets the focused paragraph whenever it closes', () => {
    const open = state({ drawerOpen: true, focusedChunk: 1409 })

    expect(reducer(open, a.toggleDrawer())).toMatchObject({ drawerOpen: false, focusedChunk: null })
    expect(reducer(open, a.closeDrawer())).toMatchObject({ drawerOpen: false, focusedChunk: null })
  })

  it('toggling it open does not invent a focus', () => {
    expect(reducer(state({ drawerOpen: false }), a.toggleDrawer())).toMatchObject({
      drawerOpen: true,
      focusedChunk: null,
    })
  })
})

describe('the view tabs', () => {
  it('switching to Admin puts the drawer away', () => {
    const next = reducer(state({ drawerOpen: true, focusedChunk: 7 }), a.setView('admin'))

    expect(next.view).toBe('admin')
    expect(next.drawerOpen).toBe(false)
    expect(next.focusedChunk).toBeNull()
  })

  it('switching back to chat leaves the drawer as the reader left it', () => {
    // Closed by the switch to admin, and it stays closed — reopening it is the
    // reader's decision, not a side effect of navigating.
    const next = reducer(state({ view: 'admin', drawerOpen: false }), a.setView('chat'))

    expect(next.view).toBe('chat')
    expect(next.drawerOpen).toBe(false)
  })

  it('starts on chat, because a revoked role must never reload into Admin', () => {
    expect(state().view).toBe('chat')
  })
})

describe('the theme', () => {
  afterEach(() => document.documentElement.removeAttribute('data-theme'))

  it('is stamped on the document so the stylesheet can see it', () => {
    reducer(state(), a.setTheme('dark'))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    reducer(state(), a.setTheme('light'))
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('stamps nothing at all for system, so prefers-color-scheme decides', () => {
    // Not `data-theme="system"` — the stylesheet's dark palette hangs off a
    // media query as well as the attribute, and a third attribute value would
    // match neither.
    applyTheme('dark')
    applyTheme('system')

    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('is recorded in the state as well as on the document', () => {
    expect(reducer(state(), a.setTheme('dark')).theme).toBe('dark')
  })
})

describe('the thread rail', () => {
  it('toggles', () => {
    const closed = reducer(state({ railOpen: true }), a.toggleRail())

    expect(closed.railOpen).toBe(false)
    expect(reducer(closed, a.toggleRail()).railOpen).toBe(true)
  })

  it('starts closed on a narrow screen and open on a wide one', async () => {
    // The 960 here has to agree with the breakpoint in index.css, where the rail
    // becomes an overlay. Read once at store construction, so the initial state
    // is recomputed by re-importing the module.
    for (const [innerWidth, expected] of [[800, false], [1280, true]] as const) {
      Object.defineProperty(window, 'innerWidth', { value: innerWidth, configurable: true })
      vi.resetModules()
      const fresh = (await import('./uiSlice')).default
      expect(fresh(undefined, { type: '@@INIT' }).railOpen).toBe(expected)
    }
  })
})
