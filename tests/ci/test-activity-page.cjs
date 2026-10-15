const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('frontend/js/activity.js', 'utf8');
const html = readFileSync('frontend/pages/activity.html', 'utf8');

const PROFILE = '/api/v1/portal/me';
const HISTORY = '/api/v1/portal/history';

const now = new Date();
const day = 24 * 60 * 60 * 1000;

/** One record per action the endpoint reports, in both outcomes. */
const uploaded = {
  time: new Date(now.getTime() - 60 * 60 * 1000).toISOString(),
  action: 'Upload',
  fileName: 'clip.mp4',
  folder: 'SW001',
  sizeBytes: 2048,
  status: 'Completed',
};
const failedUpload = {
  time: new Date(now.getTime() - day).toISOString(),
  action: 'Upload',
  fileName: 'broken.mov',
  folder: 'SW001',
  sizeBytes: 10,
  status: 'Failed',
};
const downloaded = {
  time: new Date(now.getTime() - 30 * day).toISOString(),
  action: 'Download',
  fileName: 'report.pdf',
  folder: 'semester2',
  sizeBytes: 99,
  status: 'Completed',
};
const fileDeleted = {
  time: new Date(now.getTime() - 31 * day).toISOString(),
  action: 'Delete',
  fileName: 'old.txt',
  folder: 'SW001',
  sizeBytes: 5,
  status: 'Completed',
};
// A folder deletion reports no file name of its own.
const folderDeleted = {
  time: new Date(now.getTime() - 32 * day).toISOString(),
  action: 'Delete folder',
  fileName: '',
  folder: 'Archive 2026',
  sizeBytes: null,
  status: 'Completed',
};

function node(tagName) {
  return {
    tagName,
    children: [],
    className: '',
    textContent: '',
    value: '',
    hidden: false,
    colSpan: 0,
    listeners: {},
    append(...items) {
      this.children.push(...items);
    },
    replaceChildren(...items) {
      this.children = items;
    },
    setAttribute() {},
    addEventListener(event, handler) {
      this.listeners[event] = handler;
    },
  };
}

async function page(options = {}) {
  const role = options.role || 'USER';
  const nodes = new Map();
  const requests = [];
  const observers = [];
  const adminLink = node('A');
  adminLink.hidden = true;

  const byId = (id) => {
    if (!nodes.has(id)) nodes.set(id, node('DIV'));
    return nodes.get(id);
  };
  byId('activityFilter').value = 'all';

  const respond =
    options.respond ||
    ((path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role } };
      if (path.startsWith(HISTORY))
        return { status: 200, body: { entries: [uploaded], hasMore: false, page: 0 } };
      throw new Error('Unexpected request ' + path);
    });

  const fetchMock = async (url, settings = {}) => {
    requests.push(url);
    const result = respond(url, settings);
    if (result && result.reject) throw new Error(result.reject);
    if (result && result.pending) await result.pending;
    return { ok: result.status < 300, status: result.status, json: async () => result.body };
  };

  class FakeObserver {
    constructor(callback) {
      this.callback = callback;
      this.targets = [];
      this.disconnected = false;
      observers.push(this);
    }

    observe(target) {
      this.targets.push(target);
    }

    disconnect() {
      this.disconnected = true;
    }
  }

  const context = {
    document: {
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
      querySelectorAll: (selector) => (selector === '[data-admin]' ? [adminLink] : []),
    },
    window: { mmivAuth: { ready: options.ready || Promise.resolve(), fetch: fetchMock } },
    location: { replace() {} },
    IntersectionObserver: FakeObserver,
    Date,
    encodeURIComponent,
  };
  if (options.noObserver) delete context.IntersectionObserver;

  vm.runInNewContext(source, context);

  const flush = async () => {
    for (let i = 0; i < 12; i += 1) await new Promise(setImmediate);
  };
  await flush();

  return {
    requests,
    observers,
    adminLink,
    element: byId,
    rows: () => byId('activityRows').children,
    text: (id) => byId(id).textContent,
    flush,
    async trigger(id, event = 'click', detail = { preventDefault() {} }) {
      byId(id).listeners[event](detail);
      await flush();
    },
    /** Scrolls the sentinel into view, which is what pages the list. */
    async reachSentinel() {
      const observer = observers.at(-1);
      if (observer) observer.callback([{ isIntersecting: true }]);
      await flush();
    },
    counts(path) {
      return requests.filter((url) => url.startsWith(path)).length;
    },
  };
}

/* ---------------------------------- rendering ------------------------------- */

