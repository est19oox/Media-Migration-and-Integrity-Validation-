const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

test('one folder submission sends one request and restores the button', async () => {
  const page = readFileSync(resolve(__dirname,
    '../../services/transfer-service/src/main/resources/static/upload.html'), 'utf8');
  const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
  const listeners = {};
  const elements = new Map();
  const select = selector => {
    if (!elements.has(selector)) elements.set(selector, {
      addEventListener(type, callback) {
        const key = selector + ':' + type;
        (listeners[key] ||= []).push(callback);
      },
      files: [], disabled: false, textContent: '', options: [], value: '',
      replaceChildren() { this.options = []; },
      append(option) { this.options.push(option); this.value ||= option.value; }
    });
    return elements.get(selector);
  };
  const requests = [];
  class FormData {
    constructor() { this.parts = []; }
    append(...part) { this.parts.push(part); }
  }
  vm.runInNewContext(script, {
    document: { querySelector: select, createElement: () => ({ value: '', textContent: '' }) }, FormData, encodeURIComponent,
    mmivAuth: { fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => url === '/api/v1/destinations' ? { destinations: [{ destination: 'landing', permission: 'WRITE' }] } : ({ succeeded: 1, failed: 0 }) };
    }}
  });
  await new Promise(setImmediate);
  assert.equal(requests[0].url, '/api/v1/destinations');
  assert.equal(select('#destination').value, 'landing');
  requests.length = 0;
  select('#folder').files = [{ name: 'a.docx', size: 4, webkitRelativePath: 'Office/docs/a.docx' }];
  const handlers = listeners['#folderForm:submit'];
  let prevented = 0;
  await Promise.all(handlers.map(handler => handler({ preventDefault() { prevented++; } })));
  assert.equal(handlers.length, 1);
  assert.equal(prevented, 1);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/v1/transfers/folder');
  assert.deepEqual(requests[0].options.body.parts.map(part => part[0]), ['destinationId', 'files', 'relativePaths']);
  assert.equal(requests[0].options.body.parts[0][1], 'landing');
  assert.equal(requests[0].options.body.parts[2][1], 'Office/docs/a.docx');
  assert.equal(select('#folderSubmit').disabled, false);
});


test('upload controls remain disabled when destination permission is denied', async () => {
  const page = readFileSync(resolve(__dirname, '../../services/transfer-service/src/main/resources/static/upload.html'), 'utf8');
  const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
  const elements = new Map();
  const select = selector => {
    if (!elements.has(selector)) elements.set(selector, { addEventListener() {}, disabled: false, textContent: '' });
    return elements.get(selector);
  };
  vm.runInNewContext(script, { document: { querySelector: select }, FormData: class {},
    mmivAuth: { fetch: async () => ({ ok: false, status: 403 }) } });
  await new Promise(setImmediate);
  assert.equal(select('#submit').disabled, true);
  assert.equal(select('#folderSubmit').disabled, true);
  assert.match(select('#destinationStatus').textContent, /权限/);
});
