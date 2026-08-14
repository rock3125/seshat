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
  /** How often the gateway rescans the library folder for added and deleted
   *  files. Shown to the reader, so a file dropped in by hand has a stated
   *  arrival time rather than an unexplained wait. */
  scan_minutes: number
  upload: UploadPolicy
}

/** What this signed-in caller may add to the library, decided server-side. */
export interface UploadPolicy {
  allowed: boolean
  max_bytes: number
  /** True when the gateway converts non-text formats (Tika) — which means the
   *  file picker must NOT filter by extension. */
  converts: boolean
  /** Bare extensions, no dot, stored as they arrive: 'md', 'txt', … Everything
   *  else is converted to text on upload. */
  text_extensions: string[]
}

/** What became of one uploaded file, as POST /upload reports it. */
export interface UploadResult {
  /** The name as uploaded — 'report.pdf'. */
  source: string
  /** The name it is stored and indexed under — 'report.pdf.txt' when converted. */
  path: string
  bytes: number
  replaced: boolean
  /** 'indexed' | 'unchanged' | 'no text to index' | 'queued' */
  status: string
  /** Chunks the document now holds, or null when indexing was deferred to the
   *  next scan because the indexer was busy. */
  chunks: number | null
  /** The format it was converted from ('PDF', 'Word', …), or null if it was
   *  already text. */
  converted_from: string | null
  /** True when the document was longer than EXTRACT_MAX_CHARS and only its
   *  first part was indexed. */
  truncated: boolean
  documents: number
  total_chunks: number
}
