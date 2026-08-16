import { describe, expect, it } from 'vitest'
import { parseFrame } from './chatStream'

/**
 * The seam where bytes become events.
 *
 * The gateway writes one JSON object per `data:` line and separates frames with
 * a blank line (see Sse in Http.kt). Everything here is a shape that has
 * actually come down that wire, plus the malformed ones that must not take the
 * answer down with them.
 */
describe('parseFrame', () => {
  it('reads the frames the gateway sends', () => {
    expect(parseFrame('event: token\ndata: {"text":"Seshat"}')).toEqual({
      event: 'token',
      data: { text: 'Seshat' },
    })
    expect(parseFrame('event: done\ndata: {}')).toEqual({ event: 'done', data: {} })
    expect(parseFrame('event: error\ndata: {"message":"no key"}')).toEqual({
      event: 'error',
      data: { message: 'no key' },
    })
  })

  it('carries a tool result whole', () => {
    const frame = parseFrame(
      'event: tool_result\ndata: {"name":"search","ok":true,"results":[{"chunk_id":4}]}',
    )
    expect(frame?.event).toBe('tool_result')
    expect(frame?.data.ok).toBe(true)
    expect((frame?.data.results as unknown[]).length).toBe(1)
  })

  it('defaults to the message event when a frame names none', () => {
    expect(parseFrame('data: {"text":"x"}')?.event).toBe('message')
  })

  it('ignores comment lines, which is what a keep-alive is', () => {
    expect(parseFrame(': ping\nevent: token\ndata: {"text":"x"}')).toEqual({
      event: 'token',
      data: { text: 'x' },
    })
    expect(parseFrame(': ping')).toBeNull()
  })

  it('joins a payload split across several data lines', () => {
    expect(parseFrame('event: token\ndata: {"text":\ndata: "split"}')).toEqual({
      event: 'token',
      data: { text: 'split' },
    })
  })

  it('survives anything that is not a usable payload', () => {
    // A half-flushed or truncated frame must cost one event, never the stream.
    expect(parseFrame('event: token\ndata: {"text":')).toBeNull()
    expect(parseFrame('event: token')).toBeNull()
    expect(parseFrame('')).toBeNull()
    // Valid JSON, but not an event payload — handing a scalar on as an object
    // would give every handler `undefined`.
    expect(parseFrame('data: "just a string"')).toBeNull()
    expect(parseFrame('data: 42')).toBeNull()
    expect(parseFrame('data: [1,2]')).toBeNull()
    expect(parseFrame('data: null')).toBeNull()
  })

  it('preserves text content exactly, including whitespace inside the JSON', () => {
    // Token text is the answer itself: a parser that trims it would eat the
    // spaces between words as they stream.
    expect(parseFrame('event: token\ndata: {"text":"  two  spaces  "}')?.data.text)
      .toBe('  two  spaces  ')
    expect(parseFrame('event: token\ndata: {"text":"\\n\\n"}')?.data.text).toBe('\n\n')
  })
})
