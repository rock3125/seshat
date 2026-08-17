import { describe, expect, it } from 'vitest'
import { day, stamp } from './format'

/**
 * The time column of every admin table.
 *
 * Asserted by shape rather than by literal value: these functions format in the
 * reader's locale and time zone on purpose, so a test that expected "20:35:19"
 * would pass in Auckland and fail in CI. What must hold everywhere is the
 * 24-hour, millisecond-precision shape — a chat turn writes several log lines
 * and an audit row inside the same second, and putting those in order is the
 * entire reason for looking at them together.
 */
describe('stamp', () => {
  it('writes 24-hour time with milliseconds', () => {
    expect(stamp('2026-08-17T09:35:19.007Z')).toMatch(/^\d{1,2}:\d{2}:\d{2}\.\d{3}$/)
  })

  it('pads the milliseconds, so the column stays aligned and sorts', () => {
    // 7ms as "7" would both misalign the column and sort after 700.
    expect(stamp('2026-08-17T09:35:19.007Z')).toMatch(/\.007$/)
    expect(stamp('2026-08-17T09:35:19.070Z')).toMatch(/\.070$/)
    expect(stamp('2026-08-17T09:35:19.700Z')).toMatch(/\.700$/)
    expect(stamp('2026-08-17T09:35:19Z')).toMatch(/\.000$/)
  })

  it('distinguishes two records inside the same second', () => {
    // The whole point of the milliseconds.
    expect(stamp('2026-08-17T09:35:19.001Z')).not.toBe(stamp('2026-08-17T09:35:19.002Z'))
  })

  it('never shows am or pm', () => {
    for (const iso of ['2026-08-17T01:05:00.000Z', '2026-08-17T13:05:00.000Z', '2026-08-17T23:59:59.999Z']) {
      expect(stamp(iso).toLowerCase()).not.toMatch(/[ap]m/)
    }
  })

  it('shows an unparseable timestamp as it arrived rather than as Invalid Date', () => {
    // Whatever the gateway sent is more informative to whoever is debugging than
    // the string the Date constructor would have produced.
    expect(stamp('not a timestamp')).toBe('not a timestamp')
    expect(stamp('')).toBe('')
  })
})

describe('day', () => {
  it('writes a date for a real timestamp', () => {
    expect(day('2026-08-17T09:35:19.007Z')).toMatch(/\d/)
  })

  it('is empty for an unparseable one, so the column stays blank rather than broken', () => {
    expect(day('not a timestamp')).toBe('')
    expect(day('')).toBe('')
  })
})
