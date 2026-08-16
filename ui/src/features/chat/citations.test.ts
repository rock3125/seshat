import { describe, expect, it } from 'vitest'
import { parseCitations } from './citations'

/**
 * What counts as a citation.
 *
 * This is the load-bearing rule of the interface: a marker missed renders a
 * cited claim as if it were uncited, and a false positive turns a number the
 * author wrote into a footnote that leads nowhere. The model is asked for
 * `[chunk:N]` and reliably produces two other shapes as well, so all three are
 * pinned here.
 */
describe('parseCitations', () => {
  const ids = (text: string) =>
    parseCitations(text).filter((p) => p.kind === 'cite').map((p) => (p as { id: number }).id)

  const text = (text: string) =>
    parseCitations(text).filter((p) => p.kind === 'text').map((p) => (p as { text: string }).text)

  it('reads the form the model is asked for', () => {
    expect(ids('The papyrus lists it [chunk:42].')).toEqual([42])
  })

  it('reads the two forms the model also produces', () => {
    expect(ids('Both agree [chunk:42, 43].')).toEqual([42, 43])
    expect(ids('Bare marker [42].')).toEqual([42])
    expect(ids('Spaced [chunk 42].')).toEqual([42])
  })

  it('drops the space before a marker so it sits tight against the claim', () => {
    // '...record" [chunk:4].' — a superscript floating a word-space away from
    // the sentence it supports reads as a stray number, not a footnote.
    expect(text('the record [chunk:4].')).toEqual(['the record', '.'])
  })

  it('keeps the text around and between markers', () => {
    const pieces = parseCitations('Before [chunk:1] middle [chunk:2] after')
    expect(pieces).toEqual([
      { kind: 'text', text: 'Before' },
      { kind: 'cite', id: 1 },
      { kind: 'text', text: ' middle' },
      { kind: 'cite', id: 2 },
      { kind: 'text', text: ' after' },
    ])
  })

  it('returns text with no markers as a single untouched piece', () => {
    const plain = 'The library does not answer that.'
    expect(parseCitations(plain)).toEqual([{ kind: 'text', text: plain }])
  })

  it('leaves things that are not citations alone', () => {
    expect(ids('An array literal [a, b] and an empty one []')).toEqual([])
    expect(ids('A range [1-2] is not a citation')).toEqual([])
    expect(ids('')).toEqual([])
  })

  it('handles a marker at the very start and the very end', () => {
    expect(ids('[chunk:7] opens the answer')).toEqual([7])
    expect(ids('and closes it [chunk:9]')).toEqual([9])
  })

  it('is not confused by the previous call (a module-level /g regex)', () => {
    // The regex carries lastIndex between calls; forgetting to reset it makes
    // every second call start matching from wherever the last one stopped.
    expect(ids('first [chunk:1]')).toEqual([1])
    expect(ids('second [chunk:2]')).toEqual([2])
    expect(ids('third [chunk:3]')).toEqual([3])
  })

  it('reads a long citation list', () => {
    expect(ids('Several [chunk:1, 2, 3, 4, 5].')).toEqual([1, 2, 3, 4, 5])
  })
})
