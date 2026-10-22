const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('frontend/js/reports.js', 'utf8');
const html = (readFileSync('frontend/pages/reports.html', 'utf8') + readFileSync('frontend/src/pages/reports.jsx', 'utf8').replaceAll('className=', 'class=').replaceAll('htmlFor=', 'for='));
const detailSource = readFileSync('frontend/js/report-detail.js', 'utf8');
const detailHtml = (readFileSync('frontend/pages/report-detail.html', 'utf8') + readFileSync('frontend/src/pages/report-detail.jsx', 'utf8').replaceAll('className=', 'class=').replaceAll('htmlFor=', 'for='));
const REPORTS = '/api/reports/mine';
const MIGRATION_ID = '123e4567-e89b-42d3-a456-426614174000';

const report = {
  migrationId: '<migration-001>',
  status: 'COMPLETED',
  startedAt: '2026-10-11T10:00:00Z',
  completedAt: '2026-10-11T10:05:00Z',
  summary: { totalFiles: 10, successfulFiles: 8, failedFiles: 2, successRate: 80 },
};

function node(tagName) {
  let innerWrites = 0;
  return {
    tagName,
    children: [],
    className: '',
    textContent: '',
    value: '',
    href: '',
    hidden: false,
    disabled: false,
    colSpan: 0,
    listeners: {},
    append(...items) {
      this.children.push(...items);
    },
    replaceChildren(...items) {
      this.children = items;
    },
    setAttribute(name, value) {
      this[name] = value;
    },
    addEventListener(event, handler) {
      this.listeners[event] = handler;
    },
    set innerHTML(value) {
      innerWrites += 1;
      this._innerHTML = value;
    },
    get innerHTML() {
      return this._innerHTML || '';
    },
    innerWrites: () => innerWrites,
  };
}

async function page(options = {}) {
  const nodes = new Map();
  const requests = [];
  const redirects = [];
  const byId = (id) => {
    if (!nodes.has(id)) nodes.set(id, node('DIV'));
    return nodes.get(id);
  };
  byId('reportStatusFilter').value = '';

  const respond =
    options.respond ||
    (() => ({
      status: 200,
      body: { content: [report], number: 0, totalPages: 1, totalElements: 1 },
    }));
  const fetchMock = async (url) => {
    requests.push(url);
    const response = respond(url);
    if (response.status === 401) redirects.push('/pages/login.html');
    return { ok: response.status < 300, status: response.status, json: async () => response.body };
  };

  const context = {
    document: {
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
    },
    window: { mmivAuth: { ready: Promise.resolve(), fetch: fetchMock } },
    location: { replace: (value) => redirects.push(value) },
    Date,
    encodeURIComponent,
  };
  vm.runInNewContext(source, context);
  const flush = async () => {
    for (let i = 0; i < 10; i += 1) await new Promise(setImmediate);
  };
  await flush();

  return {
    requests,
    redirects,
    element: byId,
    rows: () => byId('reportRows').children,
    flush,
    async trigger(id, event = 'click') {
      byId(id).listeners[event]({ preventDefault() {} });
      await flush();
    },
  };
}

test('loads only /api/reports/mine and renders summary values safely', async () => {
  const app = await page();
  assert.deepEqual(app.requests, [REPORTS + '?page=0&size=20']);
  const row = app.rows()[0];
  assert.equal(row.children[0].textContent, '<migration-001>');
  assert.equal(row.children[3].textContent, '10');
  assert.equal(row.children[4].textContent, '8');
  assert.equal(row.children[5].textContent, '2');
  assert.equal(row.children[6].textContent, '80.0%');
  assert.equal(row.children[7].children[0].href, '/pages/report-detail.html?id=%3Cmigration-001%3E');
  assert.equal(row.children[0].innerWrites(), 0);
});

