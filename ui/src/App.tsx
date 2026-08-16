import { useCallback, useEffect, useMemo, useState } from 'react'
import Composer from './components/Composer'
import Rail from './components/Rail'
import SourcesDrawer from './components/SourcesDrawer'
import Transcript from './components/Transcript'
import { AutoIcon, MoonIcon, RailIcon, SourcesIcon, SunIcon } from './components/icons'
import { fetchConfig } from './api/gateway'
import { NARROW, useMediaQuery } from './hooks/useMediaQuery'
import { abortTurn, sendMessage } from './features/chat/sendMessage'
import { store } from './store'
import { useAppDispatch, useAppSelector } from './store/hooks'
import { uiActions, type Theme } from './store/uiSlice'
import type { ServerConfig } from './types'

const THEMES: Theme[] = ['system', 'light', 'dark']

export default function App() {
  const dispatch = useAppDispatch()
  const { activeId, byId } = useAppSelector((s) => s.conversations)
  const { railOpen, drawerOpen, focusedChunk, theme } = useAppSelector((s) => s.ui)
  const [config, setConfig] = useState<ServerConfig | null>(null)
  const [configError, setConfigError] = useState<string | null>(null)

  const conversation = activeId ? byId[activeId] ?? null : null
  const busy = conversation?.messages.at(-1)?.streaming === true

  // Every passage this thread retrieved, best first. Folded rather than
  // stored: it is a fold over a list that is already in memory, and a second
  // copy in the store is a second thing to keep correct.
  //
  // Memoised rather than recomputed per render, because during a stream this
  // component renders on every token and the fold builds a Map and a sorted
  // array each time — none of which can change until retrieval returns
  // something new.
  //
  // Keyed on the passages themselves, NOT on the messages array: appending a
  // token replaces that array (Immer gives a new one whenever any message in
  // it changes), so a dependency on it would be a dependency on every token.
  const messages = conversation?.messages
  const passageKey = (messages ?? [])
    .flatMap((m) => m.passages?.map((p) => `${p.chunk_id}:${p.score}`) ?? [])
    .join('|')
  const passages = useMemo(
    () => [...new Map(
      (messages ?? []).flatMap((m) => m.passages ?? []).map((p) => [p.chunk_id, p]),
    ).values()].sort((a, b) => b.score - a.score),
    // `messages` is deliberately not a dependency: passageKey is the part of
    // it this fold reads, and depending on the array itself would recompute on
    // every streamed token — which is the whole thing being avoided here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [passageKey],
  )

  const refreshConfig = useCallback(() => {
    fetchConfig()
      .then((c) => {
        setConfig(c)
        setConfigError(null)
      })
      .catch((e: Error) => setConfigError(e.message))
  }, [])

  // Once at start, then once a minute. The gateway rescans the library folder
  // on that same cadence, so the counts in the header follow a file dropped
  // into (or deleted from) the folder by hand without anyone reloading.
  useEffect(() => {
    refreshConfig()
    const timer = window.setInterval(refreshConfig, 60_000)
    return () => window.clearInterval(timer)
  }, [refreshConfig])

  function ask(prompt: string) {
    // `store.getState` is passed as an arrow rather than as the bare method:
    // detached from its object it would be called with the wrong `this`.
    void sendMessage(prompt, dispatch, () => store.getState())
  }

  const narrow = useMediaQuery(NARROW)

  return (
    <div
      className={`shell${railOpen ? '' : ' rail-closed'}${drawerOpen ? ' drawer-open' : ''}`}
    >
      <Rail
        onNavigate={narrow ? () => dispatch(uiActions.toggleRail()) : undefined}
        uploads={config?.upload ?? null}
        onUploaded={refreshConfig}
      />

      <main className="main">
        <header className="bar">
          <button
            type="button"
            className="icon-button"
            onClick={() => dispatch(uiActions.toggleRail())}
            aria-label={railOpen ? 'Hide threads' : 'Show threads'}
            title="Threads"
          >
            <RailIcon size={17} />
          </button>

          <div className="eyebrow">
            <span>The House of Books</span>
            {config ? (
              <span>
                <b>{config.documents.toLocaleString()}</b> documents ·{' '}
                <b>{config.chunks.toLocaleString()}</b> paragraphs
              </span>
            ) : null}
            {config ? <span>{config.model}</span> : null}
          </div>

          <div className="spacer" />

          <button
            type="button"
            className="icon-button"
            onClick={() => {
              const next = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]
              dispatch(uiActions.setTheme(next))
            }}
            title={`Theme: ${theme}`}
            aria-label={`Theme: ${theme}. Click to change.`}
          >
            {theme === 'light' ? <SunIcon size={17} /> : theme === 'dark' ? <MoonIcon size={17} /> : <AutoIcon size={17} />}
          </button>

          <button
            type="button"
            className={`icon-button${drawerOpen ? ' on' : ''}`}
            onClick={() => dispatch(uiActions.toggleDrawer())}
            title="Sources"
            aria-label={drawerOpen ? 'Hide sources' : 'Show sources'}
          >
            <SourcesIcon size={17} />
          </button>
        </header>

        <Transcript conversation={conversation} config={config} onAsk={ask} />

        <Composer
          onSend={ask}
          onStop={abortTurn}
          busy={busy}
          disabledReason={
            configError
              ? 'The gateway is unreachable — check that it is running'
              : config && !config.chat_enabled
                ? 'GEMINI_API_KEY is not set on the gateway'
                : undefined
          }
        />
      </main>

      {drawerOpen ? <SourcesDrawer passages={passages} focused={focusedChunk} /> : null}

      {/* On a narrow screen the rail and the drawer are overlays; a tap outside
          should dismiss them, the way every drawer on a phone does. */}
      {narrow && (railOpen || drawerOpen) ? (
        <button
          type="button"
          className="scrim"
          aria-label="Close"
          onClick={() => {
            if (drawerOpen) dispatch(uiActions.closeDrawer())
            else dispatch(uiActions.toggleRail())
          }}
        />
      ) : null}
    </div>
  )
}