test('the page loads the account and its own history', async () => {
  const app = await page();
  assert.deepEqual(app.requests, [PROFILE, HISTORY + '?page=0']);
  assert.equal(app.element('account').textContent, 'alice');
  assert.equal(app.text('status'), '');
});

test('each reported action and outcome is rendered in the existing columns', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: {
          entries: [uploaded, failedUpload, downloaded, fileDeleted, folderDeleted],
          hasMore: false,
          page: 0,
        },
      };
    },
  });

  const rows = app.rows();
  assert.equal(rows.length, 5);

  // Activity | File / Folder | Location | Date | Status
  const titles = rows.map((row) => row.children[0].children[0].textContent);
  assert.deepEqual(titles, [
    'File uploaded',
    'Upload failed',
    'File downloaded',
    'File deleted',
    'Folder deleted',
  ]);
  assert.equal(rows[0].children[0].children[0].className, 'activity-name');
  assert.equal(rows[0].children[1].textContent, 'clip.mp4');
  assert.equal(rows[0].children[2].textContent, 'SW001');

  // A failed run says so, in the report the stylesheet already carries.
  const failedBadge = rows[1].children[4].children[0];
  assert.equal(failedBadge.className, 'activity-status status-failed');
  assert.equal(failedBadge.textContent, 'Failed');
  assert.equal(rows[0].children[4].children[0].className, 'activity-status status-completed');

  // A folder deletion carries no file name, so the cell says so rather than
  // borrowing the folder name.
  assert.equal(rows[4].children[1].textContent, '—');
  assert.equal(rows[4].children[2].textContent, 'Archive 2026');

  assert.equal(app.text('activityCount'), '5 activities');
  assert.equal(app.text('activityListStatus'), 'Showing 5 activities');
});

test("timestamps use the page's existing date vocabulary", async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: { entries: [uploaded, failedUpload, downloaded], hasMore: false },
      };
    },
  });
  const rows = app.rows();
  assert.match(rows[0].children[3].textContent, /^Today, \d{1,2}:\d{2}\s?(AM|PM)$/i);
  assert.match(rows[1].children[3].textContent, /^Yesterday, \d{1,2}:\d{2}\s?(AM|PM)$/i);
  assert.match(rows[2].children[3].textContent, /^[A-Z][a-z]{2} \d{1,2}, \d{4}$/);
});

test('a record with no timestamp or folder is shown without inventing one', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: {
          entries: [{ action: 'Upload', fileName: 'x.bin', status: 'Completed' }],
          hasMore: false,
        },
      };
    },
  });
  const row = app.rows()[0];
  assert.equal(row.children[2].textContent, '—');
  assert.equal(row.children[3].textContent, '—');
});

test('an action this page has never seen keeps its own name', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: {
          entries: [
            {
              time: uploaded.time,
              action: 'TRANSFER',
              fileName: 'a.zip',
              folder: 'SW001',
              status: 'Completed',
            },
            {
              time: uploaded.time,
              action: 'Restore',
              fileName: 'b.zip',
              folder: 'SW001',
              status: 'Failed',
            },
          ],
          hasMore: false,
        },
      };
    },
  });
  const rows = app.rows();
  assert.equal(rows[0].children[0].children[0].textContent, 'TRANSFER');
  assert.equal(rows[1].children[0].children[0].textContent, 'Restore failed');
});

test('the header reveals Administration only for the verified role', async () => {
  const member = await page();
  assert.equal(member.adminLink.hidden, true);

  const admin = await page({ role: 'ADMIN' });
  assert.equal(admin.adminLink.hidden, false);
});

/* ------------------------------- search and filter --------------------------- */

test('search narrows the records already loaded, without another request', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: { entries: [uploaded, downloaded, folderDeleted], hasMore: false },
      };
    },
  });
  const before = app.requests.length;

  app.element('activitySearch').value = 'report';
  await app.trigger('activitySearch', 'input');
  assert.equal(app.rows().length, 1);
  assert.equal(app.rows()[0].children[1].textContent, 'report.pdf');
  assert.equal(app.text('activityCount'), '1 activity');
  assert.equal(app.text('activityListStatus'), 'Showing 1 of 3 activities loaded');

  // The folder name is searchable too, and matching is case-insensitive.
  app.element('activitySearch').value = 'archive';
  await app.trigger('activitySearch', 'input');
  assert.equal(app.rows().length, 1);
  assert.equal(app.rows()[0].children[2].textContent, 'Archive 2026');

  app.element('activitySearch').value = 'nothing here';
  await app.trigger('activitySearch', 'input');
  assert.equal(app.rows()[0].children[0].textContent, 'No loaded activity matches your search.');
  assert.equal(app.text('activityCount'), '0 activities');
  assert.equal(app.requests.length, before);
});

