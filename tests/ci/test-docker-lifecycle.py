#!/usr/bin/env python3
"""Run the Docker lifecycle with a simulated Docker CLI and isolated host files."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
from urllib.error import HTTPError
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/cd' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


remote = module('docker_remote', 'docker-service-remote.py')
verification = module('verification', 'verify-deployed-services.py')


class DockerLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.running = None
        self.images = set()
        self.calls = []
        self.generation = 0
        self.configuration = None
        self.state = {'SERVICE_NAME': 'transfer-service', 'SERVICE_PORT': '8081',
                      'SERVICE_IMAGE': 'registry/mmiv:transfer-service-first',
                      'ECR_REGISTRY': 'registry', 'AWS_REGION': 'ap-southeast-1',
                      'MMIV_BUCKET_NAME': 'test'}
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
            if self.running:
                result.stdout = json.dumps([self.running])
            else:
                result.returncode = 1
                result.stderr = 'No such object'
        elif args[:3] == ('docker', 'image', 'inspect'):
            result.returncode = int(args[3] not in self.images)
        elif args[:2] == ('docker', 'pull'):
            self.images.add(args[2])
        elif args[:3] == ('aws', 'ecr', 'get-login-password'):
            result.stdout = 'test-password'
        elif args[:2] == ('docker', 'compose'):
            config = json.loads(Path(args[args.index('-f') + 1]).read_text())
            if 'config' in args:
                result.stdout = json.dumps(config)
            else:
                if config != self.configuration or '--force-recreate' in args:
                    self.generation += 1
                    self.configuration = config
                name = next(iter(config['services']))
                image = config['services'][name]['image']
                self.running = {'Id': str(self.generation),
                                'NetworkSettings': {'Networks': {'mmiv-backend': {'IPAddress': '172.20.0.3'}}},
                                'Config': {
                    'Image': image, 'Labels': {'com.docker.compose.project': 'mmiv-' + name}}}
        if check and result.returncode:
            raise subprocess.CalledProcessError(result.returncode, args)
        return result

    def test_repeat_keeps_container_and_rollback_record(self):
        first = remote.run('deploy', dict(self.state))
        identity = self.running['Id']
        self.assertTrue(first['DEPLOYMENT_CHANGED'])
        self.assertFalse(first['ROLLBACK_AVAILABLE'])
        second = remote.run('deploy', dict(self.state))
        self.assertFalse(second['DEPLOYMENT_CHANGED'])
        self.assertEqual(self.running['Id'], identity)
        self.assertEqual(sum(c[:2] == ('docker', 'pull') for c in self.calls), 1)
        self.assertFalse((self.root / 'var/lib/mmiv/transfer-service.previous-image').exists())

    def test_update_saves_previous_and_repeat_does_not_overwrite_it(self):
        remote.run('deploy', dict(self.state))
        original = self.state['SERVICE_IMAGE']
        self.state['SERVICE_IMAGE'] = 'registry/mmiv:transfer-service-second'
        update = remote.run('deploy', dict(self.state))
        self.assertTrue(update['ROLLBACK_AVAILABLE'])
        remote.run('deploy', dict(self.state))
        previous = self.root / 'var/lib/mmiv/transfer-service.previous-image'
        self.assertEqual(previous.read_text().strip(), original)
        restored = remote.run('rollback', dict(update))
        self.assertTrue(restored['ROLLED_BACK'])
        self.assertEqual(self.running['Config']['Image'], original)

    def test_failed_health_restores_previous_image(self):
        remote.run('deploy', dict(self.state))
        original = self.state['SERVICE_IMAGE']
        self.state['SERVICE_IMAGE'] = 'registry/mmiv:transfer-service-bad'
        with patch.object(remote, 'healthy', side_effect=[RuntimeError('unhealthy'), None]):
            with self.assertRaises(RuntimeError):
                remote.run('deploy', dict(self.state))
        self.assertEqual(self.running['Config']['Image'], original)

    def test_failed_registration_restores_previous_image(self):
        remote.run('deploy', dict(self.state))
        original = self.state['SERVICE_IMAGE']
        self.state['SERVICE_IMAGE'] = 'registry/mmiv:transfer-service-unregistered'
        with patch.object(remote, 'registered', side_effect=RuntimeError('not registered')):
            with self.assertRaises(RuntimeError):
                remote.run('deploy', dict(self.state))
        self.assertEqual(self.running['Config']['Image'], original)

    def test_failed_pull_removes_staged_oracle_secrets(self):
        self.state.update(SERVICE_NAME='integrity-service', SERVICE_PORT='8082', ORACLE_CONFIG_STAGED=True)
        staged = self.root / 'etc/mmiv/next-integrity.env'
        staged.parent.mkdir(parents=True)
        staged.write_text('SECRET=private\n')
        original = self.command
        def fail_pull(*args, **kwargs):
            if args[:2] == ('docker', 'pull'):
                raise subprocess.CalledProcessError(1, args)
            return original(*args, **kwargs)
        with patch.object(remote, 'command', side_effect=fail_pull):
            with self.assertRaises(subprocess.CalledProcessError):
                remote.run('deploy', dict(self.state))
        self.assertFalse(staged.exists())

    def test_rejects_foreign_compose_ownership(self):
        remote.run('deploy', dict(self.state))
        self.running['Config']['Labels']['com.docker.compose.project'] = 'other'
        with self.assertRaises(RuntimeError):
            remote.run('deploy', dict(self.state))

    def test_integrity_env_changes_recreate_only_when_needed(self):
        self.state.update(SERVICE_NAME='integrity-service', SERVICE_PORT='8082',
                          SERVICE_IMAGE='registry/mmiv:integrity-service-first', ORACLE_CONFIG_STAGED=True)
        staged = self.root / 'etc/mmiv/next-integrity.env'
        staged.parent.mkdir(parents=True)
        staged.write_text('SPRING_PROFILES_ACTIVE=oracle\nSETTING=first\n')
        remote.run('deploy', dict(self.state))
        first = self.running['Id']
        staged.write_text('SPRING_PROFILES_ACTIVE=oracle\nSETTING=first\n')
        repeated = remote.run('deploy', dict(self.state))
        self.assertEqual(self.running['Id'], first)
        self.assertFalse(repeated['DEPLOYMENT_CHANGED'])
        staged.write_text('SPRING_PROFILES_ACTIVE=oracle\nSETTING=second\n')
        update = remote.run('deploy', dict(self.state))
        self.assertNotEqual(self.running['Id'], first)
        self.assertTrue(update['DEPLOYMENT_CHANGED'])
        remote.run('rollback', dict(update))
        self.assertIn('SETTING=first', (self.root / 'etc/mmiv/integrity.env').read_text())


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


class SSMResultPollingTest(unittest.TestCase):
    def test_fast_polling_handles_delayed_invocation_visibility(self):
        ssm = verification.docker_ssm
        state = {'SERVICE_NAME': 'transfer-service', 'INSTANCE_ID': 'i-test'}
        results = [subprocess.CompletedProcess([], 0, 'command-id', ''),
                   subprocess.CompletedProcess([], 1, '', 'InvocationDoesNotExist'),
                   subprocess.CompletedProcess([], 0, '{"Status":"InProgress"}', ''),
                   subprocess.CompletedProcess([], 0, json.dumps({'Status': 'Success', 'ResponseCode': 0,
                       'StandardOutputContent': 'MMIV_DEPLOYMENT_STATE=' + json.dumps(state)}), '')]
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ssm, 'aws', side_effect=results), \
                patch.object(ssm.time, 'sleep') as sleep:
            original = ssm.Path
            def path(value):
                return Path(temporary) / value if str(value).startswith('.ci/') else original(value)
            with patch.object(ssm, 'Path', side_effect=path):
                self.assertEqual(ssm.lifecycle('deploy', state), state)
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 1])


class VerificationBarrierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.deployments = [{'SERVICE_NAME': n, 'SERVICE_PORT': p, 'INSTANCE_ID': 'i-test',
                             'ROLLED_BACK': False, 'DEPLOYMENT_CHANGED': True, 'ROLLBACK_AVAILABLE': True}
                            for n, p in [('service-registry', '8761'), ('transfer-service', '8081'),
                                         ('integrity-service', '8082'), ('api-gateway', '8080')]]

    def test_incomplete_barrier_runs_no_scans(self):
        with patch.object(verification.subprocess, 'Popen') as launch:
            with self.assertRaises(ValueError):
                verification.verify(self.deployments[:-1], self.directory)
            launch.assert_not_called()

    def test_failed_scan_does_not_restore_while_other_scans_run(self):
        names = []
        def launch(*args, **kwargs):
            names.append(kwargs['env']['DAST_SERVICE_NAME'])
            return SimpleNamespace(stdout=['test scanner output\n'], wait=lambda: 1 if len(names) == 1 else 0)
        with patch.object(verification.subprocess, 'Popen', side_effect=launch), \
                patch.object(verification.docker_ssm, 'lifecycle') as restore:
            self.assertEqual(verification.verify(self.deployments, self.directory), 1)
            self.assertEqual(set(names), {s['SERVICE_NAME'] for s in self.deployments})
            self.assertEqual(names[-1], 'api-gateway')
            restore.assert_not_called()
        self.assertTrue((self.directory / 'verification-summary.json').exists())

    def test_restore_only_changed_existing_deployments(self):
        self.deployments[0]['DEPLOYMENT_CHANGED'] = False
        self.deployments[1]['ROLLBACK_AVAILABLE'] = False
        calls = []
        def restore(action, state):
            calls.append(state['SERVICE_NAME'])
            return {**state, 'ROLLED_BACK': True}
        with patch.object(verification.docker_ssm, 'lifecycle', side_effect=restore):
            self.assertEqual(verification.rollback(self.deployments, self.directory, self.directory), 0)
        self.assertEqual(calls, ['api-gateway', 'integrity-service'])


if __name__ == '__main__':
    unittest.main()
