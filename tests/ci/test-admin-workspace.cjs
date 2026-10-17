const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('frontend/js/admin.js', 'utf8');
const html = (readFileSync('frontend/pages/admin.html', 'utf8') + readFileSync('frontend/src/pages/admin.jsx', 'utf8').replaceAll('className=', 'class=').replaceAll('htmlFor=', 'for='));

const PROFILE = '/api/access/me';
const USERS = '/api/v1/portal/admin/users';
const FOLDERS = '/api/v1/portal/admin/folders';
const LOGS = '/api/dashboard';
const ADMIN_REPORTS = '/api/admin/reports';
const REPORT_ID = '123e4567-e89b-42d3-a456-426614174000';

const alice = {
  userId: 'u-alice',
  username: 'alice',
  role: 'USER',
  active: true,
  destinations: [{ destination: 'SW001', permission: 'WRITE' }],
};
const bob = { userId: 'u-bob', username: 'bob', role: 'ADMIN', active: true, destinations: [] };
const carol = {
  userId: 'u-carol',
  username: 'carol',
  role: 'USER',
  active: false,
  destinations: [],
};

// One record per derived level: an ordinary operation, a 4xx request, a failed
// operation and a 5xx request.
const records = [
  {
    _id: '1',
    sourceService: 'transfer-service',
    occurredAt: '2026-10-14T10:42:00Z',
    operation: 'UPLOAD',
    data: {
      actorId: 'u-alice',
      operation: 'UPLOAD',
      success: true,
      folder: 'SW001',
      fileName: 'clip.mp4',
      sizeBytes: 2048,
    },
  },
  {
    _id: '2',
    sourceService: 'api-gateway',
    occurredAt: '2026-10-14T09:18:00Z',
    operation: 'HTTP_REQUEST',
    data: { method: 'GET', status: 404, durationMs: 7 },
  },
  {
    _id: '3',
    sourceService: 'integrity-service',
    occurredAt: '2026-10-13T16:30:00Z',
    operation: 'FILE_OPERATION',
    data: { actorId: 'u-carol', success: false, errorType: 'HASH_MISMATCH' },
  },
  {
    _id: '4',
    sourceService: 'transfer-service',
    occurredAt: '2026-10-12T11:05:00Z',
    operation: 'HTTP_REQUEST',
    data: { method: 'POST', status: 503, durationMs: 900 },
  },
];

function node(tagName) {
  return {
    tagName,
    children: [],
    className: '',
    textContent: '',
    value: '',
    checked: false,
    disabled: false,
    hidden: false,
    colSpan: 0,
    dataset: {},
    attributes: {},
    listeners: {},
    opened: false,
    append(...items) {
      this.children.push(...items);
    },
    replaceChildren(...items) {
      this.children = items;
    },
    setAttribute(name, value) {
      this.attributes[name] = value;
    },
    addEventListener(event, handler) {
      this.listeners[event] = handler;
    },
    showModal() {
      this.opened = true;
    },
    // Mirrors the spec: closing a closed dialog fires nothing, which is what
    // stops the confirmation handlers from recursing.
    close() {
      if (!this.opened) return;
      this.opened = false;
      if (this.listeners.close) this.listeners.close({});
    },
    removeEventListener(event) {
      delete this.listeners[event];
    },
    focus() {},
    select() {
      this.selected = true;
    },
    setSelectionRange() {},
  };
}

/** The timestamp shape the log table uses, computed in the same local zone. */
function stamp(value) {
  const date = new Date(value);
  const pad = (part) => String(part).padStart(2, '0');
  return (
    date.getFullYear() +
    '-' +
    pad(date.getMonth() + 1) +
    '-' +
    pad(date.getDate()) +
    ' ' +
    pad(date.getHours()) +
    ':' +
    pad(date.getMinutes())
  );
}

async function page(options = {}) {
  const role = options.role || 'ADMIN';
  const nodes = new Map();
  const requests = [];
  const mutations = [];
  const navigations = [];
  const dialogs = [];
  const copied = [];
  const execCommands = [];
  const execCommandWorks = options.execCommand !== false;
  const clipboard = options.clipboard !== false;
  const radios = ['READ', 'WRITE'].map((value) => {
    const input = node('INPUT');
    input.value = value;
    return input;
  });
  const workspace = node('SECTION');
  const selectors = {
    '.admin-workspace': workspace,
    '.account-avatar': node('DIV'),
    '.account-copy span': node('SPAN'),
  };
  const byId = (id) => {
    if (!nodes.has(id)) nodes.set(id, node('DIV'));
    return nodes.get(id);
  };
  // The page's selects open on their first option, so the filters start at "all".
  byId('logsFilter').value = 'all';
  byId('invitationStatus').value = 'all';

  const respond =
    options.respond ||
    ((path) => {
      if (path === PROFILE)
        return { status: 200, body: { userId: 'u-root', username: 'root', role } };
      if (path.startsWith(USERS)) return { status: 200, body: [alice, bob, carol] };
      if (path === FOLDERS) return { status: 200, body: ['SW001', 'semester2', 'Archive 2026'] };
      if (path.startsWith(LOGS)) return { status: 200, body: { logs: records, page: 0 } };
      if (path.startsWith(ADMIN_REPORTS))
        return {
          status: 200,
          body: {
            content: [
              {
                migrationId: REPORT_ID,
                initiatedBy: 'u-alice',
                startedAt: '2026-10-14T10:00:00Z',
                status: 'COMPLETED',
                summary: { totalFiles: 2, successRate: 100 },
              },
            ],
            number: 0,
            totalPages: 1,
            totalElements: 1,
          },
        };
      throw new Error('Unexpected request ' + path);
    });

  const fetchMock = async (url, settings = {}) => {
    requests.push(url);
    mutations.push({ url, settings });
    // The settings travel with the path because several endpoints share one URL
    // and differ only by method.
    const result = respond(url, settings);
    if (result && result.reject) throw new Error(result.reject);
    // A gated response lets a test observe the page mid-request.
    if (result && result.pending) await result.pending;
    return { ok: result.status < 300, status: result.status, json: async () => result.body };
  };

  vm.runInNewContext(source, {
    document: {
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
      querySelectorAll: (selector) => (selector === 'input[name="permission"]' ? radios : []),
      querySelector: (selector) => selectors[selector] || null,
      execCommand: (command) => {
        execCommands.push(command);
        return execCommandWorks;
      },
    },
    window: {
      mmivAuth: { ready: Promise.resolve(), fetch: fetchMock },
      openUserManagement: (username) => dialogs.push(username),
    },
    location: {
      origin: 'https://mmiv.example',
      replace: (url) => navigations.push(url),
      assign: (url) => navigations.push(url),
    },
    navigator: clipboard
      ? {
          clipboard: {
            async writeText(text) {
              copied.push(text);
            },
          },
        }
      : {},
    URL,
    encodeURIComponent,
  });

  const flush = async () => {
    for (let i = 0; i < 12; i += 1) await new Promise(setImmediate);
  };
  await flush();

  const rows = (id) => byId(id).children;
  const text = (id) => byId(id).textContent;

  return {
    requests,
    mutations,
    navigations,
    dialogs,
    copied,
    execCommands,
    radios,
    workspace,
    element: byId,
    rows,
    text,
    flush,
    async trigger(id, event = 'click', detail = { preventDefault() {} }) {
      byId(id).listeners[event](detail);
      await flush();
    },
    async click(target) {
      target.listeners.click();
      await flush();
    },
    /** Answers the open confirmation dialog the way an operator would. */
    async answer(accept, how = 'button') {
      if (how === 'escape') byId('confirmDialog').close();
      else if (accept) byId('confirmAccept').listeners.click();
      else byId('confirmCancel').listeners.click();
      await flush();
    },
    /** Picks one of the permission radios, as a click would. */
    async choosePermission(value) {
      radios.forEach((input) => {
        input.checked = input.value === value;
      });
      const chosen = radios.find((input) => input.value === value);
      if (chosen && chosen.listeners.change) chosen.listeners.change({});
      await flush();
    },
    /** Selects an account in the management dialog. */
    async selectUser(username) {
      byId('selectedUser').value = username;
      byId('selectedUser').listeners.change({});
      await flush();
    },
    /** Selects the folder the permission controls act on. */
    async selectFolder(folder) {
      byId('permissionFolder').value = folder;
      byId('permissionFolder').listeners.change({});
      await flush();
    },
    counts(path) {
      return requests.filter((url) => url.startsWith(path)).length;
    },
    /** Requests sent with a method other than GET, i.e. the mutations. */
    writes() {
      return mutations.filter((entry) => entry.settings.method && entry.settings.method !== 'GET');
    },
  };
}

