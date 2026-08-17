// The arithmetic behind a sparkline: the path, the value range, and how a
// number is written for a given unit.
//
// Separated from the component because these are the parts with rules worth
// stating, and none of them needs a DOM to say what those rules are. Sparkline
// .tsx draws what these return.

/** `[unixSeconds, value]`, where a null value is a gap Prometheus reported. */
export type Point = [number, number | null]

export const CHART_W = 560
export const CHART_H = 120
export const PAD = { top: 8, right: 6, bottom: 16, left: 44 }

/**
 * The polyline, as an SVG path.
 *
 * GAPS ARE BREAKS, NOT STRAIGHT LINES. Prometheus reports a scrape it did not
 * get as a null, and joining the points either side of one draws a confident
 * line straight across the outage — which is exactly the interval a reader is
 * looking at the chart to understand. Each run of real points therefore starts
 * a new subpath with `M` rather than continuing with `L`.
 */
export function path(points: readonly Point[], min: number, max: number, height = CHART_H): string {
  if (points.length === 0) return ''
  const t0 = points[0][0]
  const t1 = points[points.length - 1][0]
  const span = t1 - t0 || 1
  const range = max - min || 1

  const x = (t: number) => PAD.left + ((t - t0) / span) * (CHART_W - PAD.left - PAD.right)
  const y = (v: number) => height - PAD.bottom - ((v - min) / range) * (height - PAD.top - PAD.bottom)

  let d = ''
  let drawing = false
  for (const [t, v] of points) {
    if (v === null || !Number.isFinite(v)) {
      drawing = false
      continue
    }
    d += `${drawing ? 'L' : 'M'} ${x(t).toFixed(1)} ${y(v).toFixed(1)} `
    drawing = true
  }
  return d.trim()
}

/**
 * The value range to draw against.
 *
 * Always includes zero: a chart of 4.01 to 4.02 that fills its frame reads as
 * violent change when it is noise, and the question a reader brings to a
 * metrics panel is almost always "is this a lot", which is meaningless without
 * the origin.
 */
export function bounds(series: readonly (readonly Point[])[]): [number, number] {
  let min = Infinity
  let max = -Infinity
  for (const points of series) {
    for (const [, v] of points) {
      if (v === null || !Number.isFinite(v)) continue
      if (v < min) min = v
      if (v > max) max = v
    }
  }
  if (!Number.isFinite(min)) return [0, 1]
  min = Math.min(0, min)
  if (max === min) max = min + 1
  // A tenth of headroom, so the peak is not welded to the top edge.
  return [min, max + (max - min) * 0.1]
}

/** A value written the way its unit is read. */
export function format(value: number, unit: string): string {
  if (!Number.isFinite(value)) return '—'
  switch (unit) {
    case 'bytes': {
      const units = ['B', 'KB', 'MB', 'GB', 'TB']
      let v = value
      let i = 0
      while (Math.abs(v) >= 1024 && i < units.length - 1) {
        v /= 1024
        i++
      }
      return `${v.toFixed(Math.abs(v) < 10 && i > 0 ? 1 : 0)} ${units[i]}`
    }
    case 'seconds':
      return value < 1 ? `${(value * 1000).toFixed(0)} ms` : `${value.toFixed(2)} s`
    case 'percent':
      return `${(value * 100).toFixed(1)}%`
    case 'rate':
      return value < 1 ? value.toFixed(3) : value.toFixed(1)
    default:
      return value >= 1000
        ? value.toLocaleString(undefined, { maximumFractionDigits: 0 })
        : String(Math.round(value * 100) / 100)
  }
}