test('status filter resets pagination and encodes the value', async () => {
  const app = await page({
    respond: (url) => {
      const pageNumber = Number(new URL('https://example.test' + url).searchParams.get('page'));
      return {
        status: 200,
        body: { content: [], number: pageNumber, totalPages: 3, totalElements: 40 },
      };
    },
  });
  await app.trigger('reportsNext');
  app.element('reportStatusFilter').value = 'PARTIALLY_COMPLETED';
  await app.trigger('reportStatusFilter', 'change');
  assert.equal(
    app.requests.at(-1),
    REPORTS + '?page=0&size=20&status=PARTIALLY_COMPLETED',
  );
});

test('next and previous buttons obey returned page metadata', async () => {
  const app = await page({
    respond: (url) => {
      const number = Number(new URL('https://example.test' + url).searchParams.get('page'));
      return { status: 200, body: { content: [], number, totalPages: 2, totalElements: 21 } };
    },
  });
  assert.equal(app.element('reportsPrevious').disabled, true);
  assert.equal(app.element('reportsNext').disabled, false);
  await app.trigger('reportsNext');
  assert.equal(app.requests.at(-1), REPORTS + '?page=1&size=20');
  assert.equal(app.element('reportsPrevious').disabled, false);
  assert.equal(app.element('reportsNext').disabled, true);
  await app.trigger('reportsPrevious');
  assert.equal(app.requests.at(-1), REPORTS + '?page=0&size=20');
});

test('401 redirects through mmivAuth and 503 shows no fake rows', async () => {
  const unauthorized = await page({ respond: () => ({ status: 401, body: {} }) });
  assert.deepEqual(unauthorized.redirects, ['/pages/login.html']);
  assert.equal(unauthorized.rows().length, 0);

  const unavailable = await page({ respond: () => ({ status: 503, body: {} }) });
  assert.equal(unavailable.rows().length, 0);
  assert.equal(
    unavailable.element('reportsStatus').textContent,
    'The reporting service is unavailable. Please try again later.',
  );
});

test('remote strings are assigned through textContent, never innerHTML', async () => {
  const app = await page();
  assert.equal(source.includes('innerHTML'), false);
  assert.equal(JSON.stringify(app.rows()).includes('<migration-001>'), true);
  assert.equal(app.rows()[0].children[0].innerWrites(), 0);
});

test('page exposes the required report controls and navigation', () => {
  [
    'reportStatusFilter',
    'reportRows',
    'reportCount',
    'reportsPrevious',
    'reportsNext',
    'reportsStatus',
  ].forEach((id) => assert.ok(html.includes(`id="${id}"`), `missing ${id}`));
  assert.ok(html.includes('/js/reports.js'));
  assert.equal(html.includes('Demo data'), false);
});

