// Streams one chat turn from the gateway and hands its SSE events to callbacks.
//
// fetch + a ReadableStream reader rather than the browser's EventSource,
// because EventSource is GET-only and reconnects on its own: this needs a POST
// body (the prompt plus the replayed history, neither of which belongs in a
// URL) and a single turn that ends when it ends.

import { API } from '../basePath'
import { authHeaders } from '../auth/keycloak'
import { str } from '../json'
import type { Passage } from '../types'

export interface StreamHandlers {
  onToken: (text: string) => void
  onToolCall: (name: string, args: Record<string, unknown>) => void
  onToolResult: (name: string, ok: boolean, results?: Passage[]) => void
  onDone: () => void
  onError: (message: string) => void
}

export interface HistoryMessage {
  role: 'user' | 'assistant'
  content: string
}

export async function streamChat(
  prompt: string,
  history: HistoryMessage[],
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch(`${API}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(await authHeaders()) },
      body: JSON.stringify({ prompt, history }),
      signal,
    })
  } catch (e) {
    // A user-initiated stop is not a failure — the partial answer stands.
    if ((e as Error).name === 'AbortError') return
    handlers.onError(`could not reach the gateway (${(e as Error).message})`)
    return
  }

  if (!res.ok || !res.body) {
    // Refusals before the stream starts (401, 503 with no API key) carry a JSON
    // error worth showing verbatim — "GEMINI_API_KEY is not set" beats "HTTP 503".
    let message = `the gateway returned HTTP ${res.status}`
    try {
      const body = (await res.json()) as { error?: string }
      if (body?.error) message = body.error
    } catch {
      /* not JSON — keep the generic message */
    }
    handlers.onError(message)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE events are separated by a blank line. A chunk can carry any
      // fraction of an event, so only whole ones are dispatched.
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        dispatch(buffer.slice(0, sep), handlers)
        buffer = buffer.slice(sep + 2)
      }
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
    handlers.onError(`the answer stream was interrupted (${(e as Error).message})`)
  }
}

/** One SSE frame — the text between two blank lines — as its event name and
 *  decoded payload, or null if it carries no usable data.
 *
 *  Exported for its tests: this is the seam where a stream of bytes becomes
 *  events the app acts on, and a frame parsed wrongly is an answer that arrives
 *  garbled or not at all. */
export function parseFrame(raw: string): { event: string; data: Record<string, unknown> } | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith(':')) continue // comment / keep-alive
    if (line.startsWith('event:')) event = line.slice(6).trim()
    // A `data:` line may legitimately be empty, and the field separator is
    // one optional space — not "everything that looks like whitespace".
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (dataLines.length === 0) return null

  try {
    const data = JSON.parse(dataLines.join('\n')) as unknown
    // A JSON scalar is valid JSON and not an event payload; treating one as an
    // object would hand `undefined` to every handler below.
    if (typeof data !== 'object' || data === null || Array.isArray(data)) return null
    return { event, data: data as Record<string, unknown> }
  } catch {
    return null
  }
}

function dispatch(raw: string, handlers: StreamHandlers): void {
  const frame = parseFrame(raw)
  if (!frame) return
  const { event, data } = frame

  switch (event) {
    case 'token':
      handlers.onToken(str(data.text))
      break
    case 'tool_call':
      handlers.onToolCall(str(data.name), (data.args ?? {}) as Record<string, unknown>)
      break
    case 'tool_result':
      handlers.onToolResult(
        str(data.name),
        Boolean(data.ok),
        Array.isArray(data.results) ? (data.results as Passage[]) : undefined,
      )
      break
    case 'done':
      handlers.onDone()
      break
    case 'error':
      handlers.onError(str(data.message, 'stream error'))
      break
  }
}
