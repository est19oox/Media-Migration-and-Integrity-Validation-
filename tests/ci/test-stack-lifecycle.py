#!/usr/bin/env python3
"""Simulate one Compose stack, release recovery and SSM result polling."""
from contextlib import redirect_stdout, redirect_stderr
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml
from types import SimpleNamespace
from urllib.error import HTTPError
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/cd' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result
remote = module('stack_remote', 'docker-stack-remote.py')
verification = module('verification', 'verify-deployed-services.py')
ssm = verification.docker_ssm

class StackLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.containers, self.configs, self.images, self.calls = {}, {}, set(), []
        self.generation = 0
        self.volumes = set()
        self.real_subprocess = subprocess.run
        self.state = {'INSTANCE_ID': 'i-test', 'GITHUB_SHA': 'first', 'RELEASE_ID': 'first-1',
                      'ECR_REGISTRY': 'registry', 'AWS_REGION': 'ap-southeast-1',
                      'MMIV_BUCKET_NAME': 'test', 'MMIV_LOG_QUEUE_URL': 'https://sqs.example/queue', 'ORACLE_CONFIG_STAGED': False,
                      'services': {n: {'image': 'registry/mmiv:' + n + '-first', 'port': p}
                                   for n, p in ssm.PORTS.items() if n in
                                   ('service-registry', 'transfer-service', 'integrity-service', 'api-gateway')}}
        self.addCleanup(patch.stopall)
        patch.object(remote, 'Path', side_effect=lambda path: self.root / str(path).lstrip('/')).start()
        patch.object(remote, 'command', side_effect=self.command).start()
        patch.object(remote, 'healthy').start()
        patch.object(remote, 'registered').start()
        patch.object(remote.subprocess, 'run', return_value=SimpleNamespace(returncode=0)).start()

    def command(self, *args, check=True, timeout=120):
        self.calls.append(args)
        result = subprocess.CompletedProcess(args, 0, '', '')
        if args[:2] == ('docker', 'inspect'):
            if args[2] in self.containers:
                result.stdout = json.dumps([self.containers[args[2]]])
            else:
                result.returncode, result.stderr = 1, 'No such object'
        elif args[:3] == ('docker', 'volume', 'inspect'):
            result.returncode = int(args[3] not in self.volumes)
            if result.returncode:
                result.stderr = 'Error response from daemon: get mmiv-oracle-data: no such volume'
        elif args[:3] == ('docker', 'volume', 'ls'):
            result.stdout = '\n'.join(sorted(self.volumes))
        elif args[:3] == ('docker', 'volume', 'create'):
            self.volumes.add(args[3])
        elif args[0] == 'openssl':
            return self.real_subprocess(args, check=check, capture_output=True, text=True, timeout=timeout)
        elif args[:3] == ('docker', 'image', 'inspect'):
            result.returncode = int(args[3] not in self.images)
        elif args[:2] == ('docker', 'pull'):
            self.images.add(args[2])
        elif args[:2] == ('docker', 'rm'):
            self.containers.pop(args[-1], None)
            self.configs.pop(args[-1], None)
        elif args[:3] == ('aws', 'ecr', 'get-login-password'):
            result.stdout = 'private-password'
        elif args[:2] == ('docker', 'compose'):
            config = json.loads(Path(args[args.index('-f') + 1]).read_text())
            if 'config' in args:
                result.stdout = json.dumps(config)
            else:
                names = [name for name in args[args.index('up') + 1:] if name in config['services']]
                processed = set()
                def launch(name):
                    if name in processed:
                        return
                    service = config['services'][name]
                    if '--no-deps' not in args:
                        for dependency in service.get('depends_on', {}):
                            launch(dependency)
                            if dependency in self.state['services']:
                                remote.healthy(self.state['services'][dependency]['port'])
                    processed.add(name)
                    if service != self.configs.get(name) or '--force-recreate' in args or name not in self.containers:
                        self.generation += 1
                        self.configs[name] = service
                        self.containers[name] = {'Id': str(self.generation),
                            'Config': {'Image': service['image'], 'Labels': {'com.docker.compose.project': 'mmiv-stack'}},
                            'NetworkSettings': {'Networks': {'mmiv-backend': {'IPAddress': '172.20.0.' + str(self.generation)}}}}
                for name in names:
                    launch(name)
        if check and result.returncode:
            raise subprocess.CalledProcessError(result.returncode, args)
        return result

    def update(self, names=None, release='second-1'):
        self.state['RELEASE_ID'] = release
        for name in names or self.state['services']:
            self.state['services'][name]['image'] = 'registry/mmiv:' + name + '-second'

    def stage_access(self, config_id='a' * 64):
        self.state['services'].update({n: {'image': 'registry/mmiv:' + n + '-first', 'port': ssm.PORTS[n]}
                                      for n in ('user-access-service',)})
        bundle = self.root / 'etc/mmiv/user-access/versions' / config_id
        bundle.mkdir(parents=True, exist_ok=True)
        for name in ('environment.env', 'private.pem', 'public.pem'):
            (bundle / name).write_text('private test configuration')
        pointer = self.root / ('etc/mmiv/next-user-access-' + self.state['RELEASE_ID'] + '.json')
        pointer.write_text(json.dumps({'config_id': config_id}))

    def managed(self):
        self.state['MANAGED_USER_ACCESS_ORACLE'] = True
        self.state['services']['user-access-service'] = {
            'image': 'registry/mmiv:user-access-service-first', 'port': '8083'}

    def test_managed_oracle_reuses_credentials_keys_and_database_on_update_and_rollback(self):
        self.managed()
        first = remote.run('deploy', dict(self.state))
        self.assertTrue(first['READY'])
        secrets_dir = self.root / 'etc/mmiv/managed-oracle'
        originals = {p.name: p.read_bytes() for p in secrets_dir.iterdir()}
        oracle_id = self.containers['oracle']['Id']
        launches = [c for c in self.calls if c[:2] == ('docker', 'compose') and 'up' in c]
        self.assertEqual(len(launches), 1)
        self.assertNotIn('--no-deps', launches[0])
        self.assertNotIn('ports', self.configs['oracle'])
        self.assertEqual(self.configs['oracle']['mem_limit'], '3g')
        self.assertEqual(self.configs['oracle']['memswap_limit'], '3g')
        self.assertEqual(self.configs['user-access-service']['mem_limit'], '512m')
        self.assertEqual(self.configs['user-access-service']['memswap_limit'], '512m')
        self.assertIn('-Xmx256m', self.configs['user-access-service']['environment']['JAVA_TOOL_OPTIONS'])
        self.assertEqual(self.configs['user-access-service']['environment']['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'], '4')
        self.assertNotIn('mem_limit', self.configs['integrity-service'])
        self.assertEqual(self.configs['oracle']['image'], 'gvenzl/oracle-free:23.26.3-slim-faststart')
        self.assertEqual(self.configs['oracle']['logging'],
                         {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}})
        self.assertEqual(self.configs['oracle']['healthcheck']['start_period'], '180s')
        self.assertEqual(self.volumes, {remote.ORACLE_VOLUME})
        environment = (self.root / self.configs['user-access-service']['env_file'][0]['path'].lstrip('/')).read_text()
        self.assertIn('ORACLE_JDBC_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1', environment)
        self.assertIn('ORACLE_USER=USER_ACCESS', environment)
        self.assertIn('JWT_PRIVATE_KEY_PATH=file:/run/mmiv-jwt/private.pem', environment)
        self.assertIn('JWT_PUBLIC_KEY_PATH=file:/run/mmiv-jwt/public.pem', environment)
        self.assertNotIn('oracle', self.configs['integrity-service'].get('depends_on', {}))
        self.assertEqual((self.root / 'etc/mmiv/integrity.env').read_text(),
                         '# Embedded H2 mode: no Oracle secrets required.\n')
        self.assertTrue(all((secrets_dir / n).stat().st_mode & 0o777 == 0o600
                            for n in ('credentials.json', 'private.pem', 'public.pem', 'oracle.env')))
        state_text = json.dumps(first)
        compose_text = (self.root / 'opt/mmiv/stack/compose.yaml').read_text()
        self.assertNotIn(json.loads((secrets_dir / 'credentials.json').read_text())['user_access'], state_text + compose_text)
        self.update(['user-access-service'])
        second = remote.run('deploy', dict(self.state))
        self.assertTrue(second['READY'])
        remote.run('rollback', second)
        self.assertEqual(self.containers['oracle']['Id'], oracle_id)
        self.assertEqual(originals, {p.name: p.read_bytes() for p in secrets_dir.iterdir()})
        self.assertEqual(first['USER_ACCESS_CONFIG_ID'], second['USER_ACCESS_CONFIG_ID'])

    def test_storage_hook_changes_reconcile_oracle_in_single_forward_deployment(self):
        self.managed()
        self.assertTrue(remote.run('deploy', dict(self.state))['READY'])
        oracle_id = self.containers['oracle']['Id']
        self.calls.clear()
        self.state['RELEASE_ID'] = 'storage-hook-update'
        with patch.object(remote, 'ORACLE_STARTUP', remote.ORACLE_STARTUP + '\n# revised hook\n'):
            self.assertTrue(remote.run('deploy', dict(self.state))['READY'])
        self.assertNotEqual(self.containers['oracle']['Id'], oracle_id)
        self.assertEqual(self.volumes, {remote.ORACLE_VOLUME})
        self.assertEqual(sum(c[:2] == ('docker', 'compose') and 'up' in c for c in self.calls), 1)

    def test_startup_login_failure_does_not_mark_database_ready(self):
        directory = self.root / 'hook-test'
        directory.mkdir()
        executable = directory / 'sqlplus'
        executable.write_text('#!/bin/sh\ncat >"$SQL_CAPTURE"\nif [ "$3" = /nolog ]; then exit "$TEST_LOGIN_EXIT"; fi\nexit "$TEST_SCHEMA_EXIT"\n')
        executable.chmod(0o755)
        marker = directory / 'ready'
        hook = directory / 'startup.sh'
        hook.write_text(remote.ORACLE_STARTUP.replace('/opt/oracle/oradata/mmiv-app-user-ready', str(marker)))
        env = {**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
               'APP_USER_PASSWORD': 'test-password', 'TEST_LOGIN_EXIT': '1',
               'TEST_SCHEMA_EXIT': '0', 'SQL_CAPTURE': str(directory / 'captured.sql')}
        for exit_code in ('1', '0'):
            env['TEST_LOGIN_EXIT'] = exit_code
            result = self.real_subprocess(['bash', '-c', 'set -euo pipefail; source "$1"', 'bash', str(hook)],
                                          env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode == 0, exit_code == '0')
            self.assertEqual(marker.exists(), exit_code == '0')
            self.assertNotIn('test-password', result.stdout + result.stderr)
        env['TEST_SCHEMA_EXIT'] = '1'
        result = self.real_subprocess(['bash', '-c', 'set -euo pipefail; source "$1"', 'bash', str(hook)],
                                      env=env, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())
        self.assertIn(remote.ORACLE_STORAGE_SQL.strip(), (directory / 'captured.sql').read_text())
        self.assertNotIn('test-password', result.stdout + result.stderr)

    def test_switch_to_managed_database_retains_existing_signing_key(self):
        self.stage_access()
        bundle = self.root / 'etc/mmiv/user-access/versions' / ('a' * 64)
        private = self.real_subprocess(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'],
                                      check=True, capture_output=True, text=True).stdout
        public = self.real_subprocess(['openssl', 'pkey', '-pubout'], input=private,
                                     check=True, capture_output=True, text=True).stdout
        (bundle / 'private.pem').write_text(private)
        (bundle / 'public.pem').write_text(public)
        self.assertTrue(remote.run('deploy', dict(self.state))['READY'])
        self.managed()
        self.state['RELEASE_ID'] = 'managed-2'
        self.assertTrue(remote.run('deploy', dict(self.state))['READY'])
        self.assertEqual((self.root / 'etc/mmiv/managed-oracle/private.pem').read_text(), private)
        self.assertEqual((self.root / 'etc/mmiv/managed-oracle/public.pem').read_text(), public)

    def test_managed_access_preserves_existing_integrity_environment(self):
        self.managed()
        environment = self.root / 'etc/mmiv/integrity.env'
        environment.parent.mkdir(parents=True)
        environment.write_text('SPRING_PROFILES_ACTIVE=oracle\nEXISTING_INTEGRITY_CONFIG=keep\n')
        first = remote.run('deploy', dict(self.state))
        self.assertTrue(first['READY'])
        self.assertEqual(environment.read_text(), 'SPRING_PROFILES_ACTIVE=oracle\nEXISTING_INTEGRITY_CONFIG=keep\n')
        self.assertFalse(first['changes']['integrity-service']['previous'])

    def test_first_managed_release_failure_keeps_oracle_and_volume(self):
        self.managed()
        with patch.object(remote, 'registered', side_effect=RuntimeError('not registered')):
            failed = remote.run('deploy', dict(self.state))
        self.assertTrue(failed['ROLLED_BACK'])
        self.assertEqual(set(self.containers), {'oracle'})
        self.assertEqual(self.volumes, {remote.ORACLE_VOLUME})
        self.state['RELEASE_ID'] = 'retry-2'
        self.assertTrue(remote.run('deploy', dict(self.state))['READY'])

    def test_missing_volume_uses_listing_and_reuses_credentials_on_retry(self):
        self.managed()
        remote.managed_access(dict(self.state))
        credentials = self.root / 'etc/mmiv/managed-oracle/credentials.json'
        original = credentials.read_bytes()
        self.assertIn(('docker', 'volume', 'ls', '--format', '{{.Name}}'), self.calls)
        self.assertEqual(self.volumes, {remote.ORACLE_VOLUME})
        self.calls.clear()
        remote.managed_access(dict(self.state))
        self.assertEqual(credentials.read_bytes(), original)
        self.assertFalse(any(c[:3] == ('docker', 'volume', 'create') for c in self.calls))

    def test_uninspectable_existing_volume_is_not_treated_as_absent(self):
        self.managed()
        self.volumes.add(remote.ORACLE_VOLUME)
        def command(*args, **kwargs):
            if args[:3] == ('docker', 'volume', 'inspect'):
                return subprocess.CompletedProcess(args, 1, '', 'unrecognized error')
            return self.command(*args, **kwargs)
        with patch.object(remote, 'command', side_effect=command):
            with self.assertRaisesRegex(RuntimeError, 'Could not inspect Oracle volume'):
                remote.managed_access(dict(self.state))
        self.assertFalse((self.root / 'etc/mmiv/managed-oracle/credentials.json').exists())
        self.assertFalse(any(c[:3] == ('docker', 'volume', 'create') for c in self.calls))

    def test_daemon_or_volume_listing_failure_creates_no_credentials_or_volume(self):
        self.managed()
        for failed in [('docker', 'info'), ('docker', 'volume', 'ls')]:
            def command(*args, **kwargs):
                if args[:len(failed)] == failed:
                    raise subprocess.CalledProcessError(1, args)
                return self.command(*args, **kwargs)
            with patch.object(remote, 'command', side_effect=command):
                with self.assertRaises(subprocess.CalledProcessError):
                    remote.managed_access(dict(self.state))
            self.assertFalse((self.root / 'etc/mmiv/managed-oracle/credentials.json').exists())
            self.assertFalse(self.volumes)

    def test_existing_database_without_credentials_fails_without_resetting_it(self):
        self.managed()
        self.volumes.add(remote.ORACLE_VOLUME)
        with self.assertRaisesRegex(RuntimeError, 'restore the host secret backup'):
            remote.run('deploy', dict(self.state))
        self.assertEqual(self.volumes, {remote.ORACLE_VOLUME})
        self.assertFalse(any('up' in c or c[:3] == ('docker', 'volume', 'create') for c in self.calls))

    def test_existing_database_with_lost_signing_key_refuses_silent_rotation(self):
        self.managed()
        remote.run('deploy', dict(self.state))
        (self.root / 'etc/mmiv/managed-oracle/private.pem').unlink()
        self.state['RELEASE_ID'] = 'retry-2'
        with self.assertRaisesRegex(RuntimeError, 'Signing key is missing'):
            remote.run('deploy', dict(self.state))

    def test_five_services_access_keys_and_transfer_outbox(self):
        self.stage_access()
        result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['READY'])
        launches = [c for c in self.calls if c[:2] == ('docker', 'compose') and 'up' in c]
        self.assertEqual(len(launches), 1)
        self.assertEqual(set(self.configs['api-gateway']['depends_on']), set(self.state['services']) - {'api-gateway'})
        service = self.configs['user-access-service']
        keys = [v for v in service['volumes'] if v['target'].startswith('/run/mmiv-jwt')]
        self.assertEqual(len(keys), 2)
        self.assertTrue(all(v['read_only'] for v in keys))
        self.assertEqual(self.configs['transfer-service']['environment']['MMIV_LOG_QUEUE_URL'], self.state['MMIV_LOG_QUEUE_URL'])

    def test_same_bundle_is_a_noop_and_rotation_rolls_back(self):
        self.stage_access()
        remote.run('deploy', dict(self.state))
        original = self.containers['user-access-service']['Id']
        self.state['RELEASE_ID'] = 'first-2'
        self.stage_access()
        result = remote.run('deploy', dict(self.state))
        self.assertFalse(result['changes']['user-access-service']['changed'])
        self.assertEqual(original, self.containers['user-access-service']['Id'])
        self.state['RELEASE_ID'] = 'first-3'
        self.stage_access('b' * 64)
        result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['changes']['user-access-service']['changed'])
        self.assertIn('b' * 64, self.configs['user-access-service']['env_file'][0]['path'])
        remote.run('rollback', dict(self.state))
        self.assertIn('a' * 64, self.configs['user-access-service']['env_file'][0]['path'])
        self.assertTrue((self.root / 'etc/mmiv/user-access/versions' / ('b' * 64) / 'private.pem').exists())

    def test_incomplete_access_bundle_changes_no_containers(self):
        self.stage_access()
        (self.root / 'etc/mmiv/user-access/versions' / ('a' * 64) / 'public.pem').unlink()
        with self.assertRaises(RuntimeError): remote.run('deploy', dict(self.state))
        self.assertEqual(self.containers, {})

    def test_registry_first_backends_together_gateway_last(self):
        result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['READY'])
        launches = [c for c in self.calls if c[:2] == ('docker', 'compose') and 'up' in c]
        groups = [[n for n in c if n in self.state['services']] for c in launches]
        self.assertEqual(len(groups), 1)
        self.assertEqual(set(groups[0]), set(self.state['services']))
        self.assertEqual(set(self.configs['api-gateway']['depends_on']), set(self.state['services']) - {'api-gateway'})
        self.assertEqual(list(self.configs)[-1], 'api-gateway')
        self.assertTrue(all(c[c.index('-p') + 1] == 'mmiv-stack' for c in launches))
        self.assertEqual(sum(c[:3] == ('aws', 'ecr', 'get-login-password') for c in self.calls), 1)

    def test_repeat_preserves_container_ids_and_original_snapshot(self):
        remote.run('deploy', dict(self.state))
        identities = {n: c['Id'] for n, c in self.containers.items()}
        snapshot = self.root / 'opt/mmiv/stack/releases/first-1/rollback.json'
        original = snapshot.read_text()
        remote.run('deploy', dict(self.state))
        self.assertEqual(snapshot.read_text(), original)
        self.state['RELEASE_ID'] = 'first-2'
        repeated = remote.run('deploy', dict(self.state))
        self.assertFalse(any(change['changed'] for change in repeated['changes'].values()))
        self.assertEqual(identities, {n: c['Id'] for n, c in self.containers.items()})

    def test_partial_update_and_rollback_keep_unchanged_containers(self):
        remote.run('deploy', dict(self.state))
        identities = {n: c['Id'] for n, c in self.containers.items()}
        self.update(['transfer-service'])
        result = remote.run('deploy', dict(self.state))
        self.assertEqual([n for n, c in result['changes'].items() if c['changed']], ['transfer-service'])
        restored = remote.run('rollback', result)
        self.assertTrue(restored['ROLLED_BACK'])
        self.assertEqual(self.containers['transfer-service']['Config']['Image'], 'registry/mmiv:transfer-service-first')
        for name in identities.keys() - {'transfer-service'}:
            self.assertEqual(self.containers[name]['Id'], identities[name])

    def test_backend_failure_restores_release_before_gateway_update(self):
        remote.run('deploy', dict(self.state))
        gateway_id = self.containers['api-gateway']['Id']
        self.update()
        def health(port):
            if port == '8082' and self.containers['integrity-service']['Config']['Image'].endswith('-second'):
                raise RuntimeError('unhealthy')
        with patch.object(remote, 'healthy', side_effect=health):
            result = remote.run('deploy', dict(self.state))
        self.assertFalse(result['READY'])
        self.assertTrue(result['ROLLED_BACK'])
        self.assertEqual(result['failure_phase'], 'compose_startup')
        self.assertIn('integrity-service', result['container_states'])
        self.assertEqual(self.containers['api-gateway']['Id'], gateway_id)
        self.assertTrue(all(c['Config']['Image'].endswith('-first') for c in self.containers.values()))

    def test_first_failed_release_removes_new_containers(self):
        with patch.object(remote, 'registered', side_effect=RuntimeError('not registered')):
            result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['ROLLED_BACK'])
        self.assertFalse(self.containers)
        self.assertTrue(all(value == 'REMOVED_NEW_SERVICE' for value in result['recovery'].values()))

    def test_gateway_failure_restores_all_changed_images(self):
        remote.run('deploy', dict(self.state))
        self.update()
        def register(name, *args):
            if name == 'api-gateway' and self.containers[name]['Config']['Image'].endswith('-second'):
                raise RuntimeError('routes not ready')
        with patch.object(remote, 'registered', side_effect=register):
            result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['ROLLED_BACK'])
        self.assertTrue(all(c['Config']['Image'].endswith('-first') for c in self.containers.values()))
        self.assertEqual(list(result['recovery'])[0], 'api-gateway')

    def test_oracle_env_update_recreates_only_integrity_and_restores_secret(self):
        staged = self.root / ('etc/mmiv/next-integrity-' + self.state['RELEASE_ID'] + '.env')
        staged.parent.mkdir(parents=True)
        staged.write_text('SECRET=first\n')
        self.state['ORACLE_CONFIG_STAGED'] = True
        remote.run('deploy', dict(self.state))
        identities = {n: c['Id'] for n, c in self.containers.items()}
        self.state['RELEASE_ID'] = 'first-env-2'
        staged = self.root / 'etc/mmiv/next-integrity-first-env-2.env'
        staged.write_text('SECRET=second\n')
        result = remote.run('deploy', dict(self.state))
        self.assertNotEqual(self.containers['integrity-service']['Id'], identities['integrity-service'])
        for name in identities.keys() - {'integrity-service'}:
            self.assertEqual(self.containers[name]['Id'], identities[name])
        self.assertFalse(staged.exists())
        remote.run('rollback', result)
        self.assertEqual((self.root / 'etc/mmiv/integrity.env').read_text(), 'SECRET=first\n')

    def test_pull_failure_changes_no_runtime_and_cleans_staged_secret(self):
        remote.run('deploy', dict(self.state))
        identities = {n: c['Id'] for n, c in self.containers.items()}
        self.update()
        staged = self.root / ('etc/mmiv/next-integrity-' + self.state['RELEASE_ID'] + '.env')
        staged.write_text('SECRET=private\n')
        original = self.command
        def fail(*args, **kwargs):
            if args[:2] == ('docker', 'pull'):
                raise subprocess.CalledProcessError(1, args)
            return original(*args, **kwargs)
        with patch.object(remote, 'command', side_effect=fail):
            with self.assertRaises(subprocess.CalledProcessError):
                remote.run('deploy', dict(self.state))
        self.assertEqual(identities, {n: c['Id'] for n, c in self.containers.items()})
        self.assertFalse(staged.exists())

    def test_failed_recovery_can_resume_from_the_release_journal(self):
        remote.run('deploy', dict(self.state))
        self.update()
        def health(port):
            if port == '8080':
                raise RuntimeError('temporarily unavailable')
        with patch.object(remote, 'healthy', side_effect=health):
            failed = remote.run('deploy', dict(self.state))
        self.assertFalse(failed['READY'])
        self.assertIn('recovery_error', failed)
        recovered = remote.run('rollback', failed)
        self.assertTrue(recovered['ROLLED_BACK'])
        self.assertNotIn('recovery_error', recovered)
        self.assertTrue(all(c['Config']['Image'].endswith('-first') for c in self.containers.values()))

    def test_next_release_recovers_interrupted_update_before_capturing_baseline(self):
        remote.run('deploy', dict(self.state))
        self.update()
        remote.run('deploy', dict(self.state))
        current_path = self.root / 'opt/mmiv/stack/current-release.json'
        current = json.loads(current_path.read_text())
        current['READY'] = False
        current_path.write_text(json.dumps(current))
        self.state['RELEASE_ID'] = 'third-1'
        def health(port):
            if port == '8082' and self.containers['integrity-service']['Config']['Image'].endswith('-second'):
                raise RuntimeError('unhealthy')
        with patch.object(remote, 'healthy', side_effect=health):
            result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['ROLLED_BACK'])
        self.assertTrue(all(c['Config']['Image'].endswith('-first') for c in self.containers.values()))

    def test_migrates_earlier_service_projects(self):
        remote.run('deploy', dict(self.state))
        self.state['RELEASE_ID'] = 'migration-1'
        for name, container in self.containers.items():
            container['Config']['Labels']['com.docker.compose.project'] = 'mmiv-' + name
        result = remote.run('deploy', dict(self.state))
        self.assertTrue(result['READY'])
        self.assertTrue(all(c['Config']['Labels']['com.docker.compose.project'] == 'mmiv-stack'
                            for c in self.containers.values()))

    def test_rejects_foreign_compose_owner(self):
        remote.run('deploy', dict(self.state))
        self.state['RELEASE_ID'] = 'foreign-1'
        self.containers['transfer-service']['Config']['Labels']['com.docker.compose.project'] = 'other'
        with self.assertRaises(RuntimeError):
            remote.run('deploy', dict(self.state))

    def test_stale_rollback_cannot_replace_newer_release(self):
        old = remote.run('deploy', dict(self.state))
        self.update()
        remote.run('deploy', dict(self.state))
        with self.assertRaises(RuntimeError):
            remote.run('rollback', old)
        self.assertTrue(all(c['Config']['Image'].endswith('-second') for c in self.containers.values()))

