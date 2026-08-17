import { describe, expect, it } from 'vitest'
import { num, str } from './json'

/**
 * The guards that stand between the wire and the transcript.
 *
 * These are three lines each and look too small to test — but the thing they
 * prevent is not a crash, it is the literal text "[object Object]" being
 * APPENDED TO AN ANSWER and shown to a reader as if the model had written it.
 * A malformed field has to contribute nothing at all, and "nothing at all" is
 * exactly what an over-helpful refactor to `String(v)` or `Number(v)` would
 * take away.
 */
describe('str', () => {
  it('passes a string through, including an empty one', () => {
    expect(str('a passage')).toBe('a passage')
    expect(str('')).toBe('')
  })

  it('never coerces an object into text', () => {
    // The regression this guard exists for.
    expect(str({})).toBe('')
    expect(str({ text: 'hello' })).toBe('')
    expect(str([1, 2])).toBe('')
  })

  it('rejects every other type rather than describing it', () => {
    for (const value of [undefined, null, 42, 0, true, false, Symbol('x'), () => 'x']) {
      expect(str(value)).toBe('')
    }
  })

  it('uses the fallback the caller gave when there is one', () => {
    expect(str(undefined, 'Signed in')).toBe('Signed in')
    expect(str(null, 'Signed in')).toBe('Signed in')
    expect(str('rock', 'Signed in')).toBe('rock')
  })
})

describe('num', () => {
  it('passes a finite number through', () => {
    expect(num(0.42)).toBe(0.42)
    expect(num(-3)).toBe(-3)
  })

  it('keeps zero, which a truthiness check would have thrown away', () => {
    // A score of 0 and a chunk id of 0 are values, not absences.
    expect(num(0)).toBe(0)
    expect(num(0, 99)).toBe(0)
  })

  it('refuses NaN and both infinities', () => {
    // Neither is a value a score or an id may take, and both survive `typeof
    // v === "number"` on their own.
    expect(num(NaN)).toBe(0)
    expect(num(Infinity)).toBe(0)
    expect(num(-Infinity)).toBe(0)
  })

  it('does not parse a numeric string', () => {
    // '12' arriving where a number was promised means the gateway changed shape;
    // quietly parsing it would hide that.
    expect(num('12')).toBe(0)
    expect(num('12', -1)).toBe(-1)
  })

  it('rejects the other types', () => {
    for (const value of [undefined, null, {}, [], true]) {
      expect(num(value, -1)).toBe(-1)
    }
  })
})
