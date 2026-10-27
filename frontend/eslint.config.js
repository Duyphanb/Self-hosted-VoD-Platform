import js from '@eslint/js';
import pluginQuery from '@tanstack/eslint-plugin-query';
import reactHooks from 'eslint-plugin-react-hooks';
import { reactRefresh } from 'eslint-plugin-react-refresh';
import { defineConfig, globalIgnores } from 'eslint/config';
import tseslint from 'typescript-eslint';

// CI runs `npm run lint` with --max-warnings=0, so a warning fails the build like an error.
export default defineConfig([
  // ESLint always skips node_modules; the Vite build output must be skipped explicitly.
  globalIgnores(['dist/']),
  {
    // Application code, tests and TypeScript config files such as vite.config.ts.
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite(),
      pluginQuery.configs['flat/recommended']
    ],
    rules: {
      // Match TypeScript's noUnusedLocals: a property destructured beside a rest element omits it from the rest.
      '@typescript-eslint/no-unused-vars': ['error', { ignoreRestSiblings: true }]
    }
  },
  {
    // ES module config files (this file, PostCSS and Tailwind).
    files: ['**/*.js'],
    extends: [js.configs.recommended]
  }
]);
