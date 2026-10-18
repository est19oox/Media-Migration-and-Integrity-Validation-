#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime', ROOT / 'scripts/cd/load-runtime-config.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

class RuntimeConfigTest(unittest.TestCase):
    def test_deployed_gateway_probe_uses_gateway_owned_login_page(self):
        source = (ROOT / 'scripts/cd/verify-deployed-services.py').read_text()
        self.assertIn("'/pages/login.html' if name == 'api-gateway'", source)
        self.assertNotIn("'/upload.html' if name == 'api-gateway'", source)

    def test_default_uses_managed_oracle_without_github_secrets(self):
        self.assertEqual(runtime.values({}, 'deployment'), {
            'MANAGED_USER_ACCESS_ORACLE': 'true', 'ORACLE_CONFIG_REQUESTED': 'false'})

    def config(self):
        private = subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'],
                                 capture_output=True, text=True, check=True).stdout
        return {'userAccess': {'dbUrl': 'jdbc:oracle:thin:@//db:1521/FREEPDB1',
                               'dbUsername': 'USER_ACCESS', 'dbPassword': 'test-$-password', 'jwtPrivateKey': private},
                'discovery': {'dbUrl': 'jdbc:oracle:thin:@//db:1521/FREEPDB1',
                              'dbUsername': 'DISCOVERY', 'dbPassword': 'test-discovery-password'}}

    def test_one_bundle_derives_public_key_and_preserves_password(self):
        config = self.config()
        env = runtime.values(config, 'deployment')
        self.assertTrue(env['USER_ACCESS_JWT_PUBLIC_KEY'].startswith('-----BEGIN PUBLIC KEY-----'))
        self.assertEqual(env['USER_ACCESS_DB_PASSWORD'], 'test-$-password')
        self.assertEqual(env['ORACLE_CONFIG_REQUESTED'], 'false')

    def test_optional_integrity_and_smoke_share_the_bundle(self):
        config = self.config()
        config['integrity'] = {'dbUrl': 'jdbc:oracle:thin:@//db:1521/FREEPDB1', 'dbUsername': 'INTEGRITY_SERVICE', 'dbPassword': 'integrity-test'}
        config['userAccessSmoke'] = {'username': 'smoke', 'password': 'smoke-test', 'userId': 'smoke-id'}
        self.assertEqual(runtime.values(config, 'deployment')['ORACLE_CONFIG_REQUESTED'], 'true')
        self.assertEqual(runtime.values(config, 'verification')['USER_ACCESS_TEST_USER_ID'], 'smoke-id')
        self.assertEqual(runtime.values({}, 'verification'), {})

    def test_missing_and_multiline_credentials_fail(self):
        config = self.config()
        for value in ('', 'password\nINJECTED=1'):
            config['userAccess']['dbPassword'] = value
            with self.assertRaises(ValueError): runtime.values(config, 'deployment')

    def test_invalid_private_key_and_partial_optional_section_fail(self):
        config = self.config()
        config['userAccess']['jwtPrivateKey'] = '-----BEGIN PRIVATE KEY-----\ninvalid\n-----END PRIVATE KEY-----'
        with self.assertRaises(ValueError): runtime.values(config, 'deployment')
        with self.assertRaises(ValueError): runtime.values({'userAccessSmoke': {'username': 'only'}}, 'verification')

    def test_multiline_export_is_masked_and_not_interpreted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'env'
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                runtime.export({'TEST': 'dollar$\n-----END PRIVATE KEY-----'}, path)
            self.assertIn('::add-mask::dollar$%0A', output.getvalue())
            self.assertIn('dollar$\n-----END PRIVATE KEY-----\nMMIV_', path.read_text())

if __name__ == '__main__': unittest.main()
