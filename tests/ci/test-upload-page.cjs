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
      files: [], disabled: false, textContent: ''
    });
    return elements.get(selector);
  };
  const requests = [];
  class FormData {
    constructor() { this.parts = []; }
    append(...part) { this.parts.push(part); }
  }
  vm.runInNewContext(script, {
    document: { querySelector: select }, FormData, encodeURIComponent,
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => ({ succeeded: 1, failed: 0 }) };
    }
  });
  select('#folder').files = [{ name: 'a.docx', size: 4, webkitRelativePath: 'Office/docs/a.docx' }];
  const handlers = listeners['#folderForm:submit'];
  let prevented = 0;
  await Promise.all(handlers.map(handler => handler({ preventDefault() { prevented++; } })));
  assert.equal(handlers.length, 1);
  assert.equal(prevented, 1);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/v1/transfers/folder');
  assert.deepEqual(requests[0].options.body.parts.map(part => part[0]), ['files', 'relativePaths']);
  assert.equal(requests[0].options.body.parts[1][1], 'Office/docs/a.docx');
  assert.equal(select('#folderSubmit').disabled, false);
});