function detailPage(options = {}) {
  const nodes = new Map();
  const requests = [];
  const timers = new Map();
  const visibilityHandlers = [];
  let timerId = 0;
  let visibilityState = options.visibilityState || 'visible';
  const byId = (id) => {
    if (!nodes.has(id)) nodes.set(id, node('DIV'));
    return nodes.get(id);
  };
  const profile = options.profile || { userId: 'alice', username: 'alice', role: 'USER' };
  const summary = options.summary || {
    migrationId: MIGRATION_ID,
    status: 'COMPLETED',
    initiatedBy: 'alice',
    source: 'MANIFEST',
    startedAt: '2026-10-11T10:00:00Z',
    completedAt: '2026-10-11T10:05:00Z',
    summary: {
      totalFiles: 1,
      discoveryAccepted: 1,
      discoveryFailed: 0,
      transferStored: 1,
      transferFailed: 0,
      integrityPassed: 1,
      integrityFailed: 0,
      successfulFiles: 1,
      failedFiles: 0,
      successRate: 100,
    },
  };
  const responses = {
    ['/api/access/me']: profile,
    [`/api/reports/${MIGRATION_ID}`]: summary,
    [`/api/reports/${MIGRATION_ID}/files?page=0&size=20`]: {
      content: [
        {
          fileId: 'file-1',
          fileName: '<unsafe.mp4>',
          relativePath: '<folder/unsafe.mp4>',
          discoveryStatus: 'ACCEPTED',
          transferStatus: 'STORED',
          integrityStatus: 'PASSED',
          finalStatus: 'SUCCEEDED',
          failure: null,
        },
      ],
      number: 0,
      totalPages: 1,
      totalElements: 1,
    },
    [`/api/reports/${MIGRATION_ID}/timeline?page=0&size=50`]: {
      content: [
        {
          stage: 'INTEGRITY',
          status: 'PASSED',
          fileId: 'file-1',
          occurredAt: '2026-10-11T10:04:00Z',
          message: '<integrity passed>',
        },
      ],
      number: 0,
      totalPages: 1,
      totalElements: 1,
    },
    [`/api/admin/reports/${MIGRATION_ID}/events?page=0&size=50`]: {
      content: [
        {
          eventId: 'event-1',
          eventType: 'INTEGRITY_VALIDATION_COMPLETED',
          sourceService: 'INTEGRITY_SERVICE',
          occurredAt: '2026-10-11T10:04:00Z',
          data: { failureReason: '<internal detail>' },
        },
      ],
      number: 0,
      totalPages: 1,
      totalElements: 1,
    },
    [`/api/admin/reports/${MIGRATION_ID}`]: summary,
    [`/api/admin/reports/${MIGRATION_ID}/files?page=0&size=20`]: {
      content: [
        {
          fileId: 'file-1',
          fileName: '<unsafe.mp4>',
          relativePath: '<folder/unsafe.mp4>',
          discoveryStatus: 'ACCEPTED',
          transferStatus: 'STORED',
          integrityStatus: 'PASSED',
          finalStatus: 'SUCCEEDED',
          failure: null,
        },
      ],
      number: 0,
      totalPages: 1,
      totalElements: 1,
    },
    ...(options.responses || {}),
  };

  const context = {
    document: {
      get visibilityState() {
        return visibilityState;
      },
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
      addEventListener(event, handler) {
        if (event === 'visibilitychange') visibilityHandlers.push(handler);
      },
    },
    window: {
      mmivAuth: {
        ready: Promise.resolve(),
        fetch: async (url) => {
          requests.push(url);
          let configured = responses[url];
          if (typeof configured === 'function') configured = configured(url);
          if (configured instanceof Error) throw configured;
          if (configured && typeof configured.httpStatus === 'number')
            return {
              ok: configured.httpStatus < 300,
              status: configured.httpStatus,
              json: async () => configured.body,
            };
          if (configured === undefined) throw new Error('Unexpected request ' + url);
          return { ok: true, status: 200, json: async () => configured };
        },
      },
    },
    location: { search: options.search ?? '?id=' + MIGRATION_ID },
    URLSearchParams,
    Date,
    encodeURIComponent,
    JSON,
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) {
      timers.delete(id);
    },
  };
  vm.runInNewContext(detailSource, context);
  const flush = async () => {
    for (let i = 0; i < 12; i += 1) await new Promise(setImmediate);
  };
  return {
    requests,
    timers,
    element: byId,
    flush,
    async runTimer() {
      const current = [...timers.entries()][0];
      assert.ok(current, 'expected a scheduled poll');
      timers.delete(current[0]);
      current[1].callback();
      await flush();
    },
    async visibility(value) {
      visibilityState = value;
      visibilityHandlers.forEach((handler) => handler());
      await flush();
    },
  };
}

test('rejects a non-UUID id before issuing a fetch', async () => {
  const app = detailPage({ search: '?id=not-a-uuid' });
  await app.flush();
  assert.deepEqual(app.requests, []);
  assert.equal(app.element('reportDetailStatus').textContent, 'This report link is invalid.');
});

test('loads summary files and sanitized timeline for an owned report', async () => {
  const app = detailPage();
  await app.flush();
  assert.deepEqual(app.requests, [
    '/api/access/me',
    `/api/reports/${MIGRATION_ID}`,
    `/api/reports/${MIGRATION_ID}/files?page=0&size=20`,
    `/api/reports/${MIGRATION_ID}/timeline?page=0&size=50`,
  ]);
  assert.equal(app.element('reportMigrationId').textContent, MIGRATION_ID);
  assert.equal(app.element('reportFileRows').children.length, 1);
  assert.equal(app.element('reportTimeline').children.length, 1);
});

