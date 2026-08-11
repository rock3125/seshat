// The gateway's non-streaming endpoints.

import { API } from '../basePath'
import { authHeaders } from '../auth/keycloak'
import type { ChunkWindow, ServerConfig } from '../types'

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

/** Re-embed the whole corpus from Postgres (admin only). */
export async function reindex(): Promise<void> {
  const res = await fetch(`${API}/reindex`, { method: 'POST', headers: await authHeaders() })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
}