test('the type filter keeps to the actions the endpoint reports', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return {
        status: 200,
        body: { entries: [uploaded, failedUpload, downloaded, fileDeleted], hasMore: false },
      };
    },
  });

  app.element('activityFilter').value = 'upload';
  await app.trigger('activityFilter', 'change');
  assert.deepEqual(
    app.rows().map((row) => row.children[0].children[0].textContent),
    ['File uploaded', 'Upload failed'],
  );

  app.element('activityFilter').value = 'download';
  await app.trigger('activityFilter', 'change');
  assert.deepEqual(
    app.rows().map((row) => row.children[1].textContent),
    ['report.pdf'],
  );

  // Deletions are real records with no filter option of their own: they are
  // listed under "All activity" and are not relabelled as an upload.
  app.element('activityFilter').value = 'all';
  await app.trigger('activityFilter', 'change');
  assert.equal(app.rows().length, 4);
  assert.ok(app.rows().some((row) => row.children[0].children[0].textContent === 'File deleted'));
  assert.equal(app.requests.filter((url) => url.startsWith(HISTORY)).length, 1);
});

test('the verification filter explains itself instead of inventing records', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return { status: 200, body: { entries: [uploaded, downloaded], hasMore: false } };
    },
  });

  app.element('activityFilter').value = 'verification';
  await app.trigger('activityFilter', 'change');

  const rows = app.rows();
  assert.equal(rows.length, 1);
  assert.equal(
    rows[0].children[0].textContent,
    'Integrity verification events are not included in this history.',
  );
  assert.equal(app.element('activityCount').textContent, '0 activities');
  // Nothing was relabelled as verified, and nothing was requested.
  assert.ok(!JSON.stringify(rows).includes('Verified'));
  assert.equal(app.requests.filter((url) => url.startsWith(HISTORY)).length, 1);
});

/* --------------------------------- pagination -------------------------------- */

test('the sentinel loads the next page and appends it', async () => {
  const second = {
    time: new Date(now.getTime() - 40 * day).toISOString(),
    action: 'Download',
    fileName: 'older.pdf',
    folder: 'SW001',
    status: 'Completed',
  };
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      const requested = Number(path.split('page=')[1]);
      return {
        status: 200,
        body: {
          entries: requested === 0 ? [uploaded] : [second],
          hasMore: requested === 0,
          page: requested,
        },
      };
    },
  });

  assert.deepEqual(app.requests, [PROFILE, HISTORY + '?page=0']);
  assert.equal(app.rows().length, 1);
  // The first page reported more, so the sentinel is armed.
  assert.equal(app.observers.at(-1).targets.length, 1);

  await app.reachSentinel();
  assert.equal(app.requests.at(-1), HISTORY + '?page=1');
  assert.equal(app.rows().length, 2);
  assert.equal(app.rows()[1].children[1].textContent, 'older.pdf');
  assert.equal(app.text('activityCount'), '2 activities');
});

test('paging stops when the endpoint reports no further page', async () => {
  const app = await page();
  await app.reachSentinel();
  assert.equal(app.requests.filter((url) => url.startsWith(HISTORY)).length, 1);
  // The observer armed for the loading render is dropped once the endpoint
  // reports that the list is complete, so the sentinel asks for nothing more.
  assert.equal(app.observers.at(-1).disconnected, true);
});

test('the refresh button reloads from the first page', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      const requested = Number(path.split('page=')[1]);
      return {
        status: 200,
        body: {
          entries: requested === 0 ? [uploaded] : [],
          hasMore: requested === 0,
          page: requested,
        },
      };
    },
  });
  await app.reachSentinel();
  assert.equal(app.requests.at(-1), HISTORY + '?page=1');

  await app.trigger('refreshActivity');
  assert.equal(app.requests.at(-1), HISTORY + '?page=0');
  assert.equal(app.rows().length, 1);
});

/* ------------------------------ empty and error states ----------------------- */

test('no history shows an empty state and no records', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return { status: 200, body: { entries: [], hasMore: false, page: 0 } };
    },
  });
  assert.equal(app.rows().length, 1);
  assert.equal(app.rows()[0].children[0].textContent, 'No activity yet.');
  assert.equal(app.text('activityCount'), '0 activities');
  assert.equal(app.text('activityListStatus'), 'No activity yet.');
});

