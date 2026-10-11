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

    def test_parallel_images_finish_before_one_stack_deployment(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        self.assertIn('terraform', jobs['setup-host']['needs'])
        self.assertIn('build-images', jobs['setup-host']['needs'])
        self.assertEqual({s['name'] for s in jobs['build-images']['strategy']['matrix']['service']},
                         {'service-registry', 'transfer-service', 'integrity-service', 'user-access-service', 'api-gateway'})
        self.assertGreater(jobs['build-images']['strategy']['max-parallel'], 1)
        self.assertEqual(set(jobs['deploy-stack']['needs']), {'setup-host', 'build-images'})
        self.assertIn('deploy-stack', jobs['verify-services']['needs'])
        build = yaml.safe_load((ROOT / '.github/workflows/build-service.yml').read_text())
        self.assertFalse(any('ssm' in s.get('run', '') or 'ansible' in s.get('run', '')
                             for s in build['jobs']['build']['steps']))
        self.assertEqual(sum('docker-stack-ssm.py deploy' in s.get('run', '')
                             for s in jobs['deploy-stack']['steps']), 1)
        visiting, visited = set(), set()
        def visit(name):
            self.assertNotIn(name, visiting)
            if name in visited:
                return
            visiting.add(name)
            needs = jobs[name].get('needs', [])
            for dependency in [needs] if isinstance(needs, str) else needs:
                self.assertIn(dependency, jobs)
                visit(dependency)
            visiting.remove(name)
            visited.add(name)
        for name in jobs:
            visit(name)

    def test_managed_oracle_mode_and_supported_image_reach_deployment(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        runtime = next(s for s in jobs['setup-host']['steps'] if s.get('id') == 'runtime')
        self.assertIn('load-runtime-config.py deployment', runtime['run'])
        staging = next(s for s in jobs['setup-host']['steps'] if 'stage-user-access.yml' in s.get('run', ''))
        self.assertIn("MANAGED_USER_ACCESS_ORACLE != 'true'", staging['if'])
        deploy = next(s for s in jobs['deploy-stack']['steps'] if 'docker-stack-ssm.py deploy' in s.get('run', ''))
        self.assertIn('managed_user_access_oracle', deploy['env']['MANAGED_USER_ACCESS_ORACLE'])
        self.assertEqual(jobs['quality_gates']['services']['oracle']['image'],
                         'gvenzl/oracle-free:23.26.3-slim-faststart')

    def test_host_and_scanner_tools_are_prepared_once(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
        steps = [step for job in jobs.values() for step in job.get('steps', [])]
        self.assertEqual(sum('wait-for-ssm.sh' in s.get('run', '') for s in steps), 1)
        self.assertEqual(sum('stage-integrity.yml' in s.get('run', '') and '--syntax-check' not in s['run']
                             for s in steps), 1)
        self.assertFalse(any('ansible' in s.get('run', '') for s in jobs['deploy-stack']['steps']))
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
