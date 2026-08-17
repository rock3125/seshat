import { describe, expect, it } from 'vitest'
import { bounds, format, path, type Point } from './charts'

describe('the sparkline path', () => {
  it('draws one continuous line through real points', () => {
    const d = path([[0, 0], [10, 5], [20, 10]] as Point[], 0, 10)
    expect(d.match(/M/g)).toHaveLength(1)
    expect(d.match(/L/g)).toHaveLength(2)
  })

  it('breaks the line at a gap instead of drawing across it', () => {
    // THE reason this function is tested. Prometheus reports a scrape it did
    // not get as null, and joining the points either side draws a confident
    // line straight through an outage — the exact interval the reader opened
    // the chart to understand.
    const d = path([[0, 1], [10, 2], [20, null], [30, 4], [40, 5]] as Point[], 0, 5)
    expect(d.match(/M/g)).toHaveLength(2)
    expect(d.match(/L/g)).toHaveLength(2)
  })

  it('treats NaN and Infinity as gaps too', () => {
    const d = path([[0, 1], [10, NaN], [20, 3]] as unknown as Point[], 0, 3)
    expect(d.match(/M/g)).toHaveLength(2)
  })

  it('is empty for no points, and for points that are all gaps', () => {
    expect(path([], 0, 1)).toBe('')
    expect(path([[0, null], [1, null]] as Point[], 0, 1)).toBe('')
  })

  it('survives a single point without dividing by zero', () => {
    expect(path([[5, 3]] as Point[], 0, 10)).toMatch(/^M [\d.]+ [\d.]+$/)
  })
})

describe('the value range', () => {
  it('always includes zero', () => {
    // A chart of 4.01 to 4.02 filling its frame reads as violent change when
    // it is noise, and "is this a lot" is unanswerable without the origin.
    const [min] = bounds([[[0, 4.01], [1, 4.02]] as Point[]])
    expect(min).toBe(0)
  })

  it('leaves headroom so the peak is not welded to the top edge', () => {
    const [, max] = bounds([[[0, 0], [1, 10]] as Point[]])
    expect(max).toBeGreaterThan(10)
  })

  it('handles a flat series without collapsing to a zero-height frame', () => {
    const [min, max] = bounds([[[0, 5], [1, 5]] as Point[]])
    expect(max).toBeGreaterThan(min)
  })

  it('falls back to 0..1 when there is nothing to draw', () => {
    expect(bounds([])).toEqual([0, 1])
    expect(bounds([[[0, null]] as Point[]])).toEqual([0, 1])
  })

  it('keeps a negative minimum', () => {
    const [min] = bounds([[[0, -3], [1, 2]] as Point[]])
    expect(min).toBe(-3)
  })
})

describe('formatting a value for its unit', () => {
  it('scales bytes', () => {
    expect(format(512, 'bytes')).toBe('512 B')
    expect(format(1536, 'bytes')).toBe('1.5 KB')
    expect(format(5 * 1024 * 1024 * 1024, 'bytes')).toBe('5.0 GB')
  })

  it('drops sub-second durations into milliseconds', () => {
    expect(format(0.042, 'seconds')).toBe('42 ms')
    expect(format(2.5, 'seconds')).toBe('2.50 s')
  })

  it('keeps enough precision on a small rate to be worth showing', () => {
    // A request every few minutes is 0.004/s; two decimal places would print
    // 0.00 and the panel would look dead.
    expect(format(0.004, 'rate')).toBe('0.004')
    expect(format(12.34, 'rate')).toBe('12.3')
  })

  it('writes a percentage and a count', () => {
    expect(format(0.4237, 'percent')).toBe('42.4%')
    expect(format(3, 'count')).toBe('3')
  })

  it('never prints NaN or Infinity at a reader', () => {
    expect(format(NaN, 'bytes')).toBe('—')
    expect(format(Infinity, 'count')).toBe('—')
  })
})