test('a failing history call reports it and fabricates nothing', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return { status: 503, body: {} };
    },
  });
  const rows = app.rows();
  assert.equal(rows.length, 1);
  assert.equal(
    rows[0].children[0].textContent,
    'The reporting service is unavailable. Please try again later.',
  );
  assert.equal(app.text('activityCount'), '0 activities');
  // The failed page is not retried by the sentinel.
  await app.reachSentinel();
  assert.equal(app.requests.filter((url) => url.startsWith(HISTORY)).length, 1);
});

test('a failed later page keeps the records already on screen', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      const requested = Number(path.split('page=')[1]);
      if (requested === 0)
        return { status: 200, body: { entries: [uploaded, downloaded], hasMore: true, page: 0 } };
      return { status: 503, body: {} };
    },
  });
  await app.reachSentinel();
  assert.equal(app.rows().length, 2);
  assert.equal(app.text('status'), 'The reporting service is unavailable. Please try again later.');
  assert.equal(app.rows()[0].children[1].textContent, 'clip.mp4');
});

test('a malformed history response is an error, not an empty list', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 200, body: { username: 'alice', role: 'USER' } };
      return { status: 200, body: { records: [] } };
    },
  });
  assert.equal(
    app.rows()[0].children[0].textContent,
    'Your activity could not be read. Please reload the page.',
  );
});

test('an unreadable account stops the page before any history request', async () => {
  const app = await page({
    respond: (path) => {
      if (path === PROFILE) return { status: 403, body: {} };
      throw new Error('Unexpected request ' + path);
    },
  });
  assert.deepEqual(app.requests, [PROFILE]);
  assert.equal(app.text('status'), 'Your account is not available. Contact your administrator.');
  assert.equal(app.rows()[0].children[0].textContent, 'Your activity is unavailable.');
  assert.equal(app.text('activityCount'), '0 activities');
});

test('a session that cannot be verified issues no request at all', async () => {
  const app = await page({ ready: Promise.reject(new Error('Please sign in again.')) });
  assert.deepEqual(app.requests, []);
});

/* ---------------------------------- page source ------------------------------ */

test('the page ships no mock records and keeps its structure', () => {
  [
    'mockActivities',
    'Demo data',
    'example activity',
    'Integrity verified',
    'Project Atlas',
  ].forEach((value) => assert.ok(!html.includes(value), 'mock value still present: ' + value));
  assert.ok(html.includes('<script src="../js/activity.js"></script>'));
  assert.ok(html.includes('<tbody id="activityRows"></tbody>'));
  assert.ok(html.includes('id="activitySentinel"'));
  // Navigation, search, filter and the table columns are preserved.
  ['Home', 'File Manager', 'Activity', 'Administration'].forEach((label) =>
    assert.ok(
      html.includes('>' + label + '<') || html.includes(label + ' '),
      'missing nav ' + label,
    ),
  );
  assert.ok(html.includes('id="activitySearch"'));
  assert.ok(html.includes('id="refreshActivity"'));
  ['all', 'upload', 'download', 'verification'].forEach((value) =>
    assert.ok(html.includes('<option value="' + value + '"'), 'missing filter option ' + value),
  );
  ['Activity', 'File / Folder', 'Location', 'Date', 'Status'].forEach((heading) =>
    assert.ok(html.includes('>' + heading + '</th>'), 'missing column ' + heading),
  );
  // The animation and session scripts are untouched.
  assert.ok(html.includes('<script src="../js/particles.min.js"></script>'));
  assert.ok(html.includes('<script src="../js/mmiv-auth.js"></script>'));
});

test('the activity script reads only the verified endpoints', () => {
  assert.deepEqual(
    [...source.matchAll(/['"](\/api\/v1\/portal[^'"]*)['"]/g)].map((match) => match[1]),
    [PROFILE, HISTORY],
  );
  // A bare fetch would skip the CSRF header and the 401 redirect.
  assert.ok(!/(?<![.\w])fetch\s*\(/.test(source), 'use auth.fetch, never the global fetch');
  assert.ok(!/localStorage|sessionStorage|console\./.test(source));
});

test('every element the script looks up exists in the page', () => {
  const ids = new Set([...html.matchAll(/id="([^"]+)"/g)].map((match) => match[1]));
  const looked = [...source.matchAll(/element\('([^']+)'\)/g)].map((match) => match[1]);
  assert.ok(looked.length > 8);
  looked.forEach((id) => assert.ok(ids.has(id), 'missing element #' + id));
  assert.ok(html.includes('data-admin'));
});