/* ------------------------------- authorization ------------------------------ */

test('an administrator loads users, folders and logs from the backend', async () => {
  const app = await page();
  assert.deepEqual(
    [...app.requests].sort(),
    [PROFILE, USERS + '?q=', FOLDERS, LOGS + '?page=0'].sort(),
  );
  assert.equal(app.navigations.length, 0);
  assert.equal(app.element('account').textContent, 'root');

  const users = app.rows('usersTable');
  assert.equal(users.length, 3);
  assert.equal(users[0].children[0].children[0].textContent, 'alice');
  assert.equal(users[0].children[0].children[0].className, 'admin-user-name');
  assert.equal(users[0].children[1].children[0].className, 'admin-badge badge-user');
  assert.equal(users[0].children[1].children[0].textContent, 'User');
  assert.equal(users[0].children[2].children[0].textContent, 'Active');
  assert.equal(users[1].children[1].children[0].className, 'admin-badge badge-admin');
  assert.equal(users[1].children[1].children[0].textContent, 'Administrator');
  // Inactive accounts are reported as disabled, not hidden.
  assert.equal(users[2].children[2].children[0].className, 'admin-badge badge-disabled');
  assert.equal(users[2].children[2].children[0].textContent, 'Disabled');
  assert.equal(app.text('usersMeta'), '3 users');

  const folders = app.rows('foldersTable');
  assert.equal(folders.length, 3);
  assert.equal(folders[0].children[0].children[0].children[1], 'SW001');
  assert.equal(folders[0].children[1].children[0].textContent, '/SW001/');
  // The folders endpoint reports no status, so none is invented.
  assert.equal(folders[0].children[2].children[0].textContent, '—');
  // The management phase activates the row action; it locks only while a
  // request is in flight.
  assert.equal(folders[0].children[3].children[0].disabled, false);
  assert.equal(app.text('foldersMeta'), '3 folders');

  assert.equal(app.rows('logsTable').length, 4);
  assert.match(app.text('status'), /^$/);
});

test('a non-administrator is refused and no administrative request is issued', async () => {
  const app = await page({ role: 'USER' });
  assert.deepEqual(app.requests, [PROFILE]);
  assert.equal(app.counts('/api/v1/portal/admin'), 0);
  assert.equal(app.counts(LOGS), 0);
  assert.equal(app.workspace.hidden, true);
  assert.match(app.text('status'), /Administrator access required/);
  assert.deepEqual(app.navigations, ['/pages/home.html']);
  // Nothing administrative is on screen, not even between requests.
  ['usersTable', 'foldersTable', 'logsTable', 'invitationsTable'].forEach((id) =>
    assert.equal(app.rows(id).length, 0, id + ' must be empty'),
  );
});

test('an unreadable session reports the failure without inventing data', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { reject: 'Please sign in again.' };
      throw new Error('Unexpected request ' + path);
    },
  });
  assert.deepEqual(app.requests, [PROFILE]);
  assert.equal(app.text('status'), 'Please sign in again.');
  assert.equal(app.text('usersMeta'), 'Unavailable');
  assert.equal(app.text('foldersMeta'), 'Unavailable');
  assert.equal(app.text('logsMeta'), 'Unavailable');
  assert.equal(app.rows('usersTable')[0].children[0].textContent, 'Users are unavailable.');
  assert.equal(app.rows('foldersTable')[0].children[0].textContent, 'Folders are unavailable.');
  assert.equal(app.rows('logsTable')[0].children[0].textContent, 'System logs are unavailable.');
});

/* ------------------------------ search and filters --------------------------- */

test('user search uses the verified q parameter', async () => {
  const matching = (url) => {
    const query = decodeURIComponent(url.split('q=')[1]);
    return {
      status: 200,
      body: [alice, bob, carol].filter((user) => user.username.includes(query)),
    };
  };
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) return matching(path);
      if (path === FOLDERS) return { status: 200, body: [] };
      if (path.startsWith(LOGS)) return { status: 200, body: { logs: [], page: 0 } };
      throw new Error('Unexpected request ' + path);
    },
  });
  assert.ok(app.requests.includes(USERS + '?q='));

  app.element('userSearch').value = 'ali';
  await app.trigger('searchUsers');
  assert.equal(app.requests.at(-1), USERS + '?q=ali');
  assert.equal(app.rows('usersTable').length, 1);
  assert.equal(app.rows('usersTable')[0].children[0].children[0].textContent, 'alice');

  // Enter in the search field runs the same server-side search.
  app.element('userSearch').value = 'nobody';
  await app.trigger('userSearch', 'keydown', { key: 'Enter', preventDefault() {} });
  assert.equal(app.requests.at(-1), USERS + '?q=nobody');
  assert.equal(app.rows('usersTable').length, 1);
  assert.equal(app.rows('usersTable')[0].children[0].textContent, 'No users match this search.');
});

