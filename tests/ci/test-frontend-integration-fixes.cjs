const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, existsSync } = require('node:fs');
const vm = require('node:vm');

const authSource = readFileSync('frontend/js/mmiv-auth.js', 'utf8');
const loginSource = readFileSync('frontend/js/login.js', 'utf8');
const managerSource = readFileSync('frontend/js/file-manager.js', 'utf8');
const homeSource = readFileSync('frontend/js/home.js', 'utf8');
const activitySource = readFileSync('frontend/js/activity.js', 'utf8');
const adminSource = readFileSync('frontend/js/admin.js', 'utf8');
const signupHtml = readFileSync('frontend/pages/signup.html', 'utf8');
const gatewayPom = readFileSync('services/api-gateway/pom.xml', 'utf8');

const TOKEN = 'a1b2c3d4'.repeat(8);
const flush = async () => {
  for (let i = 0; i < 12; i += 1) await new Promise(setImmediate);
};

function node(tagName) {
  return {
    tagName,
    className: '',
    textContent: '',
    value: '',
    disabled: false,
    hidden: false,
    focused: false,
    attributes: {},
    listeners: {},
    children: [],
    append(...items) {
      this.children.push(...items);
    },
    replaceChildren(...items) {
      this.children = items;
    },
    setAttribute(name, value) {
      this.attributes[name] = String(value);
    },
    removeAttribute(name) {
      delete this.attributes[name];
    },
    addEventListener(event, handler) {
      this.listeners[event] = handler;
    },
    focus() {
      this.focused = true;
    },
  };
}

/* ------------------- invitation token carried through sign-in ---------------- */

/** Loads mmiv-auth.js with an unusable session, so it leaves for the login page. */
function loadAuth(options = {}) {
  const replaced = [];
  const logged = [];
  const storage = [];

  const location = { replace: (url) => replaced.push(url) };
  // A browser always reports a string here; a host that omits the property
  // must not turn the redirect into a crash.
  if (!options.withoutHash) location.hash = options.hash || '';

  const context = {
    window: {},
    document: { getElementById: () => null, addEventListener() {} },
    location,
    fetch: async () => ({ status: 401, ok: false }),
    URLSearchParams,
    Headers,
    alert() {},
    setTimeout,
    clearTimeout,
    console: { log: (...args) => logged.push(args), warn() {}, error() {} },
    localStorage: {
      getItem: () => storage.push('localStorage'),
      setItem: () => storage.push('localStorage'),
    },
    sessionStorage: {
      getItem: () => storage.push('sessionStorage'),
      setItem: () => storage.push('sessionStorage'),
    },
  };

  vm.runInNewContext(authSource, context);
  return { replaced, logged, storage, auth: context.window.mmivAuth };
}

test('a valid invitation survives the redirect to the login page', async () => {
  const app = loadAuth({ hash: '#invite=' + TOKEN });
  await flush();
  assert.deepEqual(app.replaced, ['/pages/login.html#invite=' + TOKEN]);
});

test('a fragment that is not a well-formed token is dropped, never forwarded', async () => {
  const fragments = [
    '',
    '#invite=',
    '#invite=' + TOKEN.slice(0, 63),
    '#invite=' + TOKEN + 'ff',
    '#invite=' + 'Z'.repeat(64),
    '#invite=' + TOKEN.toUpperCase(),
    '#other=' + TOKEN,
    '#browse-files',
  ];

  for (const hash of fragments) {
    const app = loadAuth({ hash });
    await flush();
    assert.deepEqual(app.replaced, ['/pages/login.html'], 'forwarded ' + JSON.stringify(hash));
  }
});

test('a host that reports no fragment at all still redirects', async () => {
  const app = loadAuth({ withoutHash: true });
  await flush();
  assert.deepEqual(app.replaced, ['/pages/login.html']);
});

test('the invitation is neither logged nor written to browser storage', async () => {
  const app = loadAuth({ hash: '#invite=' + TOKEN });
  await flush();
  assert.equal(app.storage.length, 0);
  const text = JSON.stringify(app.logged);
  assert.ok(!text.includes(TOKEN), 'the token reached the console');
});

test('login() still stops the caller after it leaves', async () => {
  const app = loadAuth({ hash: '#invite=' + TOKEN });
  await flush();
  assert.throws(() => app.auth.login(), /sign in again/);
});

/** Drives the login page's own submit handler. */
async function submitLogin(options = {}) {
  const assigned = [];
  const logged = [];
  const elements = new Map();
  const byId = (id) => {
    if (!elements.has(id)) elements.set(id, node('DIV'));
    return elements.get(id);
  };

  const submitButton = node('BUTTON');
  const form = node('FORM');
  form.querySelector = (selector) => (selector === 'button[type="submit"]' ? submitButton : null);
  elements.set('login-form', form);

  byId('username').value = 'alice';
  byId('password').value = 'correct-horse';

  const respond = (url, settings) => {
    if (url === '/user-access/api/auth/login')
      return { status: 200, body: { accessToken: 'jwt', tokenType: 'Bearer' } };
    if (url === '/api/v1/session' && settings.method === 'POST') return { status: 204, body: {} };
    if (url === '/api/v1/session') return { status: 200, body: { userId: 'u-alice' } };
    throw new Error('Unexpected request ' + url);
  };

  const location = { protocol: 'http:', assign: (url) => assigned.push(url) };
  if (!options.withoutHash) location.hash = options.hash || '';

  const context = {
    document: {
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
    },
    location,
    fetch: async (url, settings = {}) => {
      const result = respond(url, settings);
      return {
        ok: result.status < 300,
        status: result.status,
        json: async () => result.body,
        headers: new Headers(),
      };
    },
    URLSearchParams,
    TextEncoder,
    AbortController,
    setTimeout,
    clearTimeout,
    console: { log: (...args) => logged.push(args), warn() {}, error() {} },
  };

  vm.runInNewContext(loginSource, context);
  await form.listeners.submit({ preventDefault() {} });
  await flush();
  return { assigned, logged };
}

