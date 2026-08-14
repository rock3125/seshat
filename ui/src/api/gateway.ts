// The gateway's non-streaming endpoints.

import { API } from '../basePath'
import { authHeaders } from '../auth/keycloak'
import type { ChunkWindow, ServerConfig, UploadResult } from '../types'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API}${path}`, { headers: await authHeaders() })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  return (await res.json()) as T
}

/** What the corpus currently holds, and which model answers over it. */
export function fetchConfig(): Promise<ServerConfig> {
  return get<ServerConfig>('/config')
}

/** One paragraph with `around` neighbours each side — what a citation opens. */
export function fetchChunk(chunkId: number, around = 1): Promise<ChunkWindow> {
  return get<ChunkWindow>(`/chunk/${chunkId}?around=${around}`)
}

/**
 * Copy one file into the library folder and index it.
 *
 * One request per file, with the bytes as the body and the name in the query —
 * not a multipart batch. Several files then succeed and fail one at a time,
 * which is what the queue in the rail reports, and a slow document never holds
 * up the verdict on the ones behind it.
 */
export async function uploadDocument(file: File): Promise<UploadResult> {
  const res = await fetch(`${API}/upload?name=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    headers: { ...(await authHeaders()), 'Content-Type': 'application/octet-stream' },
    body: file,
  })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  return (await res.json()) as UploadResult
}
