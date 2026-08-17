// A small, dense time series in SVG.
//
// Hand-rolled rather than a chart library: this draws one shape, it themes off
// the CSS custom properties like everything else, and it adds nothing to a
// bundle that currently ships React, Redux and react-markdown and nothing more.
// Recharts would be ~500KB for a polyline.
//
// The arithmetic lives in ../charts, where it can be tested without a DOM.

import { CHART_H, CHART_W, PAD, bounds, format, path, type Point } from '../charts'

/** Faience first — it is the interaction pigment and the eye goes to it. Gold
 *  and rubric follow, so a two-series chart never puts red beside blue for a
 *  distinction that carries no meaning. */
const STROKES = ['var(--faience)', 'var(--gold)', 'var(--rubric)', 'var(--ink-2)', 'var(--faience-2)']

export default function Sparkline({
  series, unit, height = CHART_H,
}: {
  series: { name: string; points: Point[] }[]
  unit: string
  height?: number
}) {
  const drawable = series.filter((s) => s.points.some(([, v]) => v !== null))
  if (drawable.length === 0) {
    return <p className="empty">No data in this window.</p>
  }

  const [min, max] = bounds(drawable.map((s) => s.points))
  const mid = min + (max - min) / 2

  return (
    <div className="spark">
      <svg
        viewBox={`0 0 ${CHART_W} ${height}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`${drawable.length} series`}
      >
        {/* Three gridlines and three labels. Orpiment is geometry only — a
            hairline, never type and never a fill. */}
        {[max, mid, min].map((v, i) => {
          const y = PAD.top + (i * (height - PAD.top - PAD.bottom)) / 2
          return (
            <g key={i}>
              <line x1={PAD.left} x2={CHART_W - PAD.right} y1={y} y2={y} className="grid" />
              <text x={PAD.left - 6} y={y + 3} className="axis" textAnchor="end">
                {format(v, unit)}
              </text>
            </g>
          )
        })}
        {drawable.map((s, i) => (
          <path
            key={s.name}
            d={path(s.points, min, max, height)}
            fill="none"
            stroke={STROKES[i % STROKES.length]}
            strokeWidth="1.4"
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}
      </svg>

      <ul className="legend">
        {drawable.map((s, i) => {
          const last = [...s.points].reverse().find(([, v]) => v !== null)
          return (
            <li key={s.name}>
              <i style={{ background: STROKES[i % STROKES.length] }} />
              <span>{s.name}</span>
              <b>{last ? format(last[1] as number, unit) : '—'}</b>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
