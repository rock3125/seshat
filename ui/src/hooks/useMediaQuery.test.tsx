import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { NARROW, useMediaQuery } from './useMediaQuery'
import { resetViewport, setViewportWidth } from '../test/setup'

/**
 * The regression: the layout used to read `window.innerWidth <= 960` during
 * render, with nothing subscribed to changes. It was therefore only ever
 * re-read when something unrelated caused a render — so rotating a phone, or
 * dragging a window across the breakpoint, left the overlay scrim and the
 * rail's close-on-navigate believing whatever had been true at the last
 * streamed token.
 *
 * These tests fail against that implementation and pass against a subscription,
 * which is the only distinction that matters here.
 */
describe('useMediaQuery', () => {
  afterEach(resetViewport)

  function Probe() {
    const narrow = useMediaQuery(NARROW)
    return <span data-testid="state">{narrow ? 'narrow' : 'wide'}</span>
  }

  const state = () => screen.getByTestId('state').textContent

  it('reads the query on first render', () => {
    setViewportWidth(500)
    render(<Probe />)
    expect(state()).toBe('narrow')
  })

  it('starts wide on a desktop width', () => {
    setViewportWidth(1280)
    render(<Probe />)
    expect(state()).toBe('wide')
  })

  it('follows the viewport across the breakpoint, in both directions', () => {
    setViewportWidth(1280)
    render(<Probe />)
    expect(state()).toBe('wide')

    // The whole point: no re-render is triggered by anything else here, so a
    // value read during render would still say "wide".
    act(() => setViewportWidth(500))
    expect(state()).toBe('narrow')

    act(() => setViewportWidth(1280))
    expect(state()).toBe('wide')
  })

  it('sits exactly on the 960 boundary the stylesheet uses', () => {
    setViewportWidth(960)
    render(<Probe />)
    expect(state()).toBe('narrow') // max-width: 960px includes 960

    act(() => setViewportWidth(961))
    expect(state()).toBe('wide')
  })

  it('stops listening when unmounted', () => {
    setViewportWidth(1280)
    const view = render(<Probe />)
    view.unmount()
    // A listener left attached would call setState on an unmounted component.
    expect(() => act(() => setViewportWidth(500))).not.toThrow()
  })
})
