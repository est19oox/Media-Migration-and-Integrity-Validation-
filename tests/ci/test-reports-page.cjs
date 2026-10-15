const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('frontend/js/reports.js', 'utf8');
const html = readFileSync('frontend/pages/reports.html', 'utf8');
const REPORTS = '/api/reports/mine';

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
  assert.ok(html.includes('<script src="../js/reports.js"></script>'));
  assert.equal(html.includes('Demo data'), false);
});