test('folder search filters the loaded list without another request', async () => {
  const app = await page();
  const before = app.requests.length;
  app.element('folderSearch').value = 'sem';
  await app.trigger('folderSearch', 'input');
  assert.equal(app.requests.length, before);
  assert.equal(app.rows('foldersTable').length, 1);
  assert.equal(app.rows('foldersTable')[0].children[0].children[0].children[1], 'semester2');
  assert.equal(app.text('foldersMeta'), '1 of 3 folders');

  app.element('folderSearch').value = 'zzz';
  await app.trigger('folderSearch', 'input');
  assert.equal(
    app.rows('foldersTable')[0].children[0].textContent,
    'No folders match this search.',
  );
});

test('log level and search filter the records already on the page', async () => {
  const app = await page();
  const before = app.requests.length;

  app.element('logsFilter').value = 'ERROR';
  await app.trigger('logsFilter', 'change');
  let rows = app.rows('logsTable');
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[1].children[0].textContent, 'ERROR');
  assert.equal(rows[1].children[1].children[0].className, 'admin-badge badge-error');
  assert.equal(app.text('logsMeta'), '2 of 4 records · page 1');

  app.element('logsFilter').value = 'WARNING';
  await app.trigger('logsFilter', 'change');
  rows = app.rows('logsTable');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[2].textContent, 'HTTP_REQUEST');
  assert.equal(
    rows[0].children[4].textContent,
    'service: api-gateway · method: GET · status: 404 · durationMs: 7',
  );

  app.element('logsFilter').value = 'all';
  app.element('logsSearch').value = 'clip';
  await app.trigger('logsSearch', 'input');
  rows = app.rows('logsTable');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[3].textContent, 'u-alice');

  app.element('logsSearch').value = 'nothing-matches';
  await app.trigger('logsSearch', 'input');
  assert.equal(
    app.rows('logsTable')[0].children[0].textContent,
    'No records on this page match the current filter.',
  );
  assert.equal(app.requests.length, before);
});

test('log timestamps and derived levels come from the record itself', async () => {
  const app = await page();
  const rows = app.rows('logsTable');
  assert.equal(rows[0].children[0].textContent, stamp('2026-10-14T10:42:00Z'));
  assert.match(rows[0].children[0].textContent, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  assert.equal(rows[0].children[1].children[0].textContent, 'INFO');
  assert.equal(rows[0].children[2].textContent, 'UPLOAD');
  assert.equal(rows[0].children[3].textContent, 'u-alice');
  // A 404 request is a warning, a 5xx request is an error, and a record with no
  // actor shows a dash rather than a fabricated name.
  assert.equal(rows[1].children[1].children[0].textContent, 'WARNING');
  assert.equal(rows[1].children[3].textContent, '—');
  assert.equal(rows[3].children[1].children[0].textContent, 'ERROR');
});

/* --------------------------------- pagination -------------------------------- */

test('log paging asks the endpoint for the page it shows', async () => {
  const full = Array.from({ length: 100 }, (unused, index) => ({
    _id: String(index),
    sourceService: 'transfer-service',
    occurredAt: '2026-10-14T10:42:00Z',
    operation: 'UPLOAD',
    data: { success: true },
  }));
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) return { status: 200, body: [] };
      if (path === FOLDERS) return { status: 200, body: [] };
      const requested = Number(path.split('page=')[1]);
      return {
        status: 200,
        body: { logs: requested === 0 ? full : full.slice(0, 3), page: requested },
      };
    },
  });

  assert.equal(app.element('logsPrevious').disabled, true);
  assert.equal(app.element('logsNext').disabled, false);
  assert.equal(app.text('logsMeta'), '100 records · page 1');

  await app.trigger('logsNext');
  assert.equal(app.requests.at(-1), LOGS + '?page=1');
  assert.equal(app.element('logsPrevious').disabled, false);
  // A short page is the only end-of-list signal the endpoint provides.
  assert.equal(app.element('logsNext').disabled, true);
  assert.equal(app.text('logsMeta'), '3 records · page 2');

  await app.trigger('logsPrevious');
  assert.equal(app.requests.at(-1), LOGS + '?page=0');
  assert.equal(app.element('logsPrevious').disabled, true);
});

test('refresh reloads the page currently on screen', async () => {
  const app = await page();
  await app.trigger('refreshLogs');
  assert.equal(app.requests.at(-1), LOGS + '?page=0');
});

/* ------------------------------ empty and error states ----------------------- */

test('empty responses show an empty state and never demo records', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) return { status: 200, body: [] };
      if (path === FOLDERS) return { status: 200, body: [] };
      return { status: 200, body: { logs: [], page: 0 } };
    },
  });
  assert.equal(app.rows('usersTable').length, 1);
  assert.equal(app.rows('usersTable')[0].children[0].textContent, 'No user accounts were found.');
  assert.equal(
    app.rows('foldersTable')[0].children[0].textContent,
    'No folders are registered yet.',
  );
  assert.equal(app.rows('logsTable')[0].children[0].textContent, 'No log records on this page.');
  assert.equal(app.text('usersMeta'), '0 users');
  assert.equal(app.text('foldersMeta'), '0 folders');
  assert.equal(app.element('logsNext').disabled, true);
});

test('a failing endpoint clears its table and reports the status', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) return { status: 503, body: {} };
      if (path === FOLDERS) return { status: 403, body: {} };
      return { status: 503, body: {} };
    },
  });
  assert.equal(app.rows('usersTable').length, 1);
  assert.equal(
    app.rows('usersTable')[0].children[0].textContent,
    'The account service is unavailable. Please try again later.',
  );
  assert.equal(app.text('usersMeta'), 'Unavailable');
  assert.equal(
    app.rows('foldersTable')[0].children[0].textContent,
    'Administrator access required.',
  );
  assert.equal(
    app.rows('logsTable')[0].children[0].textContent,
    'The reporting service is unavailable. Please try again later.',
  );
  assert.equal(app.element('logsNext').disabled, true);
  assert.equal(app.element('selectedUser').children.length, 1);
});

test('a failed search replaces the previous result instead of keeping it', async () => {
  let failing = false;
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) {
        if (failing) return { status: 500, body: {} };
        return { status: 200, body: [alice] };
      }
      if (path === FOLDERS) return { status: 200, body: [] };
      return { status: 200, body: { logs: [], page: 0 } };
    },
  });
  assert.equal(app.rows('usersTable')[0].children[0].children[0].textContent, 'alice');

  failing = true;
  app.element('userSearch').value = 'alice';
  await app.trigger('searchUsers');
  const rows = app.rows('usersTable');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[0].textContent, 'Users could not be loaded. Please try again.');
  assert.ok(!JSON.stringify(rows).includes('alice'));
  assert.equal(app.element('selectedUser').children.length, 1);
});

