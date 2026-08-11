/**
 * The Seshat mark: seven rays at 51.43° each, one red arc, on a cord.
 *
 * Seshat's emblem is a seven-pointed star under a downturned arc. The
 * arithmetic is kept exactly — seven rays, evenly spaced — and the bar below
 * the stem is the cord she stretches to lay out a foundation, which is also a
 * rule under a heading. One shape, both readings.
 *
 * Inline SVG rather than an <img>, so `currentColor` and var(--rubric) resolve
 * against the live theme: the mark reverses on the dark ground with no second
 * asset, and the arc stays red in both. That is the identity's one hard rule —
 * the arc is the only red stroke, and nothing else may take red unless it is a
 * heading or a number.
 */
export default function Mark({
  className = '', draw = false, title,
}: {
  className?: string
  /** Animate the strokes in, once, in the order a scribe would make them. */
  draw?: boolean
  title?: string
}) {
  return (
    <svg
      className={`${className} ${draw ? 'draw' : ''}`.trim()}
      viewBox="0 0 128 128"
      role={title ? 'img' : 'presentation'}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <g fill="none" stroke="currentColor" strokeWidth="5.5" strokeLinecap="round">
        <line className="ray" x1="64" y1="58" x2="64" y2="36" />
        <line className="ray" x1="70.25" y1="61.01" x2="87.46" y2="47.30" />
        <line className="ray" x1="71.80" y1="67.78" x2="93.25" y2="72.67" />
        <line className="ray" x1="67.47" y1="73.21" x2="77.02" y2="93.03" />
        <line className="ray" x1="60.53" y1="73.21" x2="50.98" y2="93.03" />
        <line className="ray" x1="56.20" y1="67.78" x2="34.75" y2="72.67" />
        <line className="ray" x1="57.74" y1="61.01" x2="40.54" y2="47.30" />
      </g>
      <path
        className="arc"
        d="M 24 34 C 24 -4, 104 -4, 104 34"
        fill="none"
        stroke="var(--rubric)"
        strokeWidth="5.5"
        strokeLinecap="round"
      />
      <g fill="none" stroke="currentColor" strokeWidth="5.5" strokeLinecap="round">
        <line className="cord" x1="64" y1="98" x2="64" y2="112" />
        <line className="cord" x1="42" y1="116" x2="86" y2="116" />
      </g>
    </svg>
  )
}
