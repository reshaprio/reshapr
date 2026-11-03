import { fileURLToPath } from 'node:url';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-node';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [tailwindcss(), sveltekit({ adapter: adapter() })],
  resolve: {
    alias: [
      { find: /^tw-animate-css$/, replacement: fileURLToPath(new URL('./node_modules/tw-animate-css/dist/tw-animate.css', import.meta.url)) },
      // monaco-editor >= 0.56 ships an `exports` map that hides the `esm/vs/` deep paths
      // still used by monaco-yaml's dependencies (monaco-worker-manager, monaco-types).
      { find: /^monaco-editor\/esm\/vs\/(.*)$/, replacement: 'monaco-editor/$1' }
    ]
  },
  worker: {
    format: 'es'
  }
});