test('a malformed response is an error, not an empty table', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'root', role: 'ADMIN' } };
      if (path.startsWith(USERS)) return { status: 200, body: { users: [] } };
      if (path === FOLDERS) return { status: 200, body: 'SW001' };
      return { status: 200, body: { records: [] } };
    },
  });
  assert.equal(
    app.rows('usersTable')[0].children[0].textContent,
    'Users could not be read. Please reload.',
  );
  assert.equal(
    app.rows('foldersTable')[0].children[0].textContent,
    'Folders could not be read. Please reload.',
  );
  assert.equal(
    app.rows('logsTable')[0].children[0].textContent,
    'System logs could not be read. Please reload.',
  );
});

/* --------------------------------- invitations ------------------------------- */

test('invitations report their unavailability and request nothing', async () => {
  const app = await page();
  assert.ok(!app.requests.some((url) => url.includes('invitation')));
  const rows = app.rows('invitationsTable');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[0].colSpan, 5);
  assert.match(rows[0].children[0].textContent, /Invitation history is unavailable/);
  assert.equal(app.text('invitationsMeta'), 'Unavailable');
  assert.equal(app.element('invitationSearch').disabled, true);
  assert.equal(app.element('invitationStatus').disabled, true);
  // The tab and its dialog stay in place for the mutation phase.
  assert.ok(html.includes('id="invitationDialog"'));
  assert.ok(html.includes('id="openInvitationDialog"'));
});

/* ------------------------------ permission surface --------------------------- */

test('managing a user reflects their real stored grant', async () => {
  const app = await page();
  const manage = app.rows('usersTable')[0].children[3].children[0];
  assert.equal(manage.dataset.user, 'alice');

  await app.click(manage);
  assert.deepEqual(app.dialogs, ['alice']);
  assert.equal(app.element('selectedUser').value, 'alice');

  // alice holds WRITE on SW001 and nothing on semester2.
  app.element('permissionFolder').value = 'SW001';
  await app.trigger('permissionFolder', 'change');
  assert.deepEqual(
    app.radios.filter((input) => input.checked).map((input) => input.value),
    ['WRITE'],
  );

  app.element('permissionFolder').value = 'semester2';
  await app.trigger('permissionFolder', 'change');
  assert.deepEqual(
    app.radios.filter((input) => input.checked),
    [],
  );
});

test('selecting another account re-reads its permissions', async () => {
  const app = await page();
  app.element('selectedUser').value = 'bob';
  app.element('permissionFolder').value = 'SW001';
  await app.trigger('selectedUser', 'change');
  // bob is an administrator with no explicit grant on the folder.
  assert.deepEqual(
    app.radios.filter((input) => input.checked),
    [],
  );
  assert.equal(app.element('selectedUser').children.length, 4);
  assert.equal(app.element('selectedUser').children[3].textContent, 'carol (inactive)');
});

/* ------------------------------- page contents ------------------------------- */

test('the page ships no demo records and every mutation control starts disabled', () => {
  ['foldersTable', 'usersTable', 'invitationsTable', 'logsTable'].forEach((id) => {
    assert.ok(html.includes(`<tbody id="${id}"></tbody>`), id + ' must start empty');
  });
  [
    'Project Atlas',
    'Marketing Assets',
    'wsxzikai',
    'asw001',
    'newadmin',
    'member01',
    'guest01',
    'Demo',
  ].forEach((value) => assert.ok(!html.includes(value), 'demo value still present: ' + value));
  assert.ok(html.includes('/js/admin.js'));
  assert.ok(html.includes('id="logsPrevious"') && html.includes('id="logsNext"'));
  // The read-only phase leaves every administrative mutation disabled.
  [
    'createFolder',
    'savePermission',
    'revokePermission',
    'createResetLink',
    'deactivateUser',
    'createInvitation',
    'copyInvitationLink',
    'copyResetLink',
  ].forEach((id) => {
    assert.match(
      html,
      new RegExp(`<button[^>]*id="${id}"[^>]*disabled`),
      id + ' must stay disabled',
    );
  });
  // The dialog opener the page already shipped is preserved.
  assert.ok(html.includes('/js/admin-inline-3.js'));
  assert.ok(readFileSync('frontend/js/admin-inline-3.js', 'utf8').includes('function openUserManagement(username)'));
});

