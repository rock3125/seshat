import React from 'react'
import ReactDOM from 'react-dom/client'
import { Provider } from 'react-redux'
import App from './App'
import Mark from './components/Mark'
import { hasRole, initAuth, signOut } from './auth/keycloak'
import { store } from './store'

import './index.css'

const root = ReactDOM.createRoot(document.getElementById('root')!)

// Sign in BEFORE rendering anything: initAuth() redirects to Keycloak when
// there is no session, so an unauthenticated visitor never sees the app at all.
initAuth()
  .then((authenticated) => {
    if (!authenticated) return // the redirect to the login page is in flight

    if (!hasRole('use-ui')) {
      root.render(
        <Screen title="No access">
          <p>
            Your account signed in, but it does not hold the <code>use-ui</code> role
            that Seshat requires. An administrator can grant it in the Keycloak
            console under Realm roles.
          </p>
          <button type="button" className="chip" style={{ alignSelf: 'flex-start' }} onClick={signOut}>
            Sign out
          </button>
        </Screen>,
      )
      return
    }

    root.render(
      <React.StrictMode>
        <Provider store={store}>
          <App />
        </Provider>
      </React.StrictMode>,
    )
  })
  .catch((e: unknown) => {
    // Keycloak unreachable or misconfigured — say which, rather than showing a
    // blank page that looks like the app is broken.
    root.render(
      <Screen title="Sign-in unavailable">
        <p>
          Seshat could not reach the identity provider. Check that Keycloak is
          running (<code>docker compose ps keycloak</code>) and that the realm
          import completed.
        </p>
        <div className="detail">{String((e as Error)?.message ?? e)}</div>
      </Screen>,
    )
  })

function Screen({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="screen">
      <div className="card">
        <Mark className="glyph" title="Seshat" />
        <h1>{title}</h1>
        {children}
      </div>
    </div>
  )
}
