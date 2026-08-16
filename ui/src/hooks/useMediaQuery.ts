import { useEffect, useState } from 'react'

/**
 * Whether a CSS media query currently matches, kept in step with the window.
 *
 * `window.innerWidth <= 960` read during render — which is what this replaces —
 * is not wrong so much as frozen: it is only ever re-read when something
 * unrelated causes a render, so rotating a phone or dragging a window across
 * the breakpoint leaves the layout believing whatever was true at the last
 * token that happened to arrive. The overlay scrim and the rail's
 * close-on-navigate both hang off that answer.
 *
 * The query string is the same one the stylesheet uses, so there is one
 * breakpoint in the app and not two that have to be kept equal by hand.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(query).matches,
  )

  useEffect(() => {
    if (typeof window === 'undefined') return
    const list = window.matchMedia(query)
    // Re-read on subscribe: the query may have changed between the initial
    // state above and this effect running.
    setMatches(list.matches)
    const onChange = (e: MediaQueryListEvent) => setMatches(e.matches)
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [query])

  return matches
}

/** The one breakpoint the layout has, matching index.css. Below it the rail and
 *  the drawer are overlays rather than columns. */
export const NARROW = '(max-width: 960px)'
