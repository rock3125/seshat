import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Composer from './Composer'

/**
 * The question box.
 *
 * Enter sends and Shift+Enter is a newline. That is not a preference — it is the
 * convention every chat interface has trained people into, and departing from it
 * costs someone the paragraph they just typed. Which is exactly the sort of
 * behaviour that gets broken by an unrelated change to a keydown handler, and
 * exactly the sort that no one notices until it happens to them.
 */
function show({ busy = false, disabledReason }: { busy?: boolean; disabledReason?: string } = {}) {
  const onSend = vi.fn()
  const onStop = vi.fn()
  const view = render(
    <Composer onSend={onSend} onStop={onStop} busy={busy} disabledReason={disabledReason} />,
  )
  const box = screen.getByLabelText<HTMLTextAreaElement>('Your question')
  const type = (text: string) => fireEvent.change(box, { target: { value: text } })
  return { ...view, box, type, onSend, onStop }
}

describe('sending', () => {
  it('sends on Enter and empties the box', () => {
    const { box, type, onSend } = show()

    type('What does the library say?')
    fireEvent.keyDown(box, { key: 'Enter' })

    expect(onSend).toHaveBeenCalledWith('What does the library say?')
    expect(box.value).toBe('')
  })

  it('sends the question trimmed', () => {
    const { box, type, onSend } = show()

    type('  a question with padding  ')
    fireEvent.keyDown(box, { key: 'Enter' })

    expect(onSend).toHaveBeenCalledWith('a question with padding')
  })

  it('starts a new line on Shift+Enter instead of sending', () => {
    const { box, type, onSend } = show()

    type('first line')
    fireEvent.keyDown(box, { key: 'Enter', shiftKey: true })

    expect(onSend).not.toHaveBeenCalled()
    // The default is left alone, which is what actually inserts the newline.
    expect(box.value).toBe('first line')
  })

  it('does nothing on an empty or blank box', () => {
    const { box, type, onSend } = show()

    fireEvent.keyDown(box, { key: 'Enter' })
    type('   \n  ')
    fireEvent.keyDown(box, { key: 'Enter' })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('sends on the button too, and the button is dead until there is something to send', () => {
    const { type, onSend } = show()
    const send = screen.getByLabelText<HTMLButtonElement>('Send')

    expect(send.disabled).toBe(true)
    type('   ')
    expect(send.disabled).toBe(true)

    type('a question')
    expect(send.disabled).toBe(false)
    fireEvent.click(send)
    expect(onSend).toHaveBeenCalledWith('a question')
  })

  it('leaves other keys alone', () => {
    const { box, type, onSend } = show()

    type('a question')
    for (const key of ['a', 'Tab', 'Escape', 'ArrowUp']) {
      fireEvent.keyDown(box, { key })
    }

    expect(onSend).not.toHaveBeenCalled()
  })
})

describe('while an answer is streaming', () => {
  it('offers Stop in place of Send', () => {
    const { onStop } = show({ busy: true })

    expect(screen.queryByLabelText('Send')).toBeNull()
    fireEvent.click(screen.getByLabelText('Stop'))

    expect(onStop).toHaveBeenCalledTimes(1)
  })

  it('does not send a second question over the top of the first', () => {
    const { box, type, onSend } = show({ busy: true })

    type('a second question')
    fireEvent.keyDown(box, { key: 'Enter' })

    expect(onSend).not.toHaveBeenCalled()
    // And what was typed is still there, ready to send when the answer lands.
    expect(box.value).toBe('a second question')
  })

  it('takes the focus back when the answer finishes, so a follow-up needs no click', () => {
    const { box, rerender } = show({ busy: true })
    box.blur()

    rerender(<Composer onSend={vi.fn()} onStop={vi.fn()} busy={false} />)

    expect(document.activeElement).toBe(box)
  })
})

describe('when the gateway cannot answer at all', () => {
  it('says why in the box rather than swallowing what is typed', () => {
    // The reason comes from GET /config — no API key, or an unreachable gateway.
    const { box } = show({ disabledReason: 'GEMINI_API_KEY is not set on the gateway' })

    expect(box.disabled).toBe(true)
    expect(box.placeholder).toBe('GEMINI_API_KEY is not set on the gateway')
    expect(screen.getByLabelText<HTMLButtonElement>('Send').disabled).toBe(true)
  })

  it('asks the library when it can', () => {
    expect(show().box.placeholder).toBe('Ask the library…')
  })
})

describe('the note under the box', () => {
  it('states both the shortcut and where answers come from', () => {
    // The second half is the product's whole claim, and this is the only place
    // it is stated before a reader has asked anything.
    show()

    expect(screen.getByText(/Enter to send/)).toBeTruthy()
    expect(screen.getByText(/cited by chunk/)).toBeTruthy()
  })
})
