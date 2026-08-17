// The admin API, typed.
//
// Every call goes through the same bearer header as the rest of the app, and
// every one of them is refused server-side without the `admin` or
// `admin-observability` realm role — the checks the UI makes only decide what
// to render.

import { API } from '../../basePath'
import { authHeaders } from '../../auth/keycloak'
import { parseFrame } from '../../api/chatStream'
import { str } from '../../json'
import type {
  AuditFacets, AuditPage, LogFacets, LogPage, PanelData, PanelMeta, TargetStatus,
} from './types'

async function get<T>(path: string, params?: URLSearchParams): Promise<T> {
  const url = `${API}${path}${params && [...params].length ? `?${params}` : ''}`
  const res = await fetch(url, { headers: await authHeaders() })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    // The gateway's own message is far better than "HTTP 503" — it says which
    // upstream is missing and how to start it.
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  return (await res.json()) as T
}

export const fetchLogs = (p: URLSearchParams) => get<LogPage>('/admin/logs', p)
export const fetchLogFacets = (p: URLSearchParams) => get<LogFacets>('/admin/logs/facets', p)
export const fetchAudit = (p: URLSearchParams) => get<AuditPage>('/admin/audit', p)
export const fetchAuditFacets = (p: URLSearchParams) => get<AuditFacets>('/admin/audit/facets', p)
export const fetchPanels = () => get<{ panels: PanelMeta[] }>('/admin/metrics/panels')
export const fetchPanel = (name: string, p: URLSearchParams) => {
  const q = new URLSearchParams(p)
  q.set('panel', name)
  return get<PanelData>('/admin/metrics', q)
}
export const fetchServices = () => get<{ targets: TargetStatus[] }>('/admin/services')

/**
 * A CSV export, saved through a blob URL.
 *
 * `fetch` rather than a plain link because the endpoint needs an Authorization
 * header and an `<a href>` cannot carry one — the download would arrive as a
 * 401 body saved to disk as a .csv, which is a uniquely annoying failure.
 */
export async function downloadCsv(path: string, params: URLSearchParams, filename: string) {
  const res = await fetch(`${API}${path}?${params}`, { headers: await authHeaders() })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  // Revoked on the next tick rather than immediately: revoking synchronously
  // races the click on some browsers and produces an empty file.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * The live tail, as an async iterator over SSE records.
 *
 * `fetch` and a stream reader rather than `EventSource`, for the same reason
 * chatStream.ts does it: EventSource cannot send an Authorization header, and
 * discovering that after building the panel is a bad afternoon. `parseFrame` is
 * shared with the chat stream — one SSE parser, tested once.
 */
export async function tailLogs(
  params: URLSearchParams,
  onRecord: (r: unknown) => void,
  onError: (message: string) => void,
  signal: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch(`${API}/admin/logs/tail?${params}`, {
      headers: await authHeaders(),
      signal,
    })
  } catch (e) {
    if ((e as Error).name !== 'AbortError') onError((e as Error).message)
    return
  }
  if (!res.ok || !res.body) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    onError(body.error ?? `HTTP ${res.status}`)
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
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const frame = parseFrame(buffer.slice(0, sep))
        buffer = buffer.slice(sep + 2)
        if (!frame) continue
        if (frame.event === 'record') onRecord(frame.data)
        // `str` rather than `String(...)`: an object coerced with String()
        // becomes the literal text "[object Object]", which would be shown to
        // the reader as the reason the tail stopped. See json.ts.
        else if (frame.event === 'error') onError(str(frame.data.message, 'the tail stopped'))
      }
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') onError((e as Error).message)
  }
}
