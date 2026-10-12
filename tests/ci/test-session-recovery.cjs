const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const script = readFileSync(resolve(__dirname,
  '../../services/transfer-service/src/main/resources/static/auth.js'), 'utf8');

function start(fetch) {
  const delays = [];
  const context = {
    window: {}, Headers, Error,
    // A wrong client clock must not control server session expiry.
    Date: class extends Date { static now() { return Date.now() + 86400000; } },
    setTimeout: (callback, delay) => { delays.push(delay); },
    document: {getElementById: () => null},
    location: {replace() {}}, fetch
  };
  vm.runInNewContext(script, context);
  return {api: context.window.mmivAuth, delays};
}
const session = token => ({ok: true, status: 200, json: async () => ({
  csrfToken: token, csrfHeader: 'X-XSRF-TOKEN',
  expiresAt: new Date(Date.now() + 60000).toISOString(), expiresInSeconds: 60
})});

test('session expiry uses server duration even when the client clock is wrong', async () => {
  const app = start(async () => session('csrf'));
  await app.api.ready;
  assert.deepEqual(app.delays, [60000]);
});

test('a rejected CSRF request refreshes the token and retries exactly once', async () => {
  const calls = [];
  const app = start(async (url, options) => {
    calls.push({url, csrf: options.headers && options.headers.get('X-XSRF-TOKEN')});
    if (calls.length === 1) return session('old');
    if (calls.length === 2) return {ok: false, status: 403,
      clone: () => ({json: async () => ({error: 'CSRF_INVALID'})})};
    if (calls.length === 3) return session('new');
    return {ok: true, status: 200};
  });
  await app.api.ready;
  const result = await app.api.fetch('/api/v1/transfers', {method: 'POST', body: 'fixture'});
  assert.equal(result.status, 200);
  assert.equal(calls.length, 4);
  assert.equal(calls[1].csrf, 'old');
  assert.equal(calls[2].url, '/api/v1/session');
  assert.equal(calls[3].csrf, 'new');
});

test('an authorization rejection is never replayed', async () => {
  let requests = 0;
  const app = start(async () => ++requests === 1 ? session('csrf')
    : {ok: false, status: 403, clone: () => ({json: async () => ({error: 'ACCESS_DENIED'})})});
  await app.api.ready;
  const result = await app.api.fetch('/api/v1/transfers', {method: 'POST'});
  assert.equal(result.status, 403);
  assert.equal(requests, 2);
});
