/** A passage the `search` tool returned, as the gateway streams it. */
export interface Passage {
  chunk_id: number
  title: string
  path: string
  score: number
  text: string
}

/** One tool the model reached for during a turn, and how it went. */
export interface Trace {
  name: string
  /** The interesting argument, already flattened for display (the query, or the
   *  chunk id) — the UI never shows a raw JSON blob to a reader. */
  detail: string
  ok?: boolean
  resultCount?: number
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** Tool activity behind this answer. Assistant messages only. */
  trace?: Trace[]
  /** Everything retrieved during this turn — what the sources drawer shows. */
  passages?: Passage[]
  error?: string
  /** True while tokens are still arriving. */
  streaming?: boolean
}

export interface Conversation {
  id: string
  title: string
  createdAt: number
  messages: Message[]
}

/** A paragraph plus its neighbours, from GET /chunk/:id. */
export interface ChunkWindow {
  document_id: number
  title: string
  path: string
  count: number
  paragraphs: { chunk_id: number; ordinal: number; text: string }[]
}

export interface ServerConfig {
  model: string
  documents: number
  chunks: number
  library_bytes: number
  chat_enabled: boolean
}
