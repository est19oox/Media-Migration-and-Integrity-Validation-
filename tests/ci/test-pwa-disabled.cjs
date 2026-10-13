const { test } = require('node:test');
const assert = require('node:assert/strict');
const { existsSync, readFileSync, readdirSync } = require('node:fs');

test('unused PWA resources are absent and no page references them', () => {
  const staticDir = 'services/transfer-service/src/main/resources/static';
  for (const file of ['manifest.webmanifest', 'pwa.js', 'sw.js', 'icon-192.png', 'icon-512.png']) {
    assert.equal(existsSync(`${staticDir}/${file}`), false);
  }

  const gateway = readFileSync('services/api-gateway/src/main/resources/application.yml', 'utf8');
  assert.doesNotMatch(gateway, /manifest\.webmanifest|icon-(?:192|512)\.png|\/sw\.js|\/pwa\.js/);

  const security = readFileSync(
    'services/transfer-service/src/main/java/mmiv/transfer/config/TransferSecurityConfig.java',
    'utf8',
  );
  assert.doesNotMatch(security, /manifest\.webmanifest|icon-(?:192|512)\.png|\/sw\.js|\/pwa\.js/);

  for (const service of ['transfer-service', 'user-access-service']) {
    const dir = `services/${service}/src/main/resources/static`;
    for (const file of readdirSync(dir).filter((file) => file.endsWith('.html'))) {
      const html = readFileSync(`${dir}/${file}`, 'utf8');
      assert.doesNotMatch(html, /manifest\.webmanifest|icon-(?:192|512)\.png|\/sw\.js|\/pwa\.js/);
    }
  }
});