test('every element the script looks up exists in the page', () => {
  const ids = new Set([...html.matchAll(/id="([^"]+)"/g)].map((match) => match[1]));
  const looked = [...source.matchAll(/element\('([^']+)'\)/g)].map((match) => match[1]);
  assert.ok(looked.length > 15);
  looked.forEach((id) => assert.ok(ids.has(id), 'missing element #' + id));
  ['admin-workspace', 'account-avatar', 'account-copy'].forEach((name) =>
    assert.ok(html.includes(name), 'missing .' + name),
  );
  assert.ok(html.includes('name="permission"'));
});

test('the admin script requests only the verified endpoints', () => {
  // Every portal URL the script can build, so an invented endpoint cannot slip in.
  const urls = [...source.matchAll(/['"](\/api\/v1\/portal[^'"]*)['"]/g)].map((match) => match[1]);
  assert.deepEqual(
    [...new Set(urls)].sort(),
    [
      '/api/v1/portal/admin/folders',
      '/api/v1/portal/admin/invitations',
      '/api/v1/portal/admin/users',
      '/api/v1/portal/folders',
    ].sort(),
  );
  // No invitation revoke endpoint exists, so none may be called.
  assert.ok(!urls.some((url) => url.includes('invitations/')));
});

test('every mutation goes through the shared session wrapper', () => {
  // A bare fetch would skip the CSRF header and the 401 redirect.
  assert.ok(!/(?<![.\w])fetch\s*\(/.test(source), 'use auth.fetch, never the global fetch');
  assert.ok(!/localStorage|sessionStorage|console\./.test(source));
});

/* ============================================================================
 * Phase 5B: management actions
 * ==========================================================================*/

const rootAdmin = {
  userId: 'u-root',
  username: 'root',
  role: 'ADMIN',
  active: true,
  destinations: [],
};
const ciAccount = {
  userId: 'u-ci',
  username: 'mmiv_ci_' + 'a'.repeat(32),
  role: 'USER',
  active: true,
  destinations: [],
};

/**
 * A backend double for the management tests: the read endpoints, plus whatever
 * mutation a test declares through `handle`.
 */
function backend(options = {}) {
  const folders = options.folders || ['SW001', 'semester2'];
  const people = options.users || [alice, bob, carol];
  return (path, settings = {}) => {
    if (options.handle) {
      const handled = options.handle(path, settings);
      if (handled) return handled;
    }
    if (path === PROFILE)
      return { status: 200, body: { userId: 'u-root', username: 'root', role: 'ADMIN' } };
    if (path.startsWith(USERS + '?')) {
      // Same rule as the server: a case-insensitive substring of the username.
      const query = decodeURIComponent(path.split('q=')[1] || '').toLowerCase();
      return {
        status: 200,
        body: people.filter((user) => user.username.toLowerCase().includes(query)),
      };
    }
    if (path === FOLDERS) return { status: 200, body: folders };
    if (path.startsWith(LOGS)) return { status: 200, body: { logs: [], page: 0 } };
    throw new Error('Unexpected request ' + path);
  };
}

/* ------------------------------ folder creation ----------------------------- */

test('creating a folder sends the verified request and refreshes the list', async () => {
  const folders = ['SW001', 'semester2'];
  const app = await page({
    respond: backend({
      folders,
      handle: (path, settings) => {
        if (path === '/api/v1/portal/admin/folders' && settings.method === 'POST') {
          folders.push(JSON.parse(settings.body).name);
          return { status: 200, body: folders.slice() };
        }
        return null;
      },
    }),
  });

  // Nothing to create until a name is typed.
  assert.equal(app.element('createFolder').disabled, true);
  app.element('folderName').value = 'Archive 2026';
  await app.trigger('folderName', 'input');
  assert.equal(app.element('createFolder').disabled, false);

  app.element('createFolderDialog').showModal();
  await app.trigger('createFolder');
  const request = app.writes()[0];
  assert.equal(request.url, '/api/v1/portal/admin/folders');
  assert.equal(request.settings.method, 'POST');
  assert.equal(request.settings.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(request.settings.body), { name: 'Archive 2026' });

  assert.equal(app.element('createFolderDialog').opened, false);
  assert.equal(app.element('folderName').value, '');
  assert.match(app.text('status'), /Archive 2026 created/);
  assert.deepEqual(
    app.rows('foldersTable').map((row) => row.children[0].children[0].children[1]),
    ['SW001', 'semester2', 'Archive 2026'],
  );
  assert.equal(app.text('foldersMeta'), '3 folders');
  // The entered filter and the chosen folder are left alone.
  assert.equal(app.element('folderSearch').value, '');
});

test('creating a folder keeps the active search filter', async () => {
  const folders = ['SW001', 'semester2'];
  const app = await page({
    respond: backend({
      folders,
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/folders' && settings.method === 'POST'
          ? { status: 200, body: ['SW001', 'semester2', 'Archive 2026'] }
          : null,
    }),
  });
  app.element('folderSearch').value = 'sem';
  await app.trigger('folderSearch', 'input');
  app.element('permissionFolder').value = 'semester2';
  await app.trigger('permissionFolder', 'change');

  app.element('folderName').value = 'Archive 2026';
  await app.trigger('createFolder');
  assert.equal(app.element('folderSearch').value, 'sem');
  assert.deepEqual(
    app.rows('foldersTable').map((row) => row.children[0].children[0].children[1]),
    ['semester2'],
  );
  assert.equal(app.element('permissionFolder').value, 'semester2');
});

test('a folder name the server would refuse costs no request', async () => {
  const app = await page({ respond: backend() });
  app.element('folderName').value = 'bad/name';
  await app.trigger('createFolder');
  assert.equal(app.writes().length, 0);
  assert.match(app.text('status'), /Use a folder name of up to 80/);

  app.element('folderName').value = 'upload';
  await app.trigger('createFolder');
  assert.equal(app.writes().length, 0);
  assert.match(app.text('status'), /reserved/);

  // A trailing dot is refused by the server rule too.
  app.element('folderName').value = 'archive.';
  await app.trigger('createFolder');
  assert.equal(app.writes().length, 0);

  // The trimmed name is what travels.
  app.element('folderName').value = '  Archive 2026  ';
  await app.trigger('createFolder');
  assert.deepEqual(JSON.parse(app.writes()[0].settings.body), { name: 'Archive 2026' });
});

test('a rejected folder name reports the reason the server gave', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/folders' && settings.method === 'POST'
          ? { status: 400, body: { error: 'Invalid folder name' } }
          : null,
    }),
  });
  app.element('folderName').value = 'Archive 2026';
  await app.trigger('createFolder');
  assert.equal(app.text('status'), 'Invalid folder name');
  // The dialog stays open so the operator can correct the name.
  assert.equal(app.element('folderName').value, 'Archive 2026');
});

test('an unavailable folder service is reported, not hidden', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/folders' && settings.method === 'POST'
          ? { status: 503, body: { error: 'Service unavailable; please retry' } }
          : null,
    }),
  });
  app.element('folderName').value = 'Archive 2026';
  await app.trigger('createFolder');
  assert.equal(app.text('status'), 'The service is unavailable. Please try again later.');
});

/* ------------------------------ folder deletion ----------------------------- */

test('deleting a folder names the whole consequence in the confirmation', async () => {
  let deleted = null;
  const app = await page({
    respond: backend({
      handle: (path, settings) => {
        if (path.startsWith('/api/v1/portal/folders?') && settings.method === 'DELETE') {
          deleted = decodeURIComponent(path.split('folder=')[1]);
          return { status: 200, body: { deletedVersions: 3 } };
        }
        return null;
      },
    }),
  });

  await app.click(app.rows('foldersTable')[0].children[3].children[0]);
  assert.equal(app.element('confirmDialog').opened, true);
  assert.equal(app.text('confirmTitle'), 'Delete folder');
  assert.equal(app.element('confirmAccept').className, 'admin-danger-button');
  const warning = app.text('confirmMessage');
  assert.match(warning, /SW001/);
  assert.match(warning, /stored version/);
  assert.match(warning, /permissions/);
  assert.match(warning, /cannot be undone/);

  await app.answer(true);
  assert.equal(deleted, 'SW001');
  assert.equal(app.writes()[0].settings.method, 'DELETE');
  assert.match(app.text('status'), /SW001 deleted/);
  assert.equal(app.element('confirmDialog').opened, false);
});

for (const [label, answer] of [
  ['cancelling the confirmation', [false]],
  ['escaping the confirmation', [false, 'escape']],
]) {
  test(`${label} deletes nothing`, async () => {
    const app = await page({ respond: backend() });
    await app.click(app.rows('foldersTable')[0].children[3].children[0]);
    assert.equal(app.element('confirmDialog').opened, true);
    await app.answer(...answer);
    assert.equal(app.writes().length, 0);
    assert.equal(app.element('confirmDialog').opened, false);
    assert.equal(app.rows('foldersTable').length, 2);
  });
}

