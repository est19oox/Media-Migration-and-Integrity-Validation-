#!/usr/bin/env python3
"""Check workflow dependency order and execute registration waits with fake AWS."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


class CDContractTest(unittest.TestCase):
    def test_local_integrity_receives_the_same_aws_settings_as_transfer(self):
        for name in ('compose.yml', 'compose-scaled.yml'):
            with self.subTest(compose=name):
                services = yaml.safe_load((ROOT / 'infrastructure/service-registry' / name).read_text())['services']
                for setting in ('AWS_REGION', 'MMIV_BUCKET_NAME', 'AWS_ACCESS_KEY_ID',
                                'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN'):
                    self.assertEqual(services['integrity-service']['environment'][setting],
                                     services['transfer-service']['environment'][setting])

    def test_transfer_waits_for_verified_integrity_and_terraform(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        self.assertIn('deploy-integrity', jobs['deploy-backends']['needs'])
        self.assertEqual(jobs['deploy-integrity']['with']['service-name'], 'integrity-service')
        self.assertIn('deploy-registry', jobs['deploy-integrity']['needs'])
        self.assertIn('setup-host', jobs['deploy-integrity']['needs'])
        self.assertIn('terraform', jobs['setup-host']['needs'])
        self.assertEqual([s['name'] for s in jobs['deploy-backends']['strategy']['matrix']['service']],
                         ['transfer-service'])
        for job in jobs.values():
            needs = job.get('needs', [])
            if isinstance(needs, str):
                needs = [needs]
            self.assertTrue(set(needs) <= set(jobs))
        visiting, visited = set(), set()
        def visit(name):
            self.assertNotIn(name, visiting, 'Workflow dependency cycle')
            if name in visited:
                return
            visiting.add(name)
            needs = jobs[name].get('needs', [])
            for dependency in [needs] if isinstance(needs, str) else needs:
                visit(dependency)
            visiting.remove(name)
            visited.add(name)
        for name in jobs:
            visit(name)

    def run_wait(self, mode):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            aws = directory / 'aws'
            aws.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
if args[:2] == ['ssm', 'send-command']:
    print('command-1')
elif args[:2] == ['ssm', 'get-command-invocation']:
    counter = pathlib.Path(os.environ['TEST_COUNTER'])
    n = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(n))
    mode = os.environ['TEST_MODE']
    if n == 1:
        print('InvocationDoesNotExist', file=sys.stderr); sys.exit(1)
    if n == 2:
        print(json.dumps({'Status': 'InProgress'})); sys.exit(0)
    if mode == 'denied':
        print('AccessDeniedException', file=sys.stderr); sys.exit(1)
    status = 'Success' if mode == 'success' else 'Failed'
    print(json.dumps({'Status': status, 'ResponseCode': 0 if mode == 'success' else 1,
                     'StandardOutputContent': 'registered' if mode == 'success' else '',
                     'StandardErrorContent': '' if mode == 'success' else 'registration failed'}))
else:
    sys.exit('Unexpected AWS call: ' + str(args))
''')
            aws.chmod(0o755)
            sleep = directory / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            return subprocess.run(['bash', str(ROOT / 'scripts/cd/wait-for-registration.sh')],
                env={**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                     'INSTANCE_ID': 'i-test', 'SERVICE_NAME': 'transfer-service',
                     'TEST_MODE': mode, 'TEST_COUNTER': str(directory / 'count')},
                capture_output=True, text=True, timeout=10)

    def test_wait_handles_eventual_invocation_and_in_progress(self):
        result = self.run_wait('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('registered', result.stdout)

    def test_wait_propagates_failed_remote_command(self):
        result = self.run_wait('failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('registration failed', result.stderr)

    def test_wait_propagates_permission_error(self):
        result = self.run_wait('denied')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('AccessDeniedException', result.stderr)


if __name__ == '__main__':
    unittest.main()
