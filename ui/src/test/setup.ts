// What jsdom does not bring, and every test needs.

import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(cleanup)

/**
 * Scrolling, which jsdom has no layout to perform. The transcript pins itself
 * to the bottom of the thread on every new token, so without these it throws
 * on mount rather than testing anything.
 */
Element.prototype.scrollTo = () => {}
Element.prototype.scrollIntoView = () => {}

/**
 * `window.matchMedia`, which jsdom does not implement at all.
 *
 * Not a stub that always answers false — that would make a media-query test
 * pass whatever the code did. This is a real tiny implementation: it parses
 * `(max-width: N)` against a settable width and notifies its listeners when
 * that width changes, which is what lets a test assert that a component
 * actually re-renders on a resize rather than reading the width once.
 */
let width = 1280
const lists = new Set<{ query: string; mql: MediaQueryList; listeners: Set<(e: MediaQueryListEvent) => void> }>()

function matches(query: string): boolean {
  const max = /\(max-width:\s*(\d+)px\)/.exec(query)
  if (max) return width <= Number(max[1])
  const min = /\(min-width:\s*(\d+)px\)/.exec(query)
  if (min) return width >= Number(min[1])
  return false
}

window.matchMedia = (query: string): MediaQueryList => {
  const listeners = new Set<(e: MediaQueryListEvent) => void>()
  const mql = {
    get matches() {
      return matches(query)
    },
    media: query,
    onchange: null,
    addEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => void listeners.add(fn),
    removeEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) =>
      void listeners.delete(fn),
    addListener: (fn: (e: MediaQueryListEvent) => void) => void listeners.add(fn),
    removeListener: (fn: (e: MediaQueryListEvent) => void) => void listeners.delete(fn),
    dispatchEvent: () => true,
  } as unknown as MediaQueryList

  lists.add({ query, mql, listeners })
  return mql
}

/** Resize the notional window and notify everything listening, the way a real
 *  browser does. */
export function setViewportWidth(next: number): void {
  width = next
  for (const entry of lists) {
    for (const fn of entry.listeners) {
      fn({ matches: matches(entry.query), media: entry.query } as MediaQueryListEvent)
    }
  }
}

/** Back to a wide desktop, and forget any lists from the last test. */
export function resetViewport(): void {
  width = 1280
  lists.clear()
}
