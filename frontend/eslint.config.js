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
    files: ['**/*.{ts,tsx,mts,cts}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite(),
      pluginQuery.configs['flat/recommended']
    ],
    rules: {
      // Match TypeScript's noUnusedLocals: a property destructured beside a rest element omits it from the rest.
      '@typescript-eslint/no-unused-vars': ['error', { ignoreRestSiblings: true }],
      // The shared ApiClient (`useApiClient()`, or an API function's `client: ApiClient` parameter) lives
      // as long as AuthProvider and is not query input. Putting it in a queryKey would hash its
      // in-flight refresh state and split the cache mid-refresh; every other queryFn variable stays required.
      '@tanstack/query/exhaustive-deps': [
        'error',
        { allowlist: { variables: ['apiClient'], types: ['ApiClient'] } }
      ]
    }
  },
  {
    // JavaScript config files (this file, PostCSS and Tailwind).
    files: ['**/*.{js,mjs,cjs}'],
    extends: [js.configs.recommended]
  },
  {
    // tsc never checks JavaScript here (allowJs is false) while Vite would still bundle it.
    files: ['src/**/*.{js,jsx,mjs,cjs}', '**/*.jsx'],
    languageOptions: { parserOptions: { ecmaFeatures: { jsx: true } } },
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Program',
          message: 'Write frontend application code in TypeScript (.ts or .tsx); tsc does not check JavaScript files.'
        }
      ]
    }
  }
]);
