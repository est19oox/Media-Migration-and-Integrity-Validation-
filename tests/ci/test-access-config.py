#!/usr/bin/env python3
"""Exercise real private configuration staging without AWS or a database."""
import getpass
import grp
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]

class AccessConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.inventory = self.root / 'inventory.ini'
        self.inventory.write_text('[mmiv]\nlocalhost ansible_connection=local\n')
        self.env = {**os.environ, 'MMIV_RELEASE_ID': 'test-1',
                    'USER_ACCESS_DB_URL': 'jdbc:oracle:thin:@//db.internal:1521/FREEPDB1',
                    'USER_ACCESS_DB_USERNAME': 'USER_ACCESS', 'USER_ACCESS_DB_PASSWORD': 'test-$-quoted-password',
                    'USER_ACCESS_JWT_ISSUER': 'https://auth.mmiv.example', 'USER_ACCESS_JWT_KEY_ID': 'test-key',
                    'USER_ACCESS_JWT_PRIVATE_KEY': '-----BEGIN PRIVATE KEY-----\nlocal-fixture\n-----END PRIVATE KEY-----',
                    'USER_ACCESS_JWT_PUBLIC_KEY': '-----BEGIN PUBLIC KEY-----\nlocal-fixture\n-----END PUBLIC KEY-----',
                    'ANSIBLE_LOCAL_TEMP': str(self.root / 'ansible-tmp')}

    def stage(self, success=True):
        extra = {'ansible_become': False, 'ansible_connection': 'local',
                 'ansible_python_interpreter': shutil.which('python3'),
                 'mmiv_configuration_directory': str(self.root / 'config'),
                 'mmiv_configuration_owner': getpass.getuser(),
                 'mmiv_configuration_group': grp.getgrgid(os.getgid()).gr_name}
        result = subprocess.run([shutil.which('ansible-playbook'), '-i', str(self.inventory),
                                 str(ROOT / 'infrastructure/ansible/stage-user-access.yml'),
                                 '-e', json.dumps(extra)], env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        if success:
            pointer = self.root / 'config' / ('next-user-access-' + self.env['MMIV_RELEASE_ID'] + '.json')
            config_id = json.loads(pointer.read_text())['config_id']
            return self.root / 'config/user-access/versions' / config_id, result.stdout

    def test_private_bundle_repeat_is_unchanged(self):
        bundle, _ = self.stage()
        env = (bundle / 'environment.env').read_text()
        self.assertIn('ORACLE_PASSWORD=test-$-quoted-password\n', env)
        self.assertIn('JWT_PRIVATE_KEY_PATH=file:/run/mmiv-jwt/private.pem', env)
        self.assertEqual(stat.S_IMODE(bundle.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((bundle / 'private.pem').stat().st_mode), 0o600)
        first = (bundle / 'private.pem').stat().st_mtime_ns
        again, output = self.stage()
        self.assertEqual(bundle, again)
        self.assertEqual(first, (bundle / 'private.pem').stat().st_mtime_ns)
        self.assertRegex(output, r'changed=0\b')

    def test_rotation_keeps_old_bundle(self):
        original, _ = self.stage()
        self.env.update(MMIV_RELEASE_ID='test-2', USER_ACCESS_DB_PASSWORD='new-password')
        updated, _ = self.stage()
        self.assertNotEqual(original, updated)
        self.assertTrue((original / 'private.pem').exists())
        self.assertIn('new-password', (updated / 'environment.env').read_text())

    def test_missing_or_multiline_setting_fails_before_staging(self):
        for value in ('', 'password\ninjected=1'):
            self.env['USER_ACCESS_DB_PASSWORD'] = value
            self.stage(success=False)
        self.assertFalse((self.root / 'config').exists())

if __name__ == '__main__': unittest.main()
