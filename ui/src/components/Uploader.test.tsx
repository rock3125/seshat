import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import type { UploadPolicy, UploadResult } from '../types'

/**
 * Adding documents from the rail.
 *
 * Three behaviours here are worth a test each, and all three are the kind that
 * only misbehave in front of a user:
 *
 *   sequential  Files upload ONE AT A TIME. Ten parallel uploads would queue on
 *               the gateway's index lock anyway, and each file has to carry its
 *               own verdict rather than a batch sharing one.
 *   fail fast   A refusal decided in the browser (empty, over the limit) must
 *               never be sent, and must say which rule it broke — while the
 *               gateway's own answer is still the one that counts for anything
 *               it cannot know, such as whether there is text inside a PDF.
 *   dragover    The listener MUST preventDefault, or the browser treats the drop
 *               as a navigation and replaces the whole app with the file.
 */
vi.mock('../api/gateway', () => ({ uploadDocument: vi.fn() }))

const { uploadDocument } = await import('../api/gateway')
const upload = uploadDocument as Mock
const { default: Uploader } = await import('./Uploader')

const POLICY: UploadPolicy = {
  allowed: true,
  max_bytes: 25 * 1024 * 1024,
  converts: true,
  text_extensions: ['md', 'txt'],
}

function verdict(over: Partial<UploadResult> = {}): UploadResult {
  return {
    source: 'notes.txt', path: 'notes.txt', bytes: 12, replaced: false,
    status: 'indexed', chunks: 4, converted_from: null, truncated: false,
    documents: 3, total_chunks: 40, ...over,
  }
}

function file(name: string, { size, content = 'a document' }: { size?: number; content?: string } = {}) {
  const f = new File([content], name)
  // Faking the size rather than allocating 30 MB of test data.
  if (size !== undefined) Object.defineProperty(f, 'size', { value: size })
  return f
}

function show(policy: UploadPolicy | null = POLICY) {
  const onUploaded = vi.fn()
  const view = render(<Uploader policy={policy} onUploaded={onUploaded} />)
  const input = view.container.querySelector<HTMLInputElement>('input[type="file"]')
  return { ...view, input, onUploaded }
}

/** Pick files, as the hidden input's change event, then let the queue start. */
async function pick(input: HTMLInputElement, files: File[]) {
  fireEvent.change(input, { target: { files } })
  await act(async () => {})
}

/** A drag event carrying files, as the browser dispatches it on window. */
function drag(type: string, files: File[] = []) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', { value: { types: ['Files'], files } })
  act(() => void window.dispatchEvent(event))
  return event
}

function deferred<T>() {
  let settle!: (value: T) => void
  let fail!: (error: unknown) => void
  const promise = new Promise<T>((resolve, reject) => { settle = resolve; fail = reject })
  return { promise, settle, fail }
}

/** The hover text of the first element matching `selector` — where each row's
 *  whole story lives, since the row itself has only one word of space. */
function title(selector: string) {
  return document.querySelector(selector)?.getAttribute('title')
}

function rows() {
  return Array.from(document.querySelectorAll('.queue .up')).map((li) => li.textContent ?? '')
}

beforeEach(() => {
  upload.mockReset()
  upload.mockResolvedValue(verdict())
})

describe('what is offered at all', () => {
  it('shows nothing before the policy has arrived', () => {
    // No gateway answer yet means no button — not a button that fails on click.
    expect(show(null).container.innerHTML).toBe('')
  })

  it('shows nothing to an account that may only read', () => {
    // The gateway decides this (role plus UPLOAD_ADMIN_ONLY), and a disabled
    // button that never explains itself is worse than no button.
    expect(show({ ...POLICY, allowed: false }).container.innerHTML).toBe('')
  })

  it('filters the picker by extension only when the gateway will not convert', () => {
    // A filter on a converting gateway would refuse files the server would
    // happily have taken.
    expect(show().input?.accept).toBe('')
    expect(show({ ...POLICY, converts: false }).input?.accept).toBe('.md,.txt')
  })
})