test('signing in hands an invitation back to the page that redeems it', async () => {
  const app = await submitLogin({ hash: '#invite=' + TOKEN });
  assert.deepEqual(app.assigned, ['/pages/invite.html#invite=' + TOKEN]);
});

test('signing in without an invitation opens the workspace as before', async () => {
  for (const hash of ['', '#browse-files', '#invite=not-a-token']) {
    const app = await submitLogin({ hash });
    assert.deepEqual(app.assigned, ['/pages/home.html'], 'redirected for ' + JSON.stringify(hash));
  }
});

test('a host that reports no fragment still opens the workspace', async () => {
  const app = await submitLogin({ withoutHash: true });
  assert.deepEqual(app.assigned, ['/pages/home.html']);
});

test('the login page does not log the invitation it carries', async () => {
  const app = await submitLogin({ hash: '#invite=' + TOKEN });
  assert.ok(!JSON.stringify(app.logged).includes(TOKEN), 'the token reached the console');
});

/* --------------------- signup assets belong to the Gateway ------------------ */

test('the signup scripts are loaded from the Gateway-owned frontend', () => {
  for (const asset of ['feedback.js', 'english-validation.js']) {
    const browserPath = '../js/' + asset;
    assert.ok(
      signupHtml.includes('"' + browserPath + '"'),
      'signup no longer loads ' + browserPath,
    );
    assert.ok(existsSync('frontend/js/' + asset), 'missing frontend/js/' + asset);
  }
});

test('the Gateway packages the shared frontend JavaScript directory', () => {
  assert.match(gatewayPom, /<include>js\/\*\*<\/include>/);
});

/* --------------------- uploads are offered only where allowed ---------------- */

/** Runs file-manager.js against a profile carrying the given destinations. */
async function loadFileManager(destinations) {
  const elements = new Map();
  const menus = [node('DETAILS'), node('DETAILS')];
  const byId = (id) => {
    if (!elements.has(id)) elements.set(id, node('DIV'));
    return elements.get(id);
  };

  const context = {
    document: {
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
      querySelectorAll: (selector) => (selector === '.upload-menu' ? menus : []),
    },
    window: {
      mmivAuth: {
        ready: Promise.resolve(),
        fetch: async (url) => {
          if (url === '/api/access/me')
            return { ok: true, status: 200, json: async () => ({ role: 'USER', destinations }) };
          throw new Error('Unexpected request ' + url);
        },
      },
    },
    location: { search: '' },
    URLSearchParams,
    IntersectionObserver: undefined,
    encodeURIComponent,
    Date,
  };

  vm.runInNewContext(managerSource, context);
  await flush();
  return { menus, element: byId };
}

test('a read-only account is not offered the upload menus', async () => {
  const app = await loadFileManager([
    { destination: 'SW001', permission: 'READ' },
    { destination: 'SW002', permission: 'READ' },
  ]);

  for (const menu of app.menus) assert.equal(menu.hidden, true);
  assert.equal(app.element('destination').disabled, true);
  assert.equal(app.element('folderDestination').disabled, true);
  assert.equal(app.element('destination').children[0].textContent, 'No upload destinations');
});

test('one writable destination keeps the upload menus', async () => {
  const app = await loadFileManager([
    { destination: 'SW001', permission: 'READ' },
    { destination: 'SW002', permission: 'WRITE' },
  ]);

  for (const menu of app.menus) assert.equal(menu.hidden, false);
  assert.equal(app.element('destination').disabled, false);
  assert.equal(app.element('folderDestination').disabled, false);
  // Only the writable folder is offered as a destination.
  assert.deepEqual(
    app.element('destination').children.map((option) => option.value),
    ['', 'SW002'],
  );
});

test('frontend reads profile and reporting data without traversing Transfer', () => {
  for (const script of [homeSource, activitySource, adminSource, managerSource]) {
    for (const legacy of [
      '/api/v1/portal/me',
      '/api/v1/portal/history',
      '/api/v1/portal/logs',
    ]) {
      assert.equal(script.includes(legacy), false, `legacy read remains: ${legacy}`);
    }
  }
  assert.ok(homeSource.includes("'/api/access/me'"));
  assert.ok(homeSource.includes("'/api/history?page=0'"));
  assert.ok(activitySource.includes("'/api/history'"));
  assert.ok(adminSource.includes("'/api/dashboard'"));
});
