# Shared React frontend

Run `npm ci` and `npm run build` here with Node 24. Vite builds the HTML entry
points in `pages/` and React components in `src/pages/` into `dist/`. Build this
before packaging `services/api-gateway`; Gateway serves the generated assets.
CI performs this build automatically. Transfer and User Access no longer serve HTML.

The existing team implementation in `js/` is preserved. React renders each page's
initial structure; `src/controllers.js` then loads those controllers in order.
They own their existing dynamic DOM sections. Navigation loads a new document,
which disposes their listeners and timers. This is an incremental React migration,
not a fully React-state-driven SPA. Do not rerender controller-owned subtrees
without migrating their state and event handling together.

From the repository root, run `node --test tests/ci/*.cjs` for controller tests.
For the browser smoke test, serve `frontend/dist` on port 5174 with Python's HTTP
server and run `python3 tests/frontend/test_react_pages.py` with Playwright installed.
The browser test mocks APIs; the integration harness tests real backend contracts.