test('does not request admin events for a USER profile', async () => {
  const app = detailPage();
  await app.flush();
  assert.equal(app.requests.some((url) => url.includes('/api/admin/reports/')), false);
  assert.equal(app.element('adminReportEvents').hidden, true);
});

test('requests /api/admin/reports/{id}/events only for current ADMIN', async () => {
  const app = detailPage({ profile: { userId: 'admin', username: 'admin', role: 'ADMIN' } });
  await app.flush();
  assert.ok(
    app.requests.includes(`/api/admin/reports/${MIGRATION_ID}/events?page=0&size=50`),
  );
  assert.ok(app.requests.includes(`/api/admin/reports/${MIGRATION_ID}`));
  assert.equal(app.requests.includes(`/api/reports/${MIGRATION_ID}`), false);
  assert.equal(app.element('adminReportEvents').hidden, false);
  assert.equal(app.element('adminEventRows').children.length, 1);
});

test('polls PROCESSING every ten seconds and stops on every terminal status', async () => {
  for (const terminal of ['COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'INCOMPLETE']) {
    const stopped = detailPage({ summary: { migrationId: MIGRATION_ID, status: terminal, summary: {} } });
    await stopped.flush();
    assert.equal(stopped.timers.size, 0, terminal + ' scheduled another poll');
  }
  const running = detailPage({
    summary: { migrationId: MIGRATION_ID, status: 'PROCESSING', summary: {} },
  });
  await running.flush();
  assert.equal([...running.timers.values()][0].delay, 10_000);
});

test('pauses while hidden and refreshes immediately when visible', async () => {
  const app = detailPage({
    summary: { migrationId: MIGRATION_ID, status: 'PROCESSING', summary: {} },
  });
  await app.flush();
  await app.visibility('hidden');
  assert.equal(app.timers.size, 0);
  const before = app.requests.length;
  await app.visibility('visible');
  assert.ok(app.requests.length > before);
  assert.equal(app.timers.size, 1);
});

test('failed refresh keeps already-rendered data', async () => {
  let summaryCalls = 0;
  const app = detailPage({
    summary: { migrationId: MIGRATION_ID, status: 'PROCESSING', summary: {} },
    responses: {
      [`/api/reports/${MIGRATION_ID}`]: () => {
        summaryCalls += 1;
        return summaryCalls === 1
          ? { migrationId: MIGRATION_ID, status: 'PROCESSING', summary: {} }
          : { httpStatus: 503, body: {} };
      },
    },
  });
  await app.flush();
  const rendered = app.element('reportMigrationId').textContent;
  await app.runTimer();
  assert.equal(app.element('reportMigrationId').textContent, rendered);
  assert.match(app.element('reportDetailStatus').textContent, /refresh|unavailable/i);
});

test('uses textContent for filenames paths failures and event values', async () => {
  const user = detailPage();
  const admin = detailPage({ profile: { userId: 'admin', username: 'admin', role: 'ADMIN' } });
  await user.flush();
  await admin.flush();
  assert.equal(detailSource.includes('innerHTML'), false);
  const serialized = JSON.stringify([
    user.element('reportFileRows').children,
    user.element('reportTimeline').children,
    admin.element('adminEventRows').children,
  ]);
  assert.ok(serialized.includes('<unsafe.mp4>'));
  assert.ok(serialized.includes('<folder/unsafe.mp4>'));
  assert.ok(serialized.includes('<integrity passed>'));
  assert.ok(serialized.includes('<internal detail>'));
});

test('detail page exposes report, file, timeline and admin event regions', () => {
  [
    'reportDetailStatus',
    'reportMigrationId',
    'reportFileRows',
    'reportTimeline',
    'adminReportEvents',
    'adminEventRows',
  ].forEach((id) => assert.ok(detailHtml.includes(`id="${id}"`), `missing ${id}`));
});
