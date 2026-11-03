import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import svelte from 'eslint-plugin-svelte';
import { defineConfig } from 'eslint/config';

export default defineConfig([
  js.configs.recommended,
  tseslint.configs.recommended,
  svelte.configs.recommended,
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.node }
    },
    rules: {
      // Codebase-wide migrations (SvelteKit typed resolve(), keyed #each blocks) are tracked
      // separately; keep them visible as warnings instead of failing the initial lint rollout.
      'svelte/no-navigation-without-resolve': 'warn',
      'svelte/require-each-key': 'warn'
    }
  },
  {
    files: ['**/*.svelte', '**/*.svelte.ts'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser
      }
    }
  },
  {
    ignores: ['build/', '.svelte-kit/', 'dist/']
  }
]);
