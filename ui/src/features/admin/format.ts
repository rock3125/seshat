// How a record's fixed columns are written.

/**
 * A timestamp as local time with milliseconds.
 *
 * Milliseconds are not decoration here: a chat turn writes several log lines
 * and one audit row within the same second, and putting them in order is the
 * whole point of looking at them together.
 */
export function stamp(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return `${d.toLocaleTimeString(undefined, { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
}

/** The date, for a range that spans more than one. A column of identical dates
 *  is noise, which is why the time column does not carry it. */
export function day(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString()
}
