import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { conversationsActions } from './conversationsSlice'

/**
 * The regression: the whole conversation tree was serialised to localStorage on
 * every animation frame.
 *
 * During a stream that is up to sixty `JSON.stringify` calls a second over a
 * structure that GROWS with each token, so the cost climbed as the answer got
 * longer — and nothing reads the result until the next page load. A quarter of
 * a second of coalescing costs nothing anyone can perceive.
 *
 * The two properties worth pinning are that a burst collapses to one write, and
 * that the last state still always reaches disk. A throttle that dropped the
 * trailing write would lose the end of every answer.
 */
/** Inferred through a helper rather than annotated: `ReturnType<typeof
 *  vi.spyOn>` resolves to `any` on an unapplied generic, which silently turns
 *  off type checking for every assertion made through the spy. */
const spyOnSetItem = () => vi.spyOn(Storage.prototype, 'setItem')

describe('state persistence', () => {
  let setItem: ReturnType<typeof spyOnSetItem>
  let store: typeof import('./index').store

  beforeEach(async () => {
    vi.useFakeTimers()
    store = (await import('./index')).store
    setItem = spyOnSetItem()
  })

  afterEach(() => {
    // The store is a module singleton, so its pending timer outlives the test
    // that scheduled it. Letting it fire here resets the "a write is already
    // queued" guard; dropping it instead would leave every later test unable
    // to schedule one.
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const token = (text: string) =>
    store.dispatch(conversationsActions.token({ id: 'c1', messageId: 'm1', text }))

  it('does not write on every action', () => {
    store.dispatch(conversationsActions.started({ id: 'c1', prompt: 'hello', messageId: 'm1' }))
    for (let i = 0; i < 50; i++) token('word ')
    // Fifty dispatches, and nothing has been serialised yet.
    expect(setItem).not.toHaveBeenCalled()
  })

  it('collapses a burst into a single write', () => {
    store.dispatch(conversationsActions.started({ id: 'c2', prompt: 'hello', messageId: 'm2' }))
    for (let i = 0; i < 50; i++) token('word ')
    vi.advanceTimersByTime(250)
    expect(setItem).toHaveBeenCalledTimes(1)
  })

  it('writes the state as it stands when the interval elapses, not as it was', () => {
    store.dispatch(conversationsActions.started({ id: 'c3', prompt: 'q', messageId: 'm3' }))
    token('the answer')
    vi.advanceTimersByTime(250)

    const [key, value] = setItem.mock.calls[0]
    expect(key).toBe('seshat-state')
    // The trailing write is the one that matters: it carries the newest state,
    // so the end of an answer is never the part that is lost.
    expect(value).toContain('the answer')
  })

  it('starts a fresh interval for later changes', () => {
    store.dispatch(conversationsActions.started({ id: 'c4', prompt: 'q', messageId: 'm4' }))
    token('first')
    vi.advanceTimersByTime(250)
    expect(setItem).toHaveBeenCalledTimes(1)

    token(' second')
    vi.advanceTimersByTime(250)
    expect(setItem).toHaveBeenCalledTimes(2)
  })

  it('flushes immediately when the page goes away', () => {
    store.dispatch(conversationsActions.started({ id: 'c5', prompt: 'q', messageId: 'm5' }))
    token('mid-answer')
    expect(setItem).not.toHaveBeenCalled()

    // A tab closed mid-answer would otherwise lose up to the whole interval.
    window.dispatchEvent(new Event('pagehide'))
    expect(setItem).toHaveBeenCalledTimes(1)
    expect(setItem.mock.calls[0][1]).toContain('mid-answer')
  })

  it('does not write twice when a flush follows a pending interval', () => {
    store.dispatch(conversationsActions.started({ id: 'c6', prompt: 'q', messageId: 'm6' }))
    token('x')
    window.dispatchEvent(new Event('pagehide'))
    // The flush cancels the pending timer rather than leaving it to fire.
    vi.advanceTimersByTime(500)
    expect(setItem).toHaveBeenCalledTimes(1)
  })

  it('survives a full quota, because losing a write must not break the app', () => {
    setItem.mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    store.dispatch(conversationsActions.started({ id: 'c7', prompt: 'q', messageId: 'm7' }))
    token('x')
    expect(() => vi.advanceTimersByTime(250)).not.toThrow()
  })
})
