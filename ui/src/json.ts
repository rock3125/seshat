// Reading values out of JSON that arrived over the wire.
//
// Everything the gateway sends is `unknown` until something checks it, and the
// obvious coercion is a trap: `String(v)` on an object produces the literal
// text "[object Object]", which for a token stream means that string is
// APPENDED TO THE ANSWER and shown to the reader as if the model had written
// it. A malformed field should contribute nothing, not a broken word.

/** A string field, or the fallback if it is anything else. */
export function str(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

/** A finite number field, or the fallback. NaN and Infinity are not values a
 *  score or an id may take. */
export function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}
