import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Passage } from '../types'

// keycloak-js reaches for a realm at import time; the token itself is not what
// is under test here.
vi.mock('../auth/keycloak', () => ({
  authHeaders: () => Promise.resolve({ Authorization: 'Bearer test-token' }),
}))

const { parseFrame, streamChat } = await import('./chatStream')

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

/**
 * The transport around that parser.
 *
 * The one thing here that cannot be seen by testing parseFrame alone: a chunk
 * off the network carries ANY fraction of a frame. Two tokens can arrive in one
 * chunk, one token can arrive as three chunks, and the boundary can fall inside
 * a JSON string. Frames are therefore only dispatched on a blank line, and the
 * remainder is carried over — which is easy to write and easy to break, and the
 * symptom is an answer that streams with words missing.
 *
 * The second is that a user-initiated stop is NOT a failure. An AbortError has
 * to leave the partial answer standing and say nothing, or every use of the Stop
 * button ends with an error message under the answer it just stopped.
 */
describe('streamChat', () => {
  /** A response whose body yields exactly these chunks, in order. */
  function body(...chunks: string[]) {
    const encoder = new TextEncoder()
    let i = 0
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: () => Promise.resolve(
            i < chunks.length
              ? { done: false, value: encoder.encode(chunks[i++]) }
              : { done: true, value: undefined },
          ),
        }),
      },
    }
  }

  /** A response that yields one chunk and then fails, as a dropped connection
   *  or an abort mid-answer does. */
  function bodyThenThrow(chunk: string, error: Error) {
    const encoder = new TextEncoder()
    let sent = false
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: () => {
            if (sent) return Promise.reject(error)
            sent = true
            return Promise.resolve({ done: false, value: encoder.encode(chunk) })
          },
        }),
      },
    }
  }

  /** Typed rather than bare `vi.fn()`, so the recorded arguments keep their
   *  types and an assertion about them is actually checked. */
  function handlers() {
    return {
      onToken: vi.fn<(text: string) => void>(),
      onToolCall: vi.fn<(name: string, args: Record<string, unknown>) => void>(),
      onToolResult: vi.fn<(name: string, ok: boolean, results?: Passage[]) => void>(),
      onDone: vi.fn<() => void>(),
      onError: vi.fn<(message: string) => void>(),
    }
  }

  const frame = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`

  let fetchMock: ReturnType<typeof vi.fn>

  function answers(response: unknown) {
    fetchMock = vi.fn(() => Promise.resolve(response))
    vi.stubGlobal('fetch', fetchMock)
  }

  function rejects(error: Error) {
    fetchMock = vi.fn(() => Promise.reject(error))
    vi.stubGlobal('fetch', fetchMock)
  }

  const abortError = () => Object.assign(new Error('aborted'), { name: 'AbortError' })

  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the prompt and the replayed history as one POST', async () => {
    answers(body(frame('done', {})))
    const history = [{ role: 'user' as const, content: 'earlier' }]

    const signal = new AbortController().signal
    await streamChat('a question', history, handlers(), signal)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/chat')
    expect(init.method).toBe('POST')
    expect(init.signal).toBe(signal)
    expect(init.headers).toMatchObject({
      'Content-Type': 'application/json',
      Authorization: 'Bearer test-token',
    })
    expect(JSON.parse(init.body as string)).toEqual({ prompt: 'a question', history })
  })

  it('dispatches every frame in a chunk, in order', async () => {
    const h = handlers()
    answers(body(
      frame('token', { text: 'The ' }) + frame('token', { text: 'library' }) + frame('done', {}),
    ))

    await streamChat('q', [], h)

    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(['The ', 'library'])
    expect(h.onDone).toHaveBeenCalledTimes(1)
  })

  it('reassembles a frame split across chunks, wherever the split falls', async () => {
    // The split here lands inside the JSON string, which is the case that a
    // per-chunk parser gets wrong while looking like it works.
    const h = handlers()
    answers(body(
      'event: token\ndata: {"text":"obst',
      'ruction of the heart"}\n',
      '\nevent: done\ndata: {}\n\n',
    ))

    await streamChat('q', [], h)

    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(['obstruction of the heart'])
    expect(h.onDone).toHaveBeenCalledTimes(1)
  })

  it('holds a frame back until its blank line arrives', async () => {
    const h = handlers()
    answers(body(frame('token', { text: 'first' }) + 'event: token\ndata: {"text":"unterminated"}'))

    await streamChat('q', [], h)

    // The complete frame is delivered; the trailing partial one is not invented.
    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(['first'])
    expect(h.onError).not.toHaveBeenCalled()
  })

  it('passes a keep-alive comment over in silence', async () => {
    // The gateway sends these to stop a proxy closing an idle turn.
    const h = handlers()
    answers(body(': keep-alive\n\n' + frame('token', { text: 'x' }) + frame('done', {})))

    await streamChat('q', [], h)

    expect(h.onToken).toHaveBeenCalledTimes(1)
    expect(h.onError).not.toHaveBeenCalled()
  })

  it('hands tool activity on with its arguments and results', async () => {
    const h = handlers()
    const results: Passage[] = [
      { chunk_id: 4, score: 0.8, title: 'Ebers', path: 'e.txt', text: 'a passage' },
    ]
    answers(body(
      frame('tool_call', { name: 'search', args: { query: 'obstruction' } }) +
      frame('tool_result', { name: 'search', ok: true, results }) +
      frame('done', {}),
    ))

    await streamChat('q', [], h)

    expect(h.onToolCall).toHaveBeenCalledWith('search', { query: 'obstruction' })
    expect(h.onToolResult).toHaveBeenCalledWith('search', true, results)
  })

  it('defaults a tool call with no arguments, and a result with no results', async () => {
    // Both fields are optional on the wire; neither may reach a handler as
    // undefined, because the store reads .length off the results.
    const h = handlers()
    answers(body(
      frame('tool_call', { name: 'search' }) +
      frame('tool_result', { name: 'search', ok: false, results: 'not an array' }) +
      frame('done', {}),
    ))

    await streamChat('q', [], h)

    expect(h.onToolCall).toHaveBeenCalledWith('search', {})
    expect(h.onToolResult).toHaveBeenCalledWith('search', false, undefined)
  })

  it('reports an error frame in the gateway words', async () => {
    const h = handlers()
    answers(body(frame('error', { message: 'the model refused to answer' })))

    await streamChat('q', [], h)

    expect(h.onError).toHaveBeenCalledWith('the model refused to answer')
  })

  it('shows the refusal a failed request carries, not its status code', async () => {
    // "GEMINI_API_KEY is not set" is actionable; "HTTP 503" is not.
    const h = handlers()
    answers({
      ok: false,
      status: 503,
      json: () => Promise.resolve({ error: 'GEMINI_API_KEY is not set on the gateway' }),
    })

    await streamChat('q', [], h)

    expect(h.onError).toHaveBeenCalledWith('GEMINI_API_KEY is not set on the gateway')
    expect(h.onDone).not.toHaveBeenCalled()
  })

  it('falls back to the status when a refusal carries no JSON', async () => {
    const h = handlers()
    answers({ ok: false, status: 502, json: () => Promise.reject(new Error('not json')) })

    await streamChat('q', [], h)

    expect(h.onError).toHaveBeenCalledWith('the gateway returned HTTP 502')
  })

  it('treats a 200 with no body as a failure rather than an empty answer', async () => {
    const h = handlers()
    answers({ ok: true, status: 200, body: null, json: () => Promise.reject(new Error('no body')) })

    await streamChat('q', [], h)

    expect(h.onError).toHaveBeenCalledTimes(1)
  })

  it('names the gateway when it cannot be reached at all', async () => {
    const h = handlers()
    rejects(new TypeError('Failed to fetch'))

    await streamChat('q', [], h)

    expect(h.onError).toHaveBeenCalledWith('could not reach the gateway (Failed to fetch)')
  })

  it('says nothing when the reader stopped the turn before it started', async () => {
    // Stop pressed while the request was still in flight.
    const h = handlers()
    rejects(abortError())

    await streamChat('q', [], h)

    expect(h.onError).not.toHaveBeenCalled()
    expect(h.onDone).not.toHaveBeenCalled()
  })

  it('says nothing when the reader stopped the turn mid-answer', async () => {
    // The partial answer stands, and no error is written under it.
    const h = handlers()
    answers(bodyThenThrow(frame('token', { text: 'half an ans' }), abortError()))

    await streamChat('q', [], h)

    expect(h.onToken).toHaveBeenCalledWith('half an ans')
    expect(h.onError).not.toHaveBeenCalled()
  })

  it('explains a stream that broke on its own', async () => {
    // A dropped connection is not a stop, and the reader has to be told the
    // answer is incomplete.
    const h = handlers()
    answers(bodyThenThrow(frame('token', { text: 'half an ans' }), new Error('network error')))

    await streamChat('q', [], h)

    expect(h.onToken).toHaveBeenCalledWith('half an ans')
    expect(h.onError).toHaveBeenCalledWith('the answer stream was interrupted (network error)')
  })
})
