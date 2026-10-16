#!/usr/bin/env python3
"""Exercise the actual Ansible role with Docker: no-op, update, rollback, failure."""
from pathlib import Path
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
import uuid
import yaml

ROOT = Path(__file__).resolve().parents[2]

class ComposeRuntimeTest(unittest.TestCase):
    def test_integrated_compose_assigns_browser_auth_to_gateway_only(self):
        expected = {'api-gateway', 'service-registry', 'transfer-service',
                    'discovery-service', 'user-access-service', 'reporting-service',
                    'integrity-service', 'rabbitmq', 'mongodb'}
        for filename in ('compose.yml', 'compose-scaled.yml'):
            with self.subTest(filename=filename):
                compose = yaml.safe_load((ROOT / 'infrastructure/service-registry' / filename).read_text())
                services = compose['services']
                self.assertEqual(set(services), expected)
                gateway = services['api-gateway']
                self.assertEqual(gateway['environment']['USER_ACCESS_JWK_SET_URI'],
                                 'http://user-access-service:8083/.well-known/jwks.json')
                self.assertIn('USER_ACCESS_JWT_ISSUER', gateway['environment'])
                self.assertIn('JWT_COOKIE_SECURE', gateway['environment'])
                transfer = services['transfer-service']['environment']
                self.assertNotIn('JWT_COOKIE_SECURE', transfer)
                self.assertNotIn('REPORTING_URL', transfer)
                self.assertIn('127.0.0.1:15672:15672',
                              [str(port) for port in services['rabbitmq'].get('ports', [])])
                for name, service in services.items():
                    for port in service.get('ports', []):
                        binding = str(port)
                        if name == 'api-gateway':
                            self.assertFalse(binding.startswith('127.0.0.1:'))
                        else:
                            self.assertTrue(binding.startswith('127.0.0.1:'), (name, binding))

    def test_rollout_repeat_and_rollback(self):
        name = 'mmiv-test-' + uuid.uuid4().hex[:10]
        images = [name + ':' + tag for tag in ['first', 'second', 'unhealthy']]
        network = name + '-network'
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'media').mkdir()
            (directory / 'state').mkdir()
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            subprocess.run(['docker', 'network', 'create', network], check=True, capture_output=True)
            try:
                for index, image in enumerate(images):
                    server = (
                        'from http.server import BaseHTTPRequestHandler,HTTPServer\n'
                        'class Handler(BaseHTTPRequestHandler):\n'
                        ' def do_GET(self):\n'
                        f'  self.send_response({404 if index == 2 else 200})\n'
                        '  self.send_header("Content-Type","application/json")\n'
                        '  self.end_headers()\n'
                        '  self.wfile.write(b\'{"status":"UP"}\')\n'
                        'HTTPServer(("0.0.0.0",8081),Handler).serve_forever()\n'
                    )
                    (directory / 'Dockerfile').write_text(
                        'FROM python:3.12-alpine\n' +
                        f'LABEL test.version="{index}"\n' +
                        'CMD ' + json.dumps(['python', '-c', server]) + '\n')
                    subprocess.run(['docker', 'build', '-q', '-t', image, str(directory)],
                                   check=True, capture_output=True)
                variables = {
                    'microservice_name': name,
                    'microservice_compose_project': name,
                    'microservice_compose_directory': str(directory / 'compose'),
                    'microservice_previous_image_file': str(directory / 'state/previous-image'),
                    'microservice_media_directory': str(directory / 'media'),
                    'microservice_host_port': port, 'microservice_container_port': 8081,
                    'microservice_image': images[0], 'ecr_registry': '',
                    'mmiv_docker_network': network,
                    'microservice_health_retries': 1, 'microservice_health_delay': 0,
                    'ansible_python_interpreter': sys.executable,
                }
                playbook = directory / 'playbook.yml'
                playbook.write_text(yaml.safe_dump([{
                    'hosts': 'localhost', 'gather_facts': False, 'become': False,
                    'roles': ['microservice'], 'vars': variables}]))
                environment = {**os.environ,
                    'ANSIBLE_ROLES_PATH': str(ROOT / 'infrastructure/ansible/roles'),
                    'ANSIBLE_LOCAL_TEMP': str(directory / 'ansible-tmp'),
                    'ANSIBLE_REMOTE_TEMP': str(directory / 'remote-tmp'),
                }
                environment.pop('GITHUB_OUTPUT', None)
                executable = shutil.which('ansible-playbook')
                self.assertIsNotNone(executable, 'ansible-playbook is required')
                def run(image, rollback=False, success=True):
                    print(f'Checking image={image}, rollback={rollback}, expected_success={success}', flush=True)
                    result = subprocess.run([executable, '-i', 'localhost,', '-c', 'local',
                        str(playbook), '-e', json.dumps({'microservice_image': image,
                            'microservice_rollback': rollback})],
                        capture_output=True, text=True, env=environment)
                    self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                    return result.stdout
                def container():
                    result = subprocess.run(['docker', 'inspect', name], check=True,
                                            capture_output=True, text=True)
                    return json.loads(result.stdout)[0]

                run(images[0])
                first_id = container()['Id']
                output = run(images[0])
                self.assertEqual(container()['Id'], first_id, 'Repeat deploy recreated the container')
                self.assertRegex(output, r'changed=0\s', 'Repeat deploy changed persistent state')
                run(images[1])
                self.assertNotEqual(container()['Id'], first_id)
                self.assertEqual(container()['Config']['Image'], images[1])
                run(images[1], rollback=True)
                self.assertEqual(container()['Config']['Image'], images[0])
                self.assertEqual(container()['HostConfig']['PortBindings']['8081/tcp'][0]['HostIp'], '127.0.0.1')
                run(images[2], success=False)
                self.assertEqual(container()['Config']['Image'], images[0], 'Failed health check did not restore previous image')

                # The former docker-run container is migrated once, then preserved.
                subprocess.run(['docker', 'rm', '-f', name], check=True, capture_output=True)
                subprocess.run(['docker', 'run', '-d', '--name', name, '--network', network,
                    '-p', f'127.0.0.1:{port}:8081', images[0]], check=True, capture_output=True)
                run(images[0])
                migrated = container()
                self.assertEqual(migrated['Config']['Labels']['com.docker.compose.project'], name)
                run(images[0])
                self.assertEqual(container()['Id'], migrated['Id'])

                # A manual image update can leave the on-disk Compose file stale.
                subprocess.run(['docker', 'rm', '-f', name], check=True, capture_output=True)
                subprocess.run(['docker', 'run', '-d', '--name', name, '--network', network,
                    '-p', f'127.0.0.1:{port}:8081', images[1]], check=True, capture_output=True)
                run(images[2], success=False)
                self.assertEqual(container()['Config']['Image'], images[1],
                                 'Rollback used stale Compose image instead of prior running image')
            finally:
                subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
                subprocess.run(['docker', 'network', 'rm', network], capture_output=True)
                subprocess.run(['docker', 'image', 'rm', *images], capture_output=True)

if __name__ == '__main__':
    unittest.main()
