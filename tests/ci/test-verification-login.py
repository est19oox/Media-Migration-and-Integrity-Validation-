import importlib.util
import json
import os
import subprocess
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
    def __init__(self, body, status=200):
        self.status = status
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
            self.run_login(path, {'accessToken': 'test.eyJzdWIiOiJhY3R1YWwtdXNlciJ9.signature', 'tokenType': 'Bearer'})
            self.assertIn('API_TEST_TOKEN=test.eyJzdWIiOiJhY3R1YWwtdXNlciJ9.signature', path.read_text())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_rewrite_repairs_existing_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            path.write_text('old')
            path.chmod(0o644)
            self.run_login(path, {'accessToken': 'test.eyJzdWIiOiJhY3R1YWwtdXNlciJ9.signature', 'tokenType': 'Bearer'})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_malformed_tokens_and_wrong_type_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for result in [{'accessToken': 'not-a-jwt', 'tokenType': 'Bearer'},
                           {'accessToken': 'test.eyJzdWIiOiJhY3R1YWwtdXNlciJ9.signature', 'tokenType': 'Basic'},
                           {'accessToken': 'a.b.c\nINJECTED=value', 'tokenType': 'Bearer'}]:
                path = Path(directory) / 'auth.env'
                with self.assertRaises(ValueError):
                    self.run_login(path, result)
                self.assertFalse(path.exists())

    def test_generated_account_is_reused_and_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            cache = Path(directory) / 'account.json'
            with patch.dict(os.environ, {'MMIV_VERIFICATION_ACCOUNT_FILE': str(cache)}, clear=True), \
                 patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(path)]), \
                 patch.object(login, 'build_opener') as factory:
                token = 'test.eyJzdWIiOiJhY3R1YWwtdXNlciJ9.signature'
                factory.return_value.open.side_effect = [
                    Response({'userId': 'actual-user'}, 201),
                    Response({'accessToken': token, 'tokenType': 'Bearer'}),
                    Response({'accessToken': token, 'tokenType': 'Bearer'}),
                    Response({'accessToken': token, 'tokenType': 'Bearer'}), Response({}, 204)]
                login.main()
                saved = json.loads(cache.read_text())
                self.assertRegex(saved['username'], r'^mmiv_ci_[a-f0-9]{32}$')
                self.assertEqual(cache.stat().st_mode & 0o777, 0o600)
                self.assertNotIn(saved['password'], path.read_text())
                login.main()
                login.sys.argv += ['--cleanup']
                login.main()
                self.assertFalse(cache.exists())
                requests = [c.args[0] for c in factory.return_value.open.call_args_list]
                self.assertEqual(sum(r.full_url.endswith('/signup') for r in requests), 1)
                self.assertEqual(requests[-1].method, 'DELETE')
                self.assertTrue(requests[-1].full_url.endswith('/verification-account'))

    def test_partial_supplied_credentials_never_create_accounts(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'existing'}, clear=True), \
             patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(Path(directory) / 'env')]), \
             patch.object(login, 'build_opener') as factory:
            with self.assertRaises(ValueError): login.main()
            factory.return_value.open.assert_not_called()

    def test_missing_cleanup_cache_never_deletes_supplied_account(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'existing',
                                     'USER_ACCESS_TEST_PASSWORD': 'test-password'}, clear=True), \
             patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(Path(directory) / 'env'), '--cleanup']), \
             patch.object(login, 'build_opener') as factory:
            login.main()
            factory.return_value.open.assert_not_called()

    def test_all_refreshed_tokens_and_password_are_redacted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); reports = root / 'reports'; reports.mkdir()
            history = root / 'tokens.txt'; history.write_text('old-jwt\nnew-jwt\n')
            report = reports / 'zap.log'
            report.write_text('old-jwt new-jwt verification-password verification-user')
            result = subprocess.run(['python3', str(ROOT / 'scripts/cd/redact-verification-token.py'), str(reports)],
                env={**os.environ, 'API_TEST_TOKEN': 'new-jwt', 'MMIV_VERIFICATION_TOKEN_FILE': str(history),
                     'USER_ACCESS_TEST_PASSWORD': 'verification-password', 'USER_ACCESS_TEST_USERNAME': 'verification-user'},
                capture_output=True)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(report.read_text(), '[REDACTED] [REDACTED] [REDACTED] [REDACTED]')

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
