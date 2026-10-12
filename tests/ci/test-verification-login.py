import importlib.util
import json
from urllib.error import HTTPError
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


TOKEN = 'test.eyJzdWIiOiAiVTAwMSJ9.signature'

class Response:
    def __init__(self, body, status=200):
        self.body = json.dumps(body).encode()
        self.status = status
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass
    def read(self):
        return self.body


class LoginTest(unittest.TestCase):
    def run_login(self, path, result, service="api-gateway"):
        with patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(path)]), \
             patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'alice',
                                     'USER_ACCESS_TEST_PASSWORD': 'test-password',
                                     'DAST_SERVICE_NAME': service}, clear=True), \
             patch.object(login, 'build_opener') as factory:
            factory.return_value.open.return_value = Response(result)
            login.main()
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(json.loads(request.data),
                             {'username': 'alice', 'password': 'test-password'})
            self.assertEqual(request.full_url,
                             'http://127.0.0.1:18080' + ('/api/auth/login' if service == 'user-access-service' else '/user-access/api/auth/login'))

    def test_bearer_jwt_is_written_privately(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            self.run_login(path, {'accessToken': TOKEN, 'tokenType': 'Bearer'})
            self.assertIn('API_TEST_TOKEN=' + TOKEN, path.read_text())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_user_access_scan_uses_its_native_login_endpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run_login(Path(directory) / 'auth.env',
                           {'accessToken': TOKEN, 'tokenType': 'Bearer'}, 'user-access-service')

    def test_rewrite_repairs_existing_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            path.write_text('old')
            path.chmod(0o644)
            self.run_login(path, {'accessToken': TOKEN, 'tokenType': 'Bearer'})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_malformed_tokens_and_wrong_type_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for result in [{'accessToken': 'not-a-jwt', 'tokenType': 'Bearer'},
                           {'accessToken': TOKEN, 'tokenType': 'Basic'},
                           {'accessToken': 'a.b.c\nINJECTED=value', 'tokenType': 'Bearer'}]:
                path = Path(directory) / 'auth.env'
                with self.assertRaises(ValueError):
                    self.run_login(path, result)
                self.assertFalse(path.exists())

    def test_generated_fixture_registers_then_logs_in_on_an_empty_database(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'auth.env'
            with patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(path)]), \
                 patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'ci_smoke_fixture',
                                         'USER_ACCESS_TEST_PASSWORD': 'generated-password',
                                         'MMIV_SMOKE_AUTO_SIGNUP': 'true'}, clear=True), \
                 patch.object(login, 'build_opener') as factory:
                factory.return_value.open.side_effect = [
                    HTTPError('login', 401, 'unauthorized', {}, None),
                    Response({'userId': 'U001'}, 201),
                    Response({'accessToken': TOKEN, 'tokenType': 'Bearer'})]
                login.main()
                self.assertEqual([c.args[0].full_url.rsplit('/', 1)[-1]
                                  for c in factory.return_value.open.call_args_list],
                                 ['login', 'signup', 'login'])
                self.assertIn('USER_ACCESS_TEST_USER_ID=U001', path.read_text())

    def test_supplied_wrong_password_never_registers_or_overwrites_an_account(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(login.sys, 'argv', ['login', 'http://127.0.0.1:18080', str(Path(directory) / 'auth.env')]), \
                 patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': 'existing',
                                         'USER_ACCESS_TEST_PASSWORD': 'wrong-password'}, clear=True), \
                 patch.object(login, 'build_opener') as factory:
                factory.return_value.open.side_effect = HTTPError('login', 401, 'unauthorized', {}, None)
                with self.assertRaises(HTTPError):
                    login.main()
                self.assertEqual(factory.return_value.open.call_count, 1)

    def test_non_tunnel_urls_never_send_credentials(self):
        for base in ['http://127.0.0.1:80@evil.example', 'http://127.0.0.1:80/?redirect=1',
                     'http://localhost:80', 'https://127.0.0.1:80', 'http://127.0.0.1:80/path']:
            with patch.object(login.sys, 'argv', ['login', base, '/unused']), \
                 patch.object(login, 'build_opener') as opener:
                with self.assertRaises(ValueError):
                    login.main()
                opener.assert_not_called()


class AutomaticCredentialsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('auto_verification',
            ROOT / 'scripts/cd/verify-deployed-services.py')
        cls.verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.verifier)

    def test_empty_config_generates_one_strong_normal_account_fixture(self):
        credentials = self.verifier.verification_credentials({})
        self.assertRegex(credentials['USER_ACCESS_TEST_USERNAME'], r'^ci_smoke_[a-f0-9]{24}$')
        self.assertGreaterEqual(len(credentials['USER_ACCESS_TEST_PASSWORD']), 32)
        self.assertLessEqual(len(credentials['USER_ACCESS_TEST_PASSWORD'].encode()), 72)
        self.assertEqual(credentials['MMIV_SMOKE_AUTO_SIGNUP'], 'true')

    def test_existing_credentials_are_preserved(self):
        self.assertEqual(self.verifier.verification_credentials({
            'USER_ACCESS_TEST_USERNAME': 'existing', 'USER_ACCESS_TEST_PASSWORD': 'existing-password'}), {})

    def test_partial_credentials_fail_instead_of_silently_creating_accounts(self):
        with self.assertRaises(ValueError):
            self.verifier.verification_credentials({'USER_ACCESS_TEST_USERNAME': 'existing'})


if __name__ == '__main__':
    unittest.main()
