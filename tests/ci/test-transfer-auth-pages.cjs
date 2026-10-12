const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

function loginHarness(loginOk = true, sessionOk = true, confirmationOk = true) {
  const html = readFileSync(resolve(__dirname,
    '../../services/user-access-service/src/main/resources/static/login.html'), 'utf8');
  const nodes = {};
  for (const id of ['loginForm', 'username', 'password', 'submit', 'status', 'toggle']) {
    nodes[id] = {value: id === 'username' ? ' alice ' : 'password',
      type: 'password', addEventListener(type, callback) { this[type] = callback; },
      setAttribute() {}, removeAttribute() {}};
  }
  const requests = [], navigations = [];
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], {
    document: {getElementById: id => nodes[id]},
    location: {port: '8080', protocol: 'http:', origin: 'http://localhost:8080',
      assign: url => navigations.push(url)},
    URL, URLSearchParams, AbortController, setTimeout, clearTimeout, Error, TypeError,
    fetch: async (url, options) => {
      requests.push({url, options});
      return requests.length === 1
        ? {ok: loginOk, status: loginOk ? 200 : 401,
          json: async () => ({accessToken: 'test-token', tokenType: 'Bearer'})}
        : requests.length === 2 ? {ok: sessionOk, status: sessionOk ? 204 : 401}
        : {ok: confirmationOk, status: confirmationOk ? 200 : 401};
    }
  });
  return {nodes, requests, navigations,
    submit: () => nodes.loginForm.submit({preventDefault() {}})};
}

test('login redirects only after the server establishes the browser cookie', async () => {
  const app = loginHarness();
  await app.submit();
  assert.equal(app.requests[0].url, '/user-access/api/auth/login');
  assert.equal(JSON.parse(app.requests[0].options.body).username, 'alice');
  assert.equal(app.requests[1].url, '/api/v1/session');
  assert.equal(app.requests[1].options.headers.Authorization, 'Bearer test-token');
  assert.equal(app.requests[2].url, '/api/v1/session');
  assert.equal(app.requests[2].options.headers, undefined);
  assert.deepEqual(app.navigations, ['http://localhost:8080/upload.html']);
  assert.equal(app.nodes.password.value, '');
});

test('invalid credentials and failed cookie establishment never redirect', async () => {
  for (const app of [loginHarness(false), loginHarness(true, false), loginHarness(true, true, false)]) {
    await app.submit();
    assert.equal(app.navigations.length, 0);
    assert.equal(app.nodes.submit.disabled, false);
    assert.equal(app.nodes.status.className, 'status error');
  }
});

test('cookie upload sends the server CSRF token; logout clears the server session', async () => {
  const calls = [], destinations = [], nodes = {logout: {addEventListener(t, f) { this[t] = f; }}};
  const context = {
    window: {}, Headers, Error, Date, setTimeout: () => 0,
    document: {getElementById: id => nodes[id]},
    location: {replace: url => destinations.push(url)},
    alert: message => { throw new Error(message); },
    fetch: async (url, options) => {
      calls.push({url, options});
      return {ok: true, status: 200,
        json: async () => ({csrfToken: 'masked-csrf', csrfHeader: 'X-XSRF-TOKEN',
          expiresAt: '2099-01-01T00:00:00Z', expiresInSeconds: 1800})};
    }
  };
  const script = readFileSync(resolve(__dirname,
    '../../services/transfer-service/src/main/resources/static/auth.js'), 'utf8');
  vm.runInNewContext(script, context);
  await context.window.mmivAuth.ready;
  await context.window.mmivAuth.fetch('/api/v1/transfers', {method: 'POST'});
  assert.equal(calls[1].options.headers.get('X-XSRF-TOKEN'), 'masked-csrf');
  assert.equal(calls[1].options.credentials, 'same-origin');
  await nodes.logout.click();
  assert.equal(calls[2].options.method, 'DELETE');
  assert.deepEqual(destinations, ['/login.html']);
});

function signupHarness(status = 201) {
  const page = readFileSync(resolve(__dirname,
    '../../services/user-access-service/src/main/resources/static/signup.html'), 'utf8');
  const nodes = {};
  for (const id of ['signupForm', 'username', 'password', 'confirm', 'submit', 'status', 'toggle']) {
    nodes[id] = {value: id === 'username' ? 'alice' : 'password123', type: 'password',
      addEventListener(t, f) { this[t] = f; }, setAttribute() {}, removeAttribute() {}};
  }
  const requests = [], destinations = [];
  vm.runInNewContext(page.match(/<script>([\s\S]*?)<\/script>/)[1], {
    document: {getElementById: id => nodes[id]},
    location: {port: '8080', protocol: 'http:', assign: url => destinations.push(url)},
    TextEncoder, AbortController, setTimeout, clearTimeout, Error, TypeError,
    fetch: async (url, options) => { requests.push({url, options}); return {status}; }
  });
  return {nodes, requests, destinations,
    submit: () => nodes.signupForm.submit({preventDefault() {}})};
}

test('signup creates an account and returns to sign in', async () => {
  const app = signupHarness();
  await app.submit();
  assert.equal(app.requests[0].url, '/user-access/api/auth/signup');
  assert.deepEqual(JSON.parse(app.requests[0].options.body), {username: 'alice', password: 'password123'});
  assert.deepEqual(app.destinations, ['/login.html?registered=1']);
});

test('signup handles duplicate usernames and rejects mismatched passwords locally', async () => {
  const duplicate = signupHarness(409);
  await duplicate.submit();
  assert.equal(duplicate.destinations.length, 0);
  assert.match(duplicate.nodes.status.textContent, /already taken/);
  const mismatch = signupHarness();
  mismatch.nodes.confirm.value = 'something-else';
  await mismatch.submit();
  assert.equal(mismatch.requests.length, 0);
  assert.match(mismatch.nodes.status.textContent, /do not match/);
});
