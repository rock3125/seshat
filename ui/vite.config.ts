import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// The app is served under a sub-path in production (/seshat/), so `base` drives
// both the asset URLs and import.meta.env.BASE_URL, which src/basePath.ts reads.
// Set VITE_BASE at build time; it defaults to '/' for `npm run dev`.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_')
  return {
    base: env.VITE_BASE || '/',
    plugins: [react()],
    server: {
      port: 5173,
      // Dev only: the gateway is on its own port here, not behind the nginx
      // that fronts both in production. Proxying keeps the app same-origin in
      // both modes, so no code path differs between them.
      proxy: {
        '/seshat/api': {
          target: env.VITE_DEV_GATEWAY || 'http://localhost:8090',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/seshat\/api/, ''),
        },
      },
    },
  }
})
