#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'verification', ROOT / 'scripts/cd/verify-deployed-services.py')
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


class VerificationOutcomeTest(unittest.TestCase):
    def state(self):
        return {
            'INSTANCE_ID': 'i-test',
            'READY': True,
            'ROLLED_BACK': False,
            'COMMAND_STATUS': 'Success',
            'COMMAND_RESPONSE_CODE': 0,
            'services': {
                name: {'image': 'registry/mmiv:' + name, 'port': verification.docker_ssm.PORTS[name]}
                for name in verification.expected_services()
            },
        }

    def run_verification(self, scan_code=0, data_cleanup_code=0, account_cleanup_code=0):
        def launch(*args, **kwargs):
            Path(kwargs['env']['MMIV_VERIFICATION_ACCOUNT_FILE']).write_text('{}')
            return SimpleNamespace(stdout=['scanner output\n'], wait=lambda: scan_code)

        cleanup_results = [
            SimpleNamespace(returncode=data_cleanup_code),
            SimpleNamespace(returncode=account_cleanup_code),
        ]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', side_effect=launch), \
                patch.object(verification.subprocess, 'run', side_effect=cleanup_results):
            root = Path(directory)
            result = verification.verify(self.state(), root)
            summary = json.loads((root / 'verification-summary.json').read_text())
        return result, summary

    def test_verification_data_cleanup_failure_is_recorded_but_does_not_fail_release(self):
        result, summary = self.run_verification(data_cleanup_code=1)

        self.assertEqual(result, 0)
        cleanup = next(item for item in summary if item['service'] == 'verification-data-cleanup')
        self.assertEqual(cleanup['exit_code'], 1)
        self.assertFalse(cleanup['blocking'])

    def test_verification_account_cleanup_failure_is_recorded_but_does_not_fail_release(self):
        result, summary = self.run_verification(account_cleanup_code=1)

        self.assertEqual(result, 0)
        cleanup = next(item for item in summary if item['service'] == 'verification-account-cleanup')
        self.assertEqual(cleanup['exit_code'], 1)
        self.assertFalse(cleanup['blocking'])

    def test_service_scan_failure_still_fails_release(self):
        result, summary = self.run_verification(scan_code=1)

        self.assertEqual(result, 1)
        scan = next(item for item in summary if item['service'] == 'transfer-service')
        self.assertEqual(scan['exit_code'], 1)
        self.assertTrue(scan['blocking'])


if __name__ == '__main__':
    unittest.main()