class RemoteReadinessTest(unittest.TestCase):
    def test_gateway_retries_routes_until_transfer_is_discovered(self):
        calls = []
        attempts = 0
        def response(request, **kwargs):
            nonlocal attempts
            url = request.full_url if hasattr(request, 'full_url') else request
            calls.append(url)
            if '/eureka/apps/' in url:
                attempts += 1
                return io.BytesIO(json.dumps({'application': {'instance': {'status': 'UP'}}}).encode())
            if attempts == 1:
                raise HTTPError(url, 503, 'not discovered', {}, None)
            if url.endswith('/content'):
                raise HTTPError(url, 400, 'invalid id', {}, None)
            result = io.BytesIO(b'page')
            result.status = 200
            return result
        with patch.object(remote, 'urlopen', side_effect=response), patch.object(remote.time, 'sleep') as sleep:
            remote.registered('api-gateway')
            self.assertEqual(attempts, 2)
            sleep.assert_called_once_with(5)
        self.assertTrue(any('/files.html' in url for url in calls))
        self.assertTrue(any('/content' in url for url in calls))

    def test_backend_not_up_fails_after_retries(self):
        with patch.object(remote, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(
                b'{"application":{"instance":[{"status":"DOWN"}]}}')), \
                patch.object(remote.time, 'sleep') as sleep:
            with self.assertRaises(RuntimeError):
                remote.registered('transfer-service')
            self.assertEqual(sleep.call_count, 29)


    def test_stale_up_entry_does_not_satisfy_current_container_readiness(self):
        response = {'application': {'instance': [
            {'status': 'UP', 'ipAddr': '172.20.0.2', 'port': {'$': 8081}},
            {'status': 'DOWN', 'ipAddr': '172.20.0.3', 'port': {'$': 8081}}]}}
        with patch.object(remote, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(json.dumps(response).encode())), \
                patch.object(remote.time, 'sleep'):
            with self.assertRaises(RuntimeError):
                remote.registered('transfer-service', '172.20.0.3', '8081')
        response['application']['instance'][1]['status'] = 'UP'
        with patch.object(remote, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(json.dumps(response).encode())):
            remote.registered('transfer-service', '172.20.0.3', '8081')



