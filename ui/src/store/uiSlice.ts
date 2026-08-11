import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

export type Theme = 'system' | 'light' | 'dark'

export interface UiState {
  theme: Theme
  railOpen: boolean
  drawerOpen: boolean
  /** The chunk a citation asked to open, so the drawer can scroll to it. */
  focusedChunk: number | null
}

/** Below this the rail is an overlay and starts closed, matching the CSS
 *  breakpoint in index.css. Reading the width once at store construction is
 *  enough: the rail is a user-toggled thing after that. */
const initialState: UiState = {
  theme: 'system',
  railOpen: typeof window === 'undefined' || window.innerWidth > 960,
  drawerOpen: false,
  focusedChunk: null,
}

const slice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setTheme(state, action: PayloadAction<Theme>) {
      state.theme = action.payload
      applyTheme(action.payload)
    },
    toggleRail(state) {
      state.railOpen = !state.railOpen
    },
    toggleDrawer(state) {
      state.drawerOpen = !state.drawerOpen
      if (!state.drawerOpen) state.focusedChunk = null
    },
    openSource(state, action: PayloadAction<number>) {
      state.drawerOpen = true
      state.focusedChunk = action.payload
    },
    closeDrawer(state) {
      state.drawerOpen = false
      state.focusedChunk = null
    },
  },
})

/**
 * Stamp the choice on <html>. The three states are deliberate: an explicit
 * choice sets data-theme and wins in both directions, while 'system' stamps
 * nothing at all and lets prefers-color-scheme decide — which is why the
 * stylesheet defines its dark palette under BOTH a media query and a
 * [data-theme="dark"] selector.
 */
export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  if (theme === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', theme)
}

export const uiActions = slice.actions
export default slice.reducer
