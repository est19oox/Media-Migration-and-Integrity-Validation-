#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('grant', ROOT / 'scripts/cd/grant-verification-permission.py')
grant = importlib.util.module_from_spec(spec)
spec.loader.exec_module(grant)
ACCOUNT = {'username': 'mmiv_ci_' + 'a' * 32, 'userId': '12345678-1234-4234-8234-123456789012'}


class VerificationPermissionTest(unittest.TestCase):
    def test_grant_sql_is_scoped_to_generated_active_user_and_destination(self):
        sql = grant.grant_sql(ACCOUNT, 'test-landing')
        self.assertIn("USERNAME='mmiv_ci_" + 'a' * 32 + "' AND ROLE='USER' AND ACTIVE=1", sql)
        self.assertIn("PERMISSION='WRITE'", sql)
        self.assertIn("IF found != 1", sql)
        self.assertNotIn('PASSWORD', sql)
        self.assertNotIn('UPDATE APP_USERS', sql)

    def test_ordinary_account_and_sql_injection_are_rejected(self):
        for account, destination in [({**ACCOUNT, 'username': 'ordinary'}, 'test'),
                                     ({**ACCOUNT, 'userId': "x';DELETE FROM APP_USERS"}, 'test'),
                                     (ACCOUNT, "x';DELETE FROM APP_USERS")]:
            with self.assertRaises(ValueError):
                grant.grant_sql(account, destination)

    def test_supplied_account_without_generated_cache_is_not_modified(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'MMIV_VERIFICATION_ACCOUNT_FILE': str(Path(temporary) / 'missing.json')}), patch.object(grant.subprocess, 'run') as run:
            grant.main()
            run.assert_not_called()

    def test_external_database_requires_preprovisioned_test_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary) / 'account.json'
            cache.write_text(json.dumps(ACCOUNT))
            with patch.dict(os.environ, {'MMIV_VERIFICATION_ACCOUNT_FILE': str(cache), 'MMIV_MANAGED_VERIFICATION_DB': 'false'}), patch.object(grant.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'supplied account'):
                    grant.main()
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
