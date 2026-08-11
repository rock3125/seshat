import { useEffect, useRef, useState } from 'react'
import { SendIcon, StopIcon } from './icons'

/**
 * The question box. Enter sends; Shift+Enter is a newline — the convention
 * every chat interface has trained people into, so departing from it costs a
 * user their message.
 */
export default function Composer({
  onSend, onStop, busy, disabledReason,
}: {
  onSend: (prompt: string) => void
  onStop: () => void
  busy: boolean
  /** Set when the gateway can't answer at all (no API key); the box explains
   *  why rather than silently swallowing what someone types. */
  disabledReason?: string
}) {
  const [value, setValue] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)

  // Grow to fit, up to the CSS max-height. Reset to auto first or the box can
  // only ever get taller.
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [value])

  // Focus returns to the box when an answer finishes, so a follow-up needs no
  // click.
  useEffect(() => {
    if (!busy) ref.current?.focus()
  }, [busy])

  function submit() {
    const prompt = value.trim()
    if (!prompt || busy) return
    onSend(prompt)
    setValue('')
  }

  return (
    <div className="composer-well">
      <div className="composer">
        <textarea
          ref={ref}
          rows={1}
          value={value}
          disabled={Boolean(disabledReason)}
          placeholder={disabledReason ?? 'Ask the library…'}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              submit()
            }
          }}
          aria-label="Your question"
        />
        {busy ? (
          <button type="button" className="send stop" onClick={onStop} title="Stop" aria-label="Stop">
            <StopIcon size={16} />
          </button>
        ) : (
          <button
            type="button"
            className="send"
            onClick={submit}
            disabled={!value.trim() || Boolean(disabledReason)}
            title="Send"
            aria-label="Send"
          >
            <SendIcon size={16} />
          </button>
        )}
      </div>
      <div className="composer-note">
        <span>Enter to send · Shift+Enter for a new line</span>
        <span>Answers are drawn from the library and cited by chunk</span>
      </div>
    </div>
  )
}