test('a refused folder deletion is reported and leaves the table alone', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path.startsWith('/api/v1/portal/folders?') && settings.method === 'DELETE'
          ? { status: 403, body: { error: 'Request denied or resource unavailable' } }
          : null,
    }),
  });
  await app.click(app.rows('foldersTable')[0].children[3].children[0]);
  await app.answer(true);
  assert.equal(app.text('status'), 'Administrator access required.');
  assert.equal(app.rows('foldersTable').length, 2);
});

/* --------------------------- permissions and accounts ------------------------ */

test('saving a permission sends the verified body and takes the row back from the response', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) => {
        if (
          path === '/api/v1/portal/admin/users/u-alice/permissions' &&
          settings.method === 'PUT'
        ) {
          // The server answers with the account as stored, which is what the
          // page must show — not the value that was submitted.
          return {
            status: 200,
            body: { ...alice, destinations: [{ destination: 'semester2', permission: 'WRITE' }] },
          };
        }
        return null;
      },
    }),
  });

  await app.selectUser('alice');
  await app.selectFolder('semester2');
  // The stored grant is WRITE on SW001, so nothing is chosen for semester2 yet.
  assert.equal(app.element('savePermission').disabled, true);

  await app.choosePermission('READ');
  assert.equal(app.element('savePermission').disabled, false);
  await app.trigger('savePermission');

  const request = app.writes()[0];
  assert.equal(request.url, '/api/v1/portal/admin/users/u-alice/permissions');
  assert.equal(request.settings.method, 'PUT');
  assert.deepEqual(JSON.parse(request.settings.body), {
    destination: 'semester2',
    permission: 'READ',
  });
  assert.equal(app.radios.find((input) => input.value === 'WRITE').checked, true);
  assert.equal(app.radios.find((input) => input.value === 'READ').checked, false);
  assert.match(app.text('status'), /semester2.*alice/);
});

test('revoking a permission uses the destination query parameter', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path.startsWith('/api/v1/portal/admin/users/u-alice/permissions?') &&
        settings.method === 'DELETE'
          ? { status: 200, body: { ...alice, destinations: [] } }
          : null,
    }),
  });

  await app.selectUser('alice');
  await app.selectFolder('SW001');
  assert.equal(app.element('revokePermission').disabled, false);
  await app.trigger('revokePermission');

  const request = app.writes()[0];
  assert.equal(request.url, '/api/v1/portal/admin/users/u-alice/permissions?destination=SW001');
  assert.equal(request.settings.method, 'DELETE');
  assert.deepEqual(
    app.radios.filter((input) => input.checked),
    [],
  );
  assert.match(app.text('status'), /SW001.*revoked.*alice/);
});

test('a refused permission change reports the status', async () => {
  const statuses = [
    [403, 'Administrator access required.'],
    [404, 'That item no longer exists. Please refresh.'],
    [409, 'The account cannot be changed in its current state.'],
    [503, 'The service is unavailable. Please try again later.'],
  ];
  for (const [status, message] of statuses) {
    const app = await page({
      respond: backend({
        handle: (path, settings) =>
          path === '/api/v1/portal/admin/users/u-alice/permissions' && settings.method === 'PUT'
            ? { status, body: { error: 'Request denied or resource unavailable' } }
            : null,
      }),
    });
    await app.selectUser('alice');
    await app.selectFolder('SW001');
    await app.choosePermission('READ');
    await app.trigger('savePermission');
    assert.equal(app.text('status'), message, 'status ' + status);
  }
});

test('another administrator is offered no permission or account action', async () => {
  const app = await page({ respond: backend() });
  await app.selectUser('bob');
  await app.selectFolder('SW001');
  assert.equal(app.element('savePermission').disabled, true);
  assert.equal(app.element('revokePermission').disabled, true);
  assert.equal(app.element('createResetLink').disabled, true);
  assert.equal(app.element('deactivateUser').disabled, true);
});

test('an administrator can still reset their own password and deactivate themselves', async () => {
  const app = await page({ respond: backend({ users: [rootAdmin, bob, carol] }) });
  await app.selectUser('root');
  assert.equal(app.element('createResetLink').disabled, false);
  assert.equal(app.element('deactivateUser').disabled, false);
  // Administrators already hold every folder, so grants are not offered.
  await app.selectFolder('SW001');
  assert.equal(app.element('savePermission').disabled, true);
  assert.equal(app.element('revokePermission').disabled, true);
});

test('an inactive account cannot be granted, reset or deactivated again', async () => {
  const app = await page({ respond: backend() });
  await app.selectUser('carol');
  await app.selectFolder('SW001');
  await app.choosePermission('READ');
  assert.equal(app.element('savePermission').disabled, true);
  assert.equal(app.element('createResetLink').disabled, true);
  assert.equal(app.element('deactivateUser').disabled, true);
});

test('deactivating an account confirms, then deletes and refreshes the list', async () => {
  let deactivated = null;
  const app = await page({
    respond: backend({
      handle: (path, settings) => {
        if (path === '/api/v1/portal/admin/users/u-alice' && settings.method === 'DELETE') {
          deactivated = 'u-alice';
          return { status: 204, body: null };
        }
        return null;
      },
    }),
  });

  await app.selectUser('alice');
  await app.trigger('deactivateUser');
  assert.equal(app.text('confirmTitle'), 'Deactivate account');
  assert.match(app.text('confirmMessage'), /alice/);
  assert.match(app.text('confirmMessage'), /uploaded files and audit history are kept/);

  await app.answer(true);
  assert.equal(deactivated, 'u-alice');
  assert.equal(app.writes()[0].settings.method, 'DELETE');
  assert.match(app.text('status'), /alice deactivated/);
});

test('cancelling a deactivation deletes nothing', async () => {
  const app = await page({ respond: backend() });
  await app.selectUser('alice');
  await app.trigger('deactivateUser');
  await app.answer(false);
  assert.equal(app.writes().length, 0);
});

test('deactivating yourself warns and returns to sign in', async () => {
  const app = await page({
    respond: backend({
      users: [rootAdmin],
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-root' && settings.method === 'DELETE'
          ? { status: 204, body: null }
          : null,
    }),
  });
  await app.selectUser('root');
  await app.trigger('deactivateUser');
  assert.match(app.text('confirmMessage'), /signed out/);
  assert.match(app.text('confirmMessage'), /last administrator/);
  await app.answer(true);
  assert.deepEqual(app.navigations, ['/pages/login.html']);
});

