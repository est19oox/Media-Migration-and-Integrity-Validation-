#!/usr/bin/env python3
"""Check workflow dependency order and scanner preparation with fake Docker."""
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

    def test_deployments_finish_before_tests_and_gateway_is_last(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        self.assertNotIn('deploy-integrity', jobs)
        self.assertIn('deploy-registry', jobs['deploy-backends']['needs'])
        self.assertIn('setup-host', jobs['deploy-backends']['needs'])
        self.assertIn('terraform', jobs['setup-host']['needs'])
        self.assertEqual([s['name'] for s in jobs['deploy-backends']['strategy']['matrix']['service']],
                         ['transfer-service', 'integrity-service'])
        self.assertIn('deploy-backends', jobs['deploy-gateway']['needs'])
        self.assertIn('deploy-gateway', jobs['verify-services']['needs'])
        self.assertIn('deploy-backends', jobs['verify-services']['needs'])
        deploy = yaml.safe_load((ROOT / '.github/workflows/deploy-service.yml').read_text())
        steps = deploy['jobs']['deploy']['steps']
        self.assertFalse(any('run-dast-ssm.sh' in s.get('run', '') for s in steps))
        self.assertTrue(any('docker-service-ssm.py deploy' in s.get('run', '') for s in steps))
        self.assertFalse(any('playbook.yml' in s.get('run', '') for s in steps))
        verify = yaml.safe_load((ROOT / '.github/workflows/verify-services.yml').read_text())
        verification_steps = verify['jobs']['verify']['steps']
        self.assertFalse(any('ansible-playbook' in s.get('run', '') for s in verification_steps))
        self.assertTrue(any('verify-deployed-services.py verify' in s.get('run', '') for s in verification_steps))
        self.assertTrue(any('verify-deployed-services.py rollback' in s.get('run', '') for s in verification_steps))
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

    def test_prepared_host_is_reused_and_scanner_prepared_once(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        for name in ('deploy-registry', 'deploy-backends', 'deploy-gateway'):
            self.assertIs(jobs[name]['with']['host-ready'], True)
            self.assertIn('setup-host', jobs[name]['needs'])
        deploy = yaml.safe_load((ROOT / '.github/workflows/deploy-service.yml').read_text())
        steps = deploy['jobs']['deploy']['steps']
        wait = next(s for s in steps if 'wait-for-ssm.sh' in s.get('run', ''))
        self.assertIn('!inputs.host-ready', wait['if'])
        self.assertFalse(any('smoke-test-service.sh' in s.get('run', '') or
                             'wait-for-registration.sh' in s.get('run', '') for s in steps))
        verify = yaml.safe_load((ROOT / '.github/workflows/verify-services.yml').read_text())
        steps = verify['jobs']['verify']['steps']
        preparation = [s for s in steps if 'prepare-scanner-tools.sh' in s.get('run', '')]
        self.assertEqual(len(preparation), 1)
        self.assertIn('SCANNER_TOOLS_PREPARED=true', preparation[0]['run'])
        self.assertLess(steps.index(preparation[0]), next(i for i, s in enumerate(steps)
                        if 'verify-deployed-services.py verify' in s.get('run', '')))

    def test_scanner_preparation_reuses_local_image(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for command, source in {
                'session-manager-plugin': '#!/bin/sh\necho plugin-version\n',
                'docker': '#!/bin/sh\nif [ "$1" = image ]; then test -f "$TEST_IMAGE"; else echo pull >> "$TEST_PULLS"; touch "$TEST_IMAGE"; fi\n'
            }.items():
                script = directory / command
                script.write_text(source)
                script.chmod(0o755)
            env = {**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                   'TEST_IMAGE': str(directory / 'image'), 'TEST_PULLS': str(directory / 'pulls')}
            for _ in range(2):
                result = subprocess.run(['bash', str(ROOT / 'scripts/cd/prepare-scanner-tools.sh')],
                                        env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((directory / 'pulls').read_text().splitlines(), ['pull'])



if __name__ == '__main__':
    unittest.main()
