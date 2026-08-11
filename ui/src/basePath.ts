// The app is served under /seshat/ in production and at / under `npm run dev`.
// Vite injects import.meta.env.BASE_URL from the build-time `base` option, so
// everything that builds an in-app or API URL goes through here and switching
// the prefix stays a build flag with no code change.

/** '' at the site root, or '/seshat' under a sub-path (never a trailing slash). */
export const BASE = import.meta.env.BASE_URL.replace(/\/+$/, '')

/** The gateway's origin-relative prefix. Same-origin in both modes: nginx
 *  proxies /seshat/api to the gateway in production, and the Vite dev server
 *  proxies the identical path (see vite.config.ts). Being same-origin
 *  everywhere is what keeps CORS out of the browser's way. */
export const API = `${BASE}/api`

/** Prefix an absolute in-app path: '/' -> '/seshat/'. */
export function withBase(path: string): string {
  if (path === '/') return BASE ? `${BASE}/` : '/'
  return BASE + path
}
