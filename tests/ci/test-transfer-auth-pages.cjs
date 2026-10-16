const assert = require('node:assert/strict');
const {test} = require('node:test');
const {existsSync, readFileSync} = require('node:fs');
const {resolve} = require('node:path');

const root = resolve(__dirname, '../..');
const transfer = resolve(root, 'services/transfer-service');
const source = relative => readFileSync(resolve(transfer, relative), 'utf8');

test('Transfer no longer owns browser sessions or cookie authentication', () => {
  assert.equal(existsSync(resolve(transfer,
    'src/main/java/mmiv/transfer/controller/BrowserSessionController.java')), false);
  const security = source('src/main/java/mmiv/transfer/config/TransferSecurityConfig.java');
  assert.doesNotMatch(security, /MMIV_SESSION|BearerTokenResolver|CookieCsrfTokenRepository/);
  assert.doesNotMatch(security, /\/api\/v1\/session/);
  assert.match(security, /oauth2ResourceServer/);
});

test('Transfer no longer proxies profile or reporting reads', () => {
  const controller = source('src/main/java/mmiv/transfer/controller/PortalController.java');
  const client = source('src/main/java/mmiv/transfer/integration/PortalClient.java');
  const config = source('src/main/resources/application.yml');
  assert.doesNotMatch(controller, /@GetMapping\("\/(?:me|history|logs)"\)/);
  assert.doesNotMatch(client, /reporting|reportBase|dashboard\s*\(/);
  assert.doesNotMatch(config, /cookie-secure|mmiv\.reporting\.url/);
});

test('Transfer keeps file, folder and administrator mutation endpoints', () => {
  const controller = source('src/main/java/mmiv/transfer/controller/PortalController.java');
  for (const mapping of [
    '@GetMapping("/files")',
    '@DeleteMapping("/files/{id}")',
    '@DeleteMapping("/folders")',
    '@PostMapping("/admin/folders")',
    '@PutMapping("/admin/users/{id}/permissions")',
    '@DeleteMapping("/admin/users/{id}")'
  ]) assert.ok(controller.includes(mapping), `missing retained mapping: ${mapping}`);
});
