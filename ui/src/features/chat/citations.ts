// Finding the citation markers in an answer.
//
// Split out of MessageBubble so the RULE is separable from the rendering: what
// counts as a citation is the load-bearing part of this interface — a marker
// missed is a claim that looks uncited, and a false positive turns a number the
// author wrote into a footnote that goes nowhere — and none of that needs React
// to state or to test.

/** A run of ordinary text, or one citation marker resolved to a chunk id. */
export type Piece =
  | { kind: 'text'; text: string }
  | { kind: 'cite'; id: number }

/** `[chunk:123]`, `[chunk:123, 456]` and bare `[123]` — the model is asked for
 *  the first form and reliably produces the other two as well.
 *
 *  The leading `\s*` is captured so it can be DROPPED: the model writes
 *  `...document" [chunk:4].` and a superscript marker floating a word-space
 *  away from the claim it supports reads as a stray number. Footnote markers
 *  sit tight against the text. */
const CITATION = /\s*\[(?:chunk[:\s]*)?(\d+(?:\s*,\s*\d+)*)\]/g

/**
 * One string into its text runs and its citations, in order.
 *
 * A string with no markers comes back as a single text piece, which is what
 * lets the caller skip rebuilding a node it would only have rebuilt identically.
 */
export function parseCitations(text: string): Piece[] {
  const out: Piece[] = []
  let last = 0
  let m: RegExpExecArray | null

  // The regex is module-level and /g, so it carries lastIndex between calls.
  CITATION.lastIndex = 0
  while ((m = CITATION.exec(text)) !== null) {
    if (m.index > last) out.push({ kind: 'text', text: text.slice(last, m.index) })
    for (const part of m[1].split(',')) {
      const id = Number(part.trim())
      if (Number.isFinite(id)) out.push({ kind: 'cite', id })
    }
    last = m.index + m[0].length
  }

  if (out.length === 0) return [{ kind: 'text', text }]
  if (last < text.length) out.push({ kind: 'text', text: text.slice(last) })
  return out
}
