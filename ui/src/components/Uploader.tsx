import { useEffect, useRef, useState } from 'react'
import { CheckIcon, CloseIcon, UploadIcon } from './icons'
import { uploadDocument } from '../api/gateway'
import type { UploadPolicy, UploadResult } from '../types'

/**
 * Adding documents to the library, from the rail.
 *
 * Two ways in and one path through: the picker (multiple, as the brief asks)
 * and a drop anywhere on the window. Files are then uploaded ONE AT A TIME.
 * Indexing a document costs an embedding call per paragraph, so ten parallel
 * uploads would queue behind each other on the gateway anyway — sequential
 * just makes the queue visible, and lets each file carry its own verdict
 * instead of a batch sharing one.
 *
 * Any format may be dropped here. The gateway runs Tika over anything that is
 * not already text, so a PDF, a Word file or a spreadsheet is as valid an
 * upload as a `.md` — which is why the picker has no `accept` filter: a filter
 * would refuse files the server would happily have taken. The one thing that
 * cannot be known in the browser is whether there is any TEXT inside the file;
 * a scanned page converts to nothing, and the row says so when it comes back.
 *
 * What the server accepts is not restated here: the size limit, whether
 * conversion is on, and whether this account may upload at all come from GET
 * /config. The local checks exist only to fail fast — the gateway re-runs
 * every one of them, and its answer is the one that counts.
 */

type State = 'waiting' | 'sending' | 'done' | 'failed'

interface Item {
  id: string
  name: string
  state: State
  result?: UploadResult
  error?: string
}

/** Kept short: this is a log of the last few minutes, not a history. */
const KEEP = 24

