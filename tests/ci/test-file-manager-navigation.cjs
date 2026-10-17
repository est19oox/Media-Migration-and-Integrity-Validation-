const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('frontend/js/file-manager.js', 'utf8');

function node(tagName = 'DIV') {
  return {
    tagName,
    className: '',
    textContent: '',
    value: '',
    hidden: false,
    disabled: false,
    attributes: {},
    listeners: {},
    children: [],
    append(...children) {
      this.children.push(...children);
    },
    replaceChildren(...children) {
      this.children = children;
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
  };
}

async function loadManager() {
  const elements = new Map();
  const byId = (id) => {
    if (!elements.has(id)) elements.set(id, node(id === 'folder' ? 'SELECT' : 'DIV'));
    return elements.get(id);
  };
  const files = [
    {
      fileId: 'semester-2-file',
      name: 'AY2026-2027/Semester_2/CS2107/assignment.pdf',
      sizeBytes: 400,
      modifiedAt: '2026-10-17T12:00:00Z',
      canDelete: true,
    },
    {
      fileId: 'semester-1-file',
      name: 'AY2026-2027/Semester_1/CS2107/lecture.pptx',
      sizeBytes: 500,
      modifiedAt: '2026-10-17T12:00:00Z',
      canDelete: true,
    },
  ];
  const context = {
    document: {
      body: node('BODY'),
      getElementById: byId,
      createElement: (tag) => node(String(tag).toUpperCase()),
      querySelector: () => null,
      querySelectorAll: () => [],
    },
    window: {
      confirm: () => true,
      mmivAuth: {
        ready: Promise.resolve(),
        fetch: async (url) => {
          if (url === '/api/access/me')
            return {
              ok: true,
              status: 200,
              json: async () => ({
                role: 'USER',
                destinations: [{ destination: 'new', permission: 'READ' }],
              }),
            };
          if (url === '/api/v1/portal/files?folder=new')
            return { ok: true, status: 200, json: async () => ({ files, nextCursor: null }) };
          throw new Error('Unexpected request ' + url);
        },
      },
      mmivPreview: { open() {} },
    },
    location: { search: '?folder=new' },
    URLSearchParams,
    IntersectionObserver: undefined,
    encodeURIComponent,
    Date,
    setTimeout,
    clearTimeout,
    FormData,
  };

  vm.runInNewContext(source, context);
  for (let i = 0; i < 12; i += 1) await new Promise(setImmediate);
  return { element: byId };
}

function folderButton(row) {
  return row.children[0].children[0];
}

test('file manager groups stored paths into navigable directory levels', async () => {
  const app = await loadManager();

  let rows = app.element('files').children;
  assert.equal(rows.length, 1);
  assert.equal(folderButton(rows[0]).children[1].textContent, 'AY2026-2027');

  folderButton(rows[0]).listeners.click();
  rows = app.element('files').children;
  assert.deepEqual(
    rows.map((row) => folderButton(row).children[1].textContent),
    ['Semester_1', 'Semester_2'],
  );

  folderButton(rows[0]).listeners.click();
  rows = app.element('files').children;
  assert.equal(folderButton(rows[0]).children[1].textContent, 'CS2107');

  folderButton(rows[0]).listeners.click();
  rows = app.element('files').children;
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[0].children[0].children[1].textContent, 'lecture.pptx');
});

test('file manager breadcrumbs return to the selected destination root', async () => {
  const app = await loadManager();
  folderButton(app.element('files').children[0]).listeners.click();

  const root = app
    .element('breadcrumbs')
    .children.find((child) => child.tagName === 'BUTTON' && child.textContent === 'new');
  assert.ok(root, 'destination root breadcrumb is missing');
  root.listeners.click();

  const rows = app.element('files').children;
  assert.equal(rows.length, 1);
  assert.equal(folderButton(rows[0]).children[1].textContent, 'AY2026-2027');
});