class RunnerAndBarrierTest(unittest.TestCase):
    def state(self):
        return {'INSTANCE_ID': 'i-test', 'GITHUB_SHA': 'commit', 'RELEASE_ID': 'commit-1',
                'ECR_REGISTRY': 'registry', 'READY': True, 'ROLLED_BACK': False, 'COMMAND_STATUS': 'Success',
                'services': {n: {'image': 'registry/mmiv:' + n + '-commit', 'port': ssm.PORTS[n]}
                             for n in ssm.expected_services()}}

    def test_failure_snapshot_excludes_commands_credentials_and_health_logs(self):
        secret = 'private-password-value'
        error = subprocess.CalledProcessError(17, ['docker', 'compose', '--password', secret],
                                            output=secret, stderr=secret)
        def fail():
            raise error
        with self.assertRaises(subprocess.CalledProcessError):
            remote.stage('compose_startup', fail)
        container = {'Config': {'Env': [secret]}, 'State': {'Status': 'exited',
                     'ExitCode': 137, 'OOMKilled': True, 'Error': secret,
                     'Health': {'Status': 'unhealthy', 'Log': [secret]}}}
        with patch.object(remote, 'inspect', return_value=container):
            details = remote.failure_details(error, {'services': {'transfer-service': {}}})
        self.assertEqual(details['failure_phase'], 'compose_startup')
        self.assertEqual(details['failed_operation'], 'docker compose')
        self.assertEqual(details['command_exit_code'], 17)
        self.assertTrue(details['container_states']['transfer-service']['oom_killed'])
        self.assertNotIn(secret, json.dumps(details))
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            ssm.report_outcome('deploy', {**details, 'READY': False, 'ROLLED_BACK': True,
                                        'environment': {'password': secret}})
        self.assertIn('compose_startup', err.getvalue())
        self.assertIn('exit_code=17', err.getvalue())
        self.assertIn('transfer-service', err.getvalue())
        self.assertNotIn(secret, out.getvalue() + err.getvalue())

    def test_failure_detail_only_reports_known_safe_messages(self):
        with patch.object(remote, 'inspect', return_value=None):
            details = remote.failure_details(RuntimeError('password=private-value'), {})
            self.assertNotIn('private-value', json.dumps(details))
            safe = 'Container health did not become UP'
            details = remote.failure_details(RuntimeError(safe), {})
            self.assertEqual(details['error_detail'], safe)

    def test_incomplete_or_failed_release_starts_no_scans(self):
        for mode in ('missing', 'failed', 'restored'):
            state = self.state()
            if mode == 'missing': state['services'].pop('api-gateway')
            if mode == 'failed': state['READY'] = False
            if mode == 'restored': state['ROLLED_BACK'] = True
            with patch.object(verification.subprocess, 'Popen') as scan:
                with self.assertRaises(ValueError): verification.verify(state, Path('/unused'))
                scan.assert_not_called()

    def test_failed_scan_continues_other_scans_and_gateway_is_last(self):
        names = []
        def launch(*args, **kwargs):
            names.append(kwargs['env']['DAST_SERVICE_NAME'])
            return SimpleNamespace(stdout=['scanner output\n'], wait=lambda: int(len(names) == 1))
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', side_effect=launch), \
                patch.object(ssm, 'lifecycle') as restore:
            self.assertEqual(verification.verify(self.state(), Path(directory)), 1)
            self.assertEqual(names[-1], 'api-gateway')
            self.assertEqual(set(names), ssm.expected_services())
            restore.assert_not_called()

    def test_rollback_uses_one_ssm_command_and_skips_already_restored(self):
        state = self.state()
        with tempfile.TemporaryDirectory() as directory, patch.object(ssm, 'lifecycle',
                return_value={**state, 'ROLLED_BACK': True}) as lifecycle:
            path = Path(directory)
            self.assertEqual(verification.rollback(state, path, path), 0)
            lifecycle.assert_called_once_with('rollback', state)
            lifecycle.reset_mock()
            state['ROLLED_BACK'] = True
            self.assertEqual(verification.rollback(state, path, path), 0)
            lifecycle.assert_not_called()

    def test_unchanged_release_uses_no_rollback_command(self):
        state = self.state()
        state['changes'] = {name: {'changed': False} for name in state['services']}
        with tempfile.TemporaryDirectory() as directory, patch.object(ssm, 'lifecycle') as lifecycle:
            self.assertEqual(verification.rollback(state, Path(directory), Path(directory)), 0)
            lifecycle.assert_not_called()

    def test_build_workflow_image_records_feed_release_preparation(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/build-service.yml').read_text())
        record = next(step for step in workflow['jobs']['build']['steps']
                      if step['name'] == 'Record immutable image')['run']
        with tempfile.TemporaryDirectory() as directory:
            for name, item in self.state()['services'].items():
                result = subprocess.run(['bash', '-c', record], cwd=directory,
                    env={**os.environ, 'SERVICE_NAME': name, 'SERVICE_PORT': item['port'],
                         'SERVICE_IMAGE': item['image'], 'ECR_REGISTRY': 'registry', 'GITHUB_SHA': 'commit'},
                    capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
            state = ssm.prepare(Path(directory) / '.ci/images', 'i-test', 'commit', 'commit-1')
            self.assertEqual(set(state['services']), ssm.expected_services())

    def test_image_manifest_barrier_rejects_missing_or_stale_images(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            for name, item in self.state()['services'].items():
                (path / (name + '.json')).write_text(json.dumps({'SERVICE_NAME': name, 'SERVICE_PORT': item['port'],
                    'SERVICE_IMAGE': item['image'], 'GITHUB_SHA': 'commit', 'ECR_REGISTRY': 'registry'}))
            self.assertEqual(set(ssm.prepare(path, 'i-test', 'commit', 'commit-1')['services']), ssm.expected_services())
            (path / 'api-gateway.json').unlink()
            with self.assertRaises(ValueError): ssm.prepare(path, 'i-test', 'commit', 'commit-1')

    def test_ssm_eventual_visibility_and_failed_rollout_preserve_release_state(self):
        state = self.state()
        state.update(READY=False, ROLLED_BACK=True)
        results = [subprocess.CompletedProcess([], 0, 'command-id', ''),
                   subprocess.CompletedProcess([], 1, '', 'InvocationDoesNotExist'),
                   subprocess.CompletedProcess([], 0, '{"Status":"InProgress"}', ''),
                   subprocess.CompletedProcess([], 0, json.dumps({'Status': 'Failed', 'ResponseCode': 1,
                       'StandardOutputContent': 'MMIV_RELEASE_STATE=' + json.dumps(state)}), '')]
        with tempfile.TemporaryDirectory() as directory, patch.object(ssm, 'aws', side_effect=results), \
                patch.object(ssm.time, 'sleep') as sleep:
            original = ssm.Path
            with patch.object(ssm, 'Path', side_effect=lambda value:
                    Path(directory) / value if str(value).startswith('.ci/') else original(value)):
                result = ssm.lifecycle('deploy', state)
            self.assertTrue(result['ROLLED_BACK'])
            self.assertEqual(result['COMMAND_STATUS'], 'Failed')
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 1])

if __name__ == '__main__':
    unittest.main()