test('a refused deactivation reports the reason', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-alice' && settings.method === 'DELETE'
          ? { status: 404, body: {} }
          : null,
    }),
  });
  await app.selectUser('alice');
  await app.trigger('deactivateUser');
  await app.answer(true);
  assert.equal(
    app.text('status'),
    'That account no longer exists. Refresh the list and try again.',
  );
});

/* ------------------------------ password resets ----------------------------- */

test('a reset link is built on this site and can be copied', async () => {
  const token = 'a'.repeat(64);
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-alice/password-reset' && settings.method === 'POST'
          ? { status: 200, body: { token, expiresAt: '2026-10-15T12:00:00Z' } }
          : null,
    }),
  });

  await app.selectUser('alice');
  assert.equal(app.element('copyResetLink').disabled, true);
  await app.trigger('createResetLink');
  // Creating a credential is confirmed, but it destroys nothing.
  assert.equal(app.text('confirmTitle'), 'Create password-reset link');
  assert.equal(app.element('confirmAccept').className, 'admin-primary-button');
  assert.match(app.text('confirmMessage'), /alice/);

  await app.answer(true);
  assert.equal(app.writes()[0].settings.method, 'POST');
  assert.equal(
    app.element('resetLink').value,
    'https://mmiv.example/reset-password.html#reset=' + token,
  );
  assert.equal(app.element('copyResetLink').disabled, false);

  await app.trigger('copyResetLink');
  assert.deepEqual(app.copied, [app.element('resetLink').value]);
});

test('cancelling a reset link sends nothing', async () => {
  const app = await page({ respond: backend() });
  await app.selectUser('alice');
  await app.trigger('createResetLink');
  await app.answer(false);
  assert.equal(app.writes().length, 0);
  assert.equal(app.element('resetLink').value, '');
});

test('a refused reset link reports the reason', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-alice/password-reset' && settings.method === 'POST'
          ? { status: 403, body: {} }
          : null,
    }),
  });
  await app.selectUser('alice');
  await app.trigger('createResetLink');
  await app.answer(true);
  assert.equal(app.text('status'), 'You cannot create a reset link for another administrator.');
});

test('copying falls back to the selected field when the Clipboard API is refused', async () => {
  const token = 'a'.repeat(64);
  const app = await page({
    clipboard: false,
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-alice/password-reset' && settings.method === 'POST'
          ? { status: 200, body: { token } }
          : null,
    }),
  });
  await app.selectUser('alice');
  await app.trigger('createResetLink');
  await app.answer(true);
  await app.trigger('copyResetLink');
  assert.deepEqual(app.execCommands, ['copy']);
  assert.equal(app.element('resetLink').selected, true);
  assert.match(app.text('status'), /Reset link copied/);
});

/* -------------------------------- invitations ------------------------------- */

test('finding an account offers only the accounts the server would accept', async () => {
  const app = await page({ respond: backend({ users: [alice, bob, carol, ciAccount] }) });
  app.element('invitationUsername').value = 'a';
  await app.trigger('findAccount');

  assert.equal(app.writes().length, 0);
  assert.deepEqual(
    app.element('invitationAccount').children.map((option) => option.value),
    ['', 'alice'],
  );
  assert.equal(app.element('invitationUsername').value, 'alice');
});

test('an account that cannot be invited is reported', async () => {
  const app = await page({ respond: backend({ users: [alice, bob, carol, ciAccount] }) });
  app.element('invitationUsername').value = 'bob';
  await app.trigger('findAccount');
  assert.deepEqual(
    app.element('invitationAccount').children.map((option) => option.value),
    [''],
  );
  assert.match(app.text('status'), /No eligible account/);
});

test('choosing an account from the dropdown fills the username', async () => {
  const app = await page({ respond: backend({ users: [alice] }) });
  app.element('invitationUsername').value = 'ali';
  await app.trigger('findAccount');
  app.element('invitationAccount').value = 'alice';
  await app.trigger('invitationAccount', 'change');
  assert.equal(app.element('invitationUsername').value, 'alice');
});

test('creating an invitation posts the chosen account and builds the link', async () => {
  const token = 'b'.repeat(64);
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/invitations' && settings.method === 'POST'
          ? { status: 200, body: { token, expiresAt: '2026-10-16T12:00:00Z' } }
          : null,
    }),
  });

  assert.equal(app.element('websiteAddress').value, 'https://mmiv.example');
  app.element('invitationUsername').value = 'alice';
  await app.trigger('invitationUsername', 'input');
  await app.trigger('createInvitation');

  const request = app.writes()[0];
  assert.equal(request.url, '/api/v1/portal/admin/invitations');
  assert.equal(request.settings.method, 'POST');
  assert.equal(request.settings.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(request.settings.body), { username: 'alice' });
  assert.equal(
    app.element('invitationLink').value,
    'https://mmiv.example/invite.html#invite=' + token,
  );
  assert.equal(app.element('copyInvitationLink').disabled, false);

  await app.trigger('copyInvitationLink');
  assert.deepEqual(app.copied, [app.element('invitationLink').value]);
});

test('a link is built on the entered origin, not the one being browsed', async () => {
  const token = 'b'.repeat(64);
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/invitations' && settings.method === 'POST'
          ? { status: 200, body: { token } }
          : null,
    }),
  });
  app.element('websiteAddress').value = 'https://portal.example';
  app.element('invitationUsername').value = 'alice';
  await app.trigger('createInvitation');
  assert.equal(
    app.element('invitationLink').value,
    'https://portal.example/invite.html#invite=' + token,
  );
});

test('a reset link also follows the entered origin', async () => {
  const token = 'c'.repeat(64);
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/users/u-alice/password-reset' && settings.method === 'POST'
          ? { status: 200, body: { token } }
          : null,
    }),
  });
  // The browsed origin stays the harness address; the operator names the one
  // the recipient can open, which is what the link has to carry.
  app.element('websiteAddress').value = 'https://portal.example';
  await app.selectUser('alice');
  await app.trigger('createResetLink');
  await app.answer(true);
  assert.equal(
    app.element('resetLink').value,
    'https://portal.example/reset-password.html#reset=' + token,
  );
});

test('an unusable website address also costs no reset-link request', async () => {
  const app = await page({ respond: backend() });
  await app.selectUser('alice');
  app.element('websiteAddress').value = 'not a url';
  await app.trigger('createResetLink');
  assert.equal(app.writes().length, 0);
  assert.match(app.text('status'), /Enter the website origin/);
});

test('an unusable website address costs no request', async () => {
  const app = await page({ respond: backend() });
  app.element('invitationUsername').value = 'alice';
  for (const value of ['', 'not a url', 'https://portal.example/app', 'ftp://portal.example']) {
    app.element('websiteAddress').value = value;
    await app.trigger('createInvitation');
    assert.equal(app.writes().length, 0, 'accepted ' + JSON.stringify(value));
    assert.match(app.text('status'), /Enter the website origin/);
  }
});

