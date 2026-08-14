// Keycloak sign-in for the whole SPA (Authorization Code + PKCE, keycloak-js).
//
// The app never handles a password: main.tsx calls initAuth() BEFORE the first
// render, keycloak-js redirects an unauthenticated visitor to the realm's login
// page, and every gateway call carries the resulting access token as a bearer
// header. Realm roles decide what is allowed:
//   use-ui  required to use the app at all (the gateway enforces it too)
//   admin   additionally required to add documents to the library and to
//           trigger a reindex
//
// The checks here only decide what to render. The gateway verifies the same
// token signature, issuer, audience and roles server-side, so a user who edits
// their token gets a 401, not access.
//
// Note that the upload button is NOT gated on hasRole() here: the gateway
// answers `upload.allowed` in GET /config, having applied both the role and
// UPLOAD_ADMIN_ONLY. One decision, made once, in the place that enforces it.

import Keycloak from 'keycloak-js'

export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? '/seshat/auth',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'seshat',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT ?? 'seshat-ui',
})

/** Resolves true once signed in; redirects to Keycloak when there is no
 *  session. Call exactly once, before the first render. */
export async function initAuth(): Promise<boolean> {
  return keycloak.init({
    onLoad: 'login-required',
    pkceMethod: 'S256',
    // No silent-SSO iframe: it needs a hosted static page and third-party
    // cookies, and updateToken() below covers session upkeep on its own.
    checkLoginIframe: false,
  })
}

export function hasRole(role: 'use-ui' | 'admin'): boolean {
  return keycloak.hasRealmRole(role)
}

export function displayName(): string {
  const t = keycloak.tokenParsed as Record<string, unknown> | undefined
  return String(t?.name || t?.preferred_username || 'Signed in')
}

export function displayEmail(): string {
  const t = keycloak.tokenParsed as Record<string, unknown> | undefined
  return String(t?.email || t?.preferred_username || '')
}

export function signOut(): void {
  // Conversations are persisted to localStorage so a reload doesn't lose the
  // thread. On a shared machine that would otherwise leave the next person to
  // sign in reading this user's questions — clear it before handing off.
  localStorage.removeItem('seshat-state')
  void keycloak.logout({ redirectUri: window.location.origin + import.meta.env.BASE_URL })
}

/** The access token, refreshed if it expires within 30 seconds. A dead SSO
 *  session bounces through the login page rather than letting requests limp on
 *  to a 401 the UI would have to explain. */
export async function freshToken(): Promise<string> {
  try {
    await keycloak.updateToken(30)
  } catch {
    await keycloak.login()
  }
  return keycloak.token ?? ''
}

/** Headers to spread into any gateway fetch. */
export async function authHeaders(): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await freshToken()}` }
}
