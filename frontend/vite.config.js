import { readdirSync, cpSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const root = fileURLToPath(new URL('.', import.meta.url));

// The API Gateway. Every backend call is made through it, never to a service port.
const gateway = 'http://localhost:8080';

/**
 * Serves /login.html from the existing pages/login.html file.
 *
 * Transfer redirects anonymous or expired page requests to /login.html, and the
 * Gateway serves its own copy at that path. This frontend keeps the page at
 * /pages/login.html, so both URLs are mapped onto the one file instead of
 * maintaining a second copy. "/" opens the login page as the development entry
 * point because this directory has no index.html.
 */
function loginPageAlias() {
  return {
    name: 'mmiv-login-page-alias',
    configureServer(server) {
      // Added before Vite's internal middlewares, so the URL is rewritten
      // before the HTML handler resolves the file.
      server.middlewares.use((request, _response, next) => {
        if (request.url === '/') {
          request.url = '/pages/login.html';
        } else if (request.url === '/login.html' || request.url.startsWith('/login.html?')) {
          request.url = '/pages/login.html' + request.url.slice('/login.html'.length);
        }
        next();
      });
    },
  };
}

export default defineConfig({
  root,
  // There is no Vite public/ directory: every asset is referenced by its real
  // path (/css, /js, /img, /font, /assets) and served straight from this folder.
  publicDir: false,
  server: {
    port: 5173,
    proxy: {
      // Forwarded verbatim. The Gateway strips /user-access itself, so this
      // proxy must not rewrite either path.
      '/user-access': { target: gateway, changeOrigin: true },
      '/api': { target: gateway, changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      input: Object.fromEntries(
        readdirSync(resolve(root, 'pages'))
          .filter((name) => name.endsWith('.html'))
          .map((name) => [name.replace('.html', ''), resolve(root, 'pages', name)]),
      ),
    },
  },
  plugins: [
    loginPageAlias(),
    {
      name: 'preserve-team-controller-assets',
      closeBundle() {
        for (const directory of ['js', 'css', 'img', 'font', 'assets']) {
          cpSync(resolve(root, directory), resolve(root, 'dist', directory), { recursive: true });
        }
      },
    },
  ],
});