test('an account the server refuses is reported with its reason', async () => {
  const cases = [
    [404, 'No account has that username. Ask the user to create an account first.'],
    [409, 'Invitations require an active ordinary user. An administrator does not need one.'],
  ];
  for (const [status, message] of cases) {
    const app = await page({
      respond: backend({
        handle: (path, settings) =>
          path === '/api/v1/portal/admin/invitations' && settings.method === 'POST'
            ? { status, body: { error: 'Request denied or resource unavailable' } }
            : null,
      }),
    });
    app.element('invitationUsername').value = 'nobody';
    await app.trigger('createInvitation');
    assert.equal(app.text('status'), message, 'status ' + status);
    assert.equal(app.element('invitationLink').value, '');
  }
});

test('a malformed invitation response leaves no link behind', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/invitations' && settings.method === 'POST'
          ? { status: 200, body: { expiresAt: '2026-10-16T12:00:00Z' } }
          : null,
    }),
  });
  app.element('invitationUsername').value = 'alice';
  await app.trigger('createInvitation');
  assert.equal(app.element('invitationLink').value, '');
  assert.match(app.text('status'), /invitation link could not be read/);
});

/* ---------------------------- submission discipline -------------------------- */

test('a second submission while a request is in flight is ignored', async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const app = await page({
    respond: backend({
      handle: (path, settings) =>
        path === '/api/v1/portal/admin/folders' && settings.method === 'POST'
          ? { status: 200, body: ['SW001', 'semester2', 'SW002'], pending: gate }
          : null,
    }),
  });

  app.element('folderName').value = 'SW002';
  app.element('createFolder').listeners.click();
  // Every control is locked for the duration of the request.
  assert.equal(app.element('createFolder').disabled, true);
  app.element('createFolder').listeners.click();
  assert.equal(app.writes().length, 1);

  release();
  await app.flush();
  assert.equal(app.writes().length, 1);
  assert.equal(app.element('folderName').value, '');
});

test('every management action sends a non-GET request through the session wrapper', async () => {
  const app = await page({
    respond: backend({
      handle: (path, settings) => {
        if (path === '/api/v1/portal/admin/folders' && settings.method === 'POST')
          return { status: 200, body: ['SW001', 'semester2'] };
        if (path.startsWith('/api/v1/portal/folders?') && settings.method === 'DELETE')
          return { status: 200, body: { deletedVersions: 1 } };
        if (path === '/api/v1/portal/admin/users/u-alice/permissions' && settings.method === 'PUT')
          return { status: 200, body: alice };
        if (path.startsWith('/api/v1/portal/admin/users/u-alice/permissions?'))
          return { status: 200, body: alice };
        if (path === '/api/v1/portal/admin/users/u-alice/password-reset')
          return { status: 200, body: { token: 'a'.repeat(64) } };
        if (path === '/api/v1/portal/admin/users/u-alice' && settings.method === 'DELETE')
          return { status: 204, body: null };
        if (path === '/api/v1/portal/admin/invitations' && settings.method === 'POST')
          return { status: 200, body: { token: 'b'.repeat(64) } };
        return null;
      },
    }),
  });

  app.element('folderName').value = 'SW002';
  await app.trigger('createFolder');
  await app.click(app.rows('foldersTable')[0].children[3].children[0]);
  await app.answer(true);

  await app.selectUser('alice');
  await app.selectFolder('SW001');
  await app.choosePermission('READ');
  await app.trigger('savePermission');
  await app.trigger('revokePermission');
  await app.trigger('createResetLink');
  await app.answer(true);
  await app.trigger('deactivateUser');
  await app.answer(true);

  app.element('invitationUsername').value = 'alice';
  await app.trigger('createInvitation');

  const methods = [...new Set(app.writes().map((write) => write.settings.method))].sort();
  assert.deepEqual(methods, ['DELETE', 'POST', 'PUT']);
  assert.ok(
    app.writes().every((write) => write.url.startsWith('/api/v1/portal/')),
    'every mutation targets the portal API',
  );
  // Nothing is written to browser storage, and no token is logged.
  assert.ok(!/localStorage|sessionStorage|console\./.test(source));
});

test('the confirmation dialog is part of the page', () => {
  ['confirmDialog', 'confirmTitle', 'confirmMessage', 'confirmAccept', 'confirmCancel'].forEach(
    (id) => assert.ok(html.includes('id="' + id + '"'), 'missing #' + id),
  );
  assert.ok(html.includes('id="confirmDialog" class="admin-dialog"'));
  for (const id of [
    'createFolder',
    'savePermission',
    'revokePermission',
    'createResetLink',
    'deactivateUser',
    'createInvitation',
  ]) {
    assert.match(
      html,
      new RegExp(`<button[^>]*id="${id}"[^>]*disabled`),
      id + ' must ship disabled',
    );
  }
});

test('administrator reports load only when their own tab is opened', async () => {
  const app = await page();
  assert.equal(app.requests.some((url) => url.startsWith(ADMIN_REPORTS)), false);

  app.element('tab-reports').checked = true;
  await app.trigger('tab-reports', 'change');

  assert.ok(app.requests.includes(ADMIN_REPORTS + '?page=0&size=20'));
  const row = app.rows('adminReportsTable')[0];
  assert.equal(row.children[0].textContent, REPORT_ID);
  assert.equal(row.children[1].textContent, 'u-alice');
  assert.equal(
    row.children[6].children[0].href,
    '/pages/report-detail.html?id=' + REPORT_ID,
  );
});

test('administrator report status and owner filters remain separate from system logs', async () => {
  const app = await page();
  app.element('tab-reports').checked = true;
  await app.trigger('tab-reports', 'change');
  const logRequests = app.requests.filter((url) => url.startsWith(LOGS)).length;

  app.element('adminReportStatus').value = 'FAILED';
  await app.trigger('adminReportStatus', 'change');
  app.element('adminReportUser').value = 'user/a b';
  await app.trigger('adminReportUser', 'keydown', {
    key: 'Enter',
    preventDefault() {},
  });

  assert.equal(
    app.requests.at(-1),
    ADMIN_REPORTS + '?page=0&size=20&status=FAILED&initiatedBy=user%2Fa%20b',
  );
  assert.equal(app.requests.filter((url) => url.startsWith(LOGS)).length, logRequests);
});

test('the administrator workspace exposes a distinct reports section', () => {
  for (const id of [
    'tab-reports',
    'admin-reports',
    'adminReportUser',
    'adminReportStatus',
    'adminReportsTable',
    'adminReportsMeta',
  ]) {
    assert.ok(html.includes('id="' + id + '"'), 'missing #' + id);
  }
});
