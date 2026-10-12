import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verification_login',
    ROOT / 'scripts/cd/login-for-verification.py')
login = importlib.util.module_from_spec(spec)
spec.loader.exec_module(login)


class Response:
    def __init__(self, body):
        self.body = json.dumps(body).encode()
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass
    def read(self):
        return self.body


class LoginTest(unittest.TestCase):
    def run_login(self, path, result):
        with patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(path)]), \
             patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'alice',
                                     'USER_ACCESS_TEST_PASSWORD': 'test-password'}), \
             patch.object(login, 'build_opener') as factory:
            factory.return_value.open.return_value = Response(result)
            login.main()
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(json.loads(request.data),
                             {'username': 'alice', 'password': 'test-password'})
            self.assertEqual(request.full_url,
                             'http://127.0.0.1:18080/user-access/api/auth/login')

    def test_bearer_jwt_is_written_privately(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            self.run_login(path, {'accessToken': 'test.header.signature', 'tokenType': 'Bearer'})
            self.assertIn('API_TEST_TOKEN=test.header.signature', path.read_text())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_rewrite_repairs_existing_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            path.write_text('old')
            path.chmod(0o644)
            self.run_login(path, {'accessToken': 'test.header.signature', 'tokenType': 'Bearer'})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_malformed_tokens_and_wrong_type_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for result in [{'accessToken': 'not-a-jwt', 'tokenType': 'Bearer'},
                           {'accessToken': 'test.header.signature', 'tokenType': 'Basic'},
                           {'accessToken': 'a.b.c\nINJECTED=value', 'tokenType': 'Bearer'}]:
                path = Path(directory) / 'auth.env'
                with self.assertRaises(ValueError):
                    self.run_login(path, result)
                self.assertFalse(path.exists())

    def test_non_tunnel_urls_never_send_credentials(self):
        for base in ['http://127.0.0.1:80@evil.example', 'http://127.0.0.1:80/?redirect=1',
                     'http://localhost:80', 'https://127.0.0.1:80', 'http://127.0.0.1:80/path']:
            with patch.object(login.sys, 'argv', ['login', base, '/unused']), \
                 patch.object(login, 'build_opener') as opener:
                with self.assertRaises(ValueError):
                    login.main()
                opener.assert_not_called()


if __name__ == '__main__':
    unittest.main()