describe('uploading', () => {
  it('sends the file and reports what became of it', async () => {
    const { input, onUploaded } = show()

    await pick(input!, [file('notes.txt')])

    await waitFor(() => expect(rows()).toEqual(['notes.txt4 ¶']))
    expect(upload).toHaveBeenCalledTimes(1)
    expect(upload.mock.calls[0][0].name).toBe('notes.txt')
    expect(onUploaded).toHaveBeenCalledTimes(1)
  })

  it('uploads several files one at a time, not all at once', async () => {
    const first = deferred<UploadResult>()
    const second = deferred<UploadResult>()
    upload.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { input, onUploaded } = show()

    await pick(input!, [file('one.txt'), file('two.txt')])

    // Only the first is in flight; the second is visibly queued behind it.
    expect(upload).toHaveBeenCalledTimes(1)
    expect(rows()).toEqual(['one.txtsending', 'two.txtqueued'])

    await act(async () => {
      first.settle(verdict({ chunks: 2 }))
      await first.promise
    })
    expect(upload).toHaveBeenCalledTimes(2)

    await act(async () => {
      second.settle(verdict({ chunks: 9 }))
      await second.promise
    })
    await waitFor(() => expect(rows()).toEqual(['one.txt2 ¶', 'two.txt9 ¶']))

    // One refresh for the run, not one per file.
    expect(onUploaded).toHaveBeenCalledTimes(1)
  })

  it('says what the gateway said about each status', async () => {
    upload
      .mockResolvedValueOnce(verdict({ status: 'unchanged' }))
      .mockResolvedValueOnce(verdict({ status: 'queued', chunks: null }))
      .mockResolvedValueOnce(verdict({ status: 'no text to index' }))
      .mockResolvedValueOnce(verdict({ status: 'indexed', chunks: 7, converted_from: 'PDF' }))
    const { input } = show()

    await pick(input!, [file('a.txt'), file('b.txt'), file('c.png'), file('d.pdf')])

    await waitFor(() => expect(rows()).toEqual([
      'a.txtno change',
      'b.txtindexing',       // the indexer was busy; the next scan takes it
      'c.pngno text',        // a scanned page, which needs OCR
      'd.pdfPDF7 ¶',         // converted, and the format is named
    ]))
  })

  it('a failure stops that file and nothing else', async () => {
    upload
      .mockRejectedValueOnce(new Error('the gateway is unreachable'))
      .mockResolvedValueOnce(verdict({ chunks: 3 }))
    const { input, onUploaded } = show()

    await pick(input!, [file('one.txt'), file('two.txt')])

    await waitFor(() => expect(rows()).toEqual(['one.txtfailed', 'two.txt3 ¶']))
    expect(title('.up.failed .state')).toBe('the gateway is unreachable')
    expect(onUploaded).toHaveBeenCalledTimes(1)
  })

  it('does not refresh the header when every file failed', async () => {
    upload.mockRejectedValue(new Error('nope'))
    const { input, onUploaded } = show()

    await pick(input!, [file('one.txt')])

    await waitFor(() => expect(rows()).toEqual(['one.txtfailed']))
    expect(onUploaded).not.toHaveBeenCalled()
  })

  it('explains a truncated or replaced document on hover', async () => {
    upload.mockResolvedValue(verdict({
      path: 'report.pdf.txt', replaced: true, converted_from: 'PDF', truncated: true, chunks: 120,
    }))
    const { input } = show()

    await pick(input!, [file('report.pdf')])

    await waitFor(() => expect(title('.up .state')).toBe(
      'converted from PDF, replaced as report.pdf.txt — 120 chunk(s) indexed' +
        ' (only the first part — the document was very long)',
    ))
  })
})

describe('what is refused before it is sent', () => {
  it('refuses an empty file', async () => {
    const { input } = show()

    await pick(input!, [file('empty.txt', { content: '' })])

    expect(rows()).toEqual(['empty.txtfailed'])
    expect(title('.up .state')).toBe('the file is empty')
    expect(upload).not.toHaveBeenCalled()
  })

  it('refuses a file over the limit, naming the limit', async () => {
    const { input } = show()

    await pick(input!, [file('huge.pdf', { size: 30 * 1024 * 1024 })])

    expect(title('.up .state')).toBe('over the 25 MB limit')
    expect(upload).not.toHaveBeenCalled()
  })

  it('refuses a non-text format only when the gateway does not convert', async () => {
    const { input } = show({ ...POLICY, converts: false })

    await pick(input!, [file('installer.exe'), file('notes.md')])

    expect(document.querySelectorAll('.up.failed')).toHaveLength(1)
    expect(title('.up.failed .state')).toBe('.exe is not a text format Seshat indexes')
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1))
    expect(upload.mock.calls[0][0].name).toBe('notes.md')
  })

  it('sends any format when the gateway converts', async () => {
    const { input } = show()

    await pick(input!, [file('installer.exe')])

    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1))
  })

  it('keeps the list to the last few files rather than growing forever', async () => {
    const { input } = show()

    await pick(input!, Array.from({ length: 30 }, (_, i) => file(`empty-${i}.txt`, { content: '' })))

    expect(rows()).toHaveLength(24)
    expect(rows()[0]).toContain('empty-6.txt')
  })
})

describe('dropping files on the window', () => {
  it('offers the drop target while a drag is over the page', () => {
    show()

    drag('dragenter')
    expect(screen.getByText('Release to add to the library')).toBeTruthy()

    drag('dragleave')
    expect(screen.queryByText('Release to add to the library')).toBeNull()
  })

  it('cancels the browser default on dragover, or the drop navigates away', () => {
    show()
    drag('dragenter')

    expect(drag('dragover').defaultPrevented).toBe(true)
  })

  it('uploads what was dropped, and puts the veil away', async () => {
    const { onUploaded } = show()
    drag('dragenter')

    const dropped = drag('drop', [file('dropped.txt')])

    expect(dropped.defaultPrevented).toBe(true)
    expect(screen.queryByText('Release to add to the library')).toBeNull()
    await waitFor(() => expect(rows()).toEqual(['dropped.txt4 ¶']))
    expect(onUploaded).toHaveBeenCalledTimes(1)
  })

  it('ignores a drag that carries no files, such as selected text', () => {
    show()
    const event = new Event('dragenter', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'dataTransfer', { value: { types: ['text/plain'] } })
    act(() => void window.dispatchEvent(event))

    expect(screen.queryByText('Release to add to the library')).toBeNull()
  })
})

describe('the list itself', () => {
  it('can be cleared, and the clear control appears only when there is something to clear', async () => {
    const { input } = show()
    expect(screen.queryByLabelText('Clear the upload list')).toBeNull()

    await pick(input!, [file('notes.txt')])
    await waitFor(() => expect(rows()).toHaveLength(1))

    act(() => screen.getByLabelText('Clear the upload list').click())
    expect(rows()).toEqual([])
    expect(screen.queryByLabelText('Clear the upload list')).toBeNull()
  })
})
