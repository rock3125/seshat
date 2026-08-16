// Lint rules for the SPA.
//
// The set is deliberately small. Two rules earn their place because the code
// already relies on them being enforced:
//
//   react-hooks/*        the store, the drop listeners and the scroll pinning
//                        all depend on hook order and on dependency arrays
//                        being honest. There is an `eslint-disable` for
//                        exhaustive-deps in SourcesDrawer.tsx and another in
//                        App.tsx — both were inert until this file existed,
//                        which is to say nobody was checking that the thing
//                        they suppress was the thing that fires.
//   no-floating-promises  every gateway call is async and several are launched
//                        from event handlers, where a dropped rejection is an
//                        error nobody ever sees.
//
// Type-aware linting (projectService) is what makes the second one possible; it
// is why this config points at the tsconfigs rather than parsing files alone.

import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'node_modules', '*.tsbuildinfo'] },

  js.configs.recommended,

  {
    // Type-aware rules are scoped to the TypeScript sources, because they need
    // a tsconfig that covers the file — and this config file itself is plain
    // JS that no tsconfig includes.
    files: ['**/*.{ts,tsx}'],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // A rejected promise nobody awaited is an error that never reaches the
      // user. `void expr` is the documented way to say "deliberately not
      // awaited", and the code already uses it.
      '@typescript-eslint/no-floating-promises': 'error',

      // The gateway's JSON arrives as `unknown` and is narrowed at the edge on
      // purpose (see types.ts); flagging every one of those casts would be
      // noise about a decision already made.
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',

      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },

  // Config files run in Node, not the browser.
  {
    files: ['*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
  },

  // The entry point is not a fast-refresh boundary — it mounts the tree and
  // renders two fallback screens, and has no component for React Refresh to
  // swap. The rule has nothing useful to say about it.
  {
    files: ['src/main.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
)

