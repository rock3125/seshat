import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble'
import conversations from '../store/conversationsSlice'
import ui from '../store/uiSlice'
import type { Message, Passage } from '../types'

/**
 * One turn, rendered — and in particular its citations, which are the whole
 * claim of the interface.
 *
 * The model is instructed to mark every claim `[chunk:123]`. Two rules make
 * those markers worth trusting, and both are asserted here:
 *
 *   a marker whose chunk was NOT among this turn's retrieved passages is
 *   rendered dead — grey, unclickable, and saying so — rather than hidden. A
 *   citation the model invented is precisely the thing a reader needs to be able
 *   to see, and silently dropping it would turn a fabrication into a plain
 *   sentence that reads as grounded;
 *
 *   markers are rewritten inside every element that can contain text, not just
 *   paragraphs. Missing one drops the markers inside list items and table cells,
 *   which is where a lot of cited facts end up.
 */
function store() {
  return configureStore({ reducer: { conversations, ui } })
}

function show(message: Partial<Message>, known: number[] = []) {
  const s = store()
  const full: Message = { id: 'm1', role: 'assistant', content: '', ...message }
  const view = render(
    <Provider store={s}>
      <MessageBubble message={full} knownChunks={new Set(known)} />
    </Provider>,
  )
  return { ...view, store: s }
}

function passage(chunk_id: number): Passage {
  return { chunk_id, score: 0.9, title: 'Ebers Papyrus', path: 'ebers.txt', text: 'a passage' }
}

/** Every citation button on screen, as [label, live?, hover text]. */
function citations() {
  return Array.from(document.querySelectorAll('button.cite')).map((b) => [
    b.textContent,
    !(b as HTMLButtonElement).disabled,
    b.getAttribute('title'),
  ])
}

describe('a question', () => {
  it('is rendered as what was asked, and labelled as asked', () => {
    show({ role: 'user', content: 'What does the library say about Seshat?' })

    expect(screen.getByText('Asked')).toBeTruthy()
    expect(screen.getByText('What does the library say about Seshat?')).toBeTruthy()
  })

  it('is never treated as markdown', () => {
    // Someone's question is their text, not a document: `**` in it stays `**`,
    // and an image or a link in it is not rendered as one.
    const { container } = show({ role: 'user', content: '**not bold** [link](http://x)' })

    expect(container.querySelector('strong')).toBeNull()
    expect(container.querySelector('a')).toBeNull()
    expect(screen.getByText('**not bold** [link](http://x)')).toBeTruthy()
  })
})