export default function Uploader({
  policy,
  onUploaded,
}: {
  policy: UploadPolicy | null
  onUploaded: () => void
}) {
  const [items, setItems] = useState<Item[]>([])
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  // The queue and the pump live in refs, not state: they are machinery, and
  // re-rendering because a queue got one shorter would be a render per file.
  const pending = useRef<{ id: string; file: File }[]>([])
  const running = useRef(false)
  const seq = useRef(0)

  // The drop listeners are bound once, so they must not close over a stale
  // `add` — one indirection through a ref keeps them on the current one.
  const addRef = useRef<(files: FileList | File[] | null) => void>(() => {})

  const maxMb = policy ? Math.round(policy.max_bytes / (1024 * 1024)) : 0
  // Empty means "any file". Only a gateway that does NOT convert gets a filter.
  const accept = policy && !policy.converts
    ? policy.text_extensions.map((e) => `.${e}`).join(',')
    : ''

  function patch(id: string, change: Partial<Item>) {
    setItems((prev) => prev.map((it) => (it.id === id ? { ...it, ...change } : it)))
  }

  /** Why this file cannot be sent, or null. Size only, unless the gateway has
   *  conversion off — the format question belongs to Tika, not to us. */
  function refuse(file: File): string | null {
    if (!policy) return 'the gateway is unreachable'
    if (file.size === 0) return 'the file is empty'
    if (file.size > policy.max_bytes) return `over the ${maxMb} MB limit`
    if (!policy.converts) {
      const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
      if (!file.name.includes('.') || !policy.text_extensions.includes(ext)) {
        return `.${ext || '?'} is not a text format Seshat indexes`
      }
    }
    return null
  }

  function add(files: FileList | File[] | null) {
    const list = Array.from(files ?? [])
    if (list.length === 0) return

    const added: Item[] = []
    for (const file of list) {
      const id = `u${seq.current++}`
      const bad = refuse(file)
      added.push({ id, name: file.name, state: bad ? 'failed' : 'waiting', error: bad ?? undefined })
      if (!bad) pending.current.push({ id, file })
    }
    setItems((prev) => [...prev, ...added].slice(-KEEP))
    void pump()
  }
  addRef.current = add

  async function pump() {
    if (running.current) return
    running.current = true
    try {
      let any = false
      for (let next = pending.current.shift(); next; next = pending.current.shift()) {
        patch(next.id, { state: 'sending' })
        try {
          const result = await uploadDocument(next.file)
          patch(next.id, { state: 'done', result })
          any = true
        } catch (e) {
          patch(next.id, { state: 'failed', error: (e as Error).message })
        }
      }
      // One refresh when the run drains, not one per file: the header counts
      // are the only thing that needs it, and mid-batch they would be stale by
      // the next file anyway.
      if (any) onUploaded()
    } finally {
      running.current = false
    }
  }

  // Drop anywhere. dragover MUST preventDefault or the browser treats the drop
  // as a navigation and replaces the app with the file — which is what happens
  // today, and is a worse answer than any upload UI.
  useEffect(() => {
    let depth = 0
    const carriesFiles = (e: DragEvent) =>
      Array.from(e.dataTransfer?.types ?? []).includes('Files')

    const enter = (e: DragEvent) => {
      if (!carriesFiles(e)) return
      depth += 1
      setDragging(true)
    }
    const over = (e: DragEvent) => {
      if (!carriesFiles(e)) return
      e.preventDefault()
    }
    const leave = (e: DragEvent) => {
      if (!carriesFiles(e)) return
      depth = Math.max(0, depth - 1)
      if (depth === 0) setDragging(false)
    }
    const drop = (e: DragEvent) => {
      if (!carriesFiles(e)) return
      e.preventDefault()
      depth = 0
      setDragging(false)
      addRef.current(e.dataTransfer?.files ?? null)
    }

    window.addEventListener('dragenter', enter)
    window.addEventListener('dragover', over)
    window.addEventListener('dragleave', leave)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragover', over)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('drop', drop)
    }
  }, [])

  // No policy yet, or an account that may only read: nothing to offer. The
  // gateway would refuse anyway, and a disabled button that never explains
  // itself is worse than no button.
  if (!policy?.allowed) return null

  return (
    <section className="library">
      <div className="rail-label">
        Library
        {items.length > 0 ? (
          <button
            type="button"
            className="clear"
            title="Clear this list"
            aria-label="Clear the upload list"
            onClick={() => setItems([])}
          >
            <CloseIcon size={11} />
          </button>
        ) : null}
      </div>

      <button
        type="button"
        className="add-docs"
        title={
          policy.converts
            ? `Any document — PDF, Word, spreadsheets, email, plain text — up to ${maxMb} MB each. ` +
              'Anything that is not already text is converted to text as it arrives.'
            : `Text files only — ${policy.text_extensions.map((e) => `.${e}`).join(' ')} — up to ${maxMb} MB each`
        }
        onClick={() => input.current?.click()}
      >
        <UploadIcon size={14} />
        Add documents
      </button>

      <input
        ref={input}
        type="file"
        multiple
        accept={accept}
        hidden
        onChange={(e) => {
          add(e.target.files)
          // Reset, or picking the same file twice in a row fires no change
          // event and the second upload silently never happens.
          e.target.value = ''
        }}
      />

      {items.length > 0 ? (
        <ul className="queue">
          {items.map((it) => (
            <li key={it.id} className={`up ${it.state}`}>
              <span className="name" title={it.name}>
                {it.name}
              </span>
              <span className="state" title={detail(it)}>
                {it.result?.converted_from ? (
                  <em className="from">{it.result.converted_from}</em>
                ) : null}
                {it.state === 'done' ? <CheckIcon size={11} /> : null}
                {label(it)}
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {dragging ? (
        <div className="drop-veil" aria-hidden="true">
          <div className="drop-card">
            <UploadIcon size={26} />
            <b>Release to add to the library</b>
            <span>
              {policy.converts
                ? `Any document — converted to text as it arrives — up to ${maxMb} MB each`
                : `${policy.text_extensions.map((e) => `.${e}`).join('  ')} — up to ${maxMb} MB each`}
            </span>
          </div>
        </div>
      ) : null}
    </section>
  )
}

/** The one word the rail has room for. */
function label(it: Item): string {
  if (it.state === 'waiting') return 'queued'
  if (it.state === 'sending') return 'sending'
  if (it.state === 'failed') return 'failed'
  const r = it.result
  if (!r) return 'done'
  if (r.status === 'indexed') return `${r.chunks} ¶`
  if (r.status === 'unchanged') return 'no change'
  if (r.status === 'queued') return 'indexing'
  return 'no text'
}

/** The whole story, on hover. */
function detail(it: Item): string {
  if (it.state === 'failed') return it.error ?? 'failed'
  const r = it.result
  if (!r) return ''
  const from = r.converted_from ? `converted from ${r.converted_from}, ` : ''
  const where = `${from}${r.replaced ? 'replaced' : 'added'} as ${r.path}`
  const cut = r.truncated ? ' (only the first part — the document was very long)' : ''
  if (r.status === 'indexed') return `${where} — ${r.chunks} chunk(s) indexed${cut}`
  if (r.status === 'unchanged') return `${where} — identical to the copy already indexed`
  if (r.status === 'queued') return `${where} — the indexer was busy; the next scan will pick it up`
  return `${where} — the file held no text to index`
}