describe('a citation', () => {
  it('opens the source it names', () => {
    const { store: s } = show(
      { content: 'A remedy for obstruction [chunk:1409].', passages: [passage(1409)] },
      [1409],
    )

    const cite = screen.getByRole('button', { name: '1409' })
    fireEvent.click(cite)

    expect(s.getState().ui.focusedChunk).toBe(1409)
    expect(s.getState().ui.drawerOpen).toBe(true)
  })

  it('is rendered dead, and explains itself, when the model invented it', () => {
    // The chunk was never retrieved this turn. It must still be visible.
    show({ content: 'An unsupported claim [chunk:99].', passages: [passage(1409)] }, [1409])

    expect(citations()).toEqual([['99', false, "Source 99 was not among this turn's results"]])
  })

  it('distinguishes the real ones from the invented ones in the same sentence', () => {
    show({ content: 'Two claims [chunk:1] and [chunk:2].' }, [1])

    expect(citations()).toEqual([
      ['1', true, 'Open source 1'],
      ['2', false, "Source 2 was not among this turn's results"],
    ])
  })

  it('is rewritten inside list items and table cells, not only paragraphs', () => {
    // The documented bug: only <p> used to route through the transform, so a
    // cited fact in a list or a table lost its marker entirely.
    show(
      {
        content: [
          '- A listed claim [chunk:1]',
          '',
          '| Claim | Source |',
          '| --- | --- |',
          '| A tabled claim [chunk:2] | x |',
        ].join('\n'),
      },
      [1, 2],
    )

    expect(document.querySelectorAll('li button.cite')).toHaveLength(1)
    expect(document.querySelectorAll('td button.cite')).toHaveLength(1)
  })

  it('is rewritten inside emphasis and bold', () => {
    show({ content: 'A **bold claim [chunk:1]** and an *emphatic one [chunk:2]*.' }, [1, 2])

    expect(document.querySelectorAll('strong button.cite')).toHaveLength(1)
    expect(document.querySelectorAll('em button.cite')).toHaveLength(1)
  })

  it('reads the forms the model actually produces', () => {
    // `[chunk:1]`, a bare `[2]`, and a grouped `[chunk:3, 4]` — asked for the
    // first, reliably produces all three.
    show({ content: 'One [chunk:1], two [2], three and four [chunk:3, 4].' }, [1, 2, 3, 4])

    expect(citations().map((c) => c[0])).toEqual(['1', '2', '3', '4'])
  })

  it('leaves a chunk id inside a code span alone', () => {
    // A marker in a code span is documentation of the format, not a citation to
    // follow — and turning it into a button would make the example unreadable.
    const { container } = show({ content: 'Write `[chunk:123]` after each claim.' }, [123])

    expect(citations()).toEqual([])
    expect(container.querySelector('code')?.textContent).toBe('[chunk:123]')
  })

  it('leaves an answer with no markers exactly as written', () => {
    const { container } = show({ content: 'An answer with no citations at all.' }, [1])

    expect(citations()).toEqual([])
    expect(container.querySelector('.prose')?.textContent).toBe('An answer with no citations at all.')
  })
})

describe('the answer around it', () => {
  it('renders markdown', () => {
    const { container } = show({ content: 'A **bold** claim and a `term`.' })

    expect(container.querySelector('strong')?.textContent).toBe('bold')
    expect(container.querySelector('code')?.textContent).toBe('term')
  })

  it('counts the sources behind the turn', () => {
    show({ content: 'An answer.', passages: [passage(1), passage(2)] })

    expect(screen.getByText('2 sources')).toBeTruthy()
  })

  it('says nothing about sources when there were none', () => {
    show({ content: 'An answer.' })

    expect(screen.queryByText(/sources/)).toBeNull()
  })

  it('shows the tools the model reached for, and marks the ones that failed', () => {
    const { container } = show({
      content: 'An answer.',
      trace: [
        { name: 'search', detail: 'obstruction', ok: true, resultCount: 6 },
        { name: 'load_chunk', detail: 'chunk 1409', ok: false },
      ],
    })

    expect(screen.getByText('Search')).toBeTruthy()
    expect(screen.getByText('Read')).toBeTruthy()   // load_chunk reads as Read
    expect(screen.getByText('obstruction')).toBeTruthy()
    expect(screen.getByText('6')).toBeTruthy()
    expect(container.querySelectorAll('.trace-line.bad')).toHaveLength(1)
  })

  it('shows a caret only while tokens are still arriving', () => {
    expect(show({ content: 'Partial', streaming: true }).container.querySelector('.caret')).toBeTruthy()
    expect(show({ content: 'Complete' }).container.querySelector('.caret')).toBeNull()
  })

  it('shows a failure without throwing away the part that arrived', () => {
    const { container } = show({ content: 'Half an ans', error: 'the gateway went away' })

    expect(container.textContent).toContain('Half an ans')
    expect(screen.getByText('Could not answer')).toBeTruthy()
    expect(screen.getByText('the gateway went away')).toBeTruthy()
  })

  it('renders a turn that failed before any text arrived', () => {
    // No content, no trace — just the error. The empty prose block must not be
    // rendered as an empty bubble either.
    const { container } = show({ content: '', error: 'GEMINI_API_KEY is not set' })

    expect(container.querySelector('.prose')).toBeNull()
    expect(screen.getByText('GEMINI_API_KEY is not set')).toBeTruthy()
  })
})
