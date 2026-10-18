#!/usr/bin/env python3
"""Validate rendered Compose networking and exercise EC2 adoption/plan guards."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import yaml
import importlib.util
import sys
from types import SimpleNamespace
from jinja2 import Environment, StrictUndefined

ROOT = Path(__file__).resolve().parents[2]
ROLE = ROOT / 'infrastructure/ansible/roles/microservice'

class DeploymentConfigurationTest(unittest.TestCase):
    def test_integrity_scrape_targets_have_distinct_replica_labels(self):
        monitoring = yaml.safe_load((ROOT / 'infrastructure/monitoring/prometheus.yml').read_text())
        targets = {job['job_name']: job['static_configs'][0] for job in monitoring['scrape_configs']}
        self.assertEqual(targets['integrity-service']['labels']['replica'], 'integrity-service')
        self.assertEqual(targets['integrity-service-2']['labels']['replica'], 'integrity-service-2')
        dashboard = json.loads((ROOT / 'infrastructure/monitoring/grafana/dashboards/integrity-validation.json').read_text())
        self.assertTrue(any('mmiv_integrity_validation_seconds_bucket' in panel['targets'][0]['expr']
                            for panel in dashboard['panels']))

    def test_every_eureka_client_registers_and_reports_health(self):
        for service in ['api-gateway', 'transfer-service', 'integrity-service',
                        'user-access-service', 'reporting-service', 'discovery-service']:
            config = yaml.safe_load(
                (ROOT / 'services' / service / 'src/main/resources/application.yml').read_text())
            client = config['eureka']['client']
            self.assertTrue(client['register-with-eureka'], service)
            self.assertTrue(client['fetch-registry'], service)
            self.assertTrue(client['healthcheck']['enabled'], service)
        discovery_application = (ROOT / 'services/discovery-service/src/main/java/mmiv/Application.java').read_text()
        self.assertIn('@EnableDiscoveryClient', discovery_application)

    def test_remote_stack_assigns_browser_auth_and_public_binding_to_gateway(self):
        if 'fcntl' not in sys.modules:
            sys.modules['fcntl'] = SimpleNamespace(LOCK_EX=1, LOCK_NB=2, LOCK_UN=8,
                                                   flock=lambda *_: None)
        spec = importlib.util.spec_from_file_location(
            'docker_stack_remote_contract', ROOT / 'scripts/cd/docker-stack-remote.py')
        remote = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(remote)
        names = ['service-registry', 'api-gateway', 'transfer-service', 'integrity-service',
                 'integrity-service-2', 'user-access-service', 'reporting-service', 'discovery-service']
        state = {'AWS_REGION': 'ap-southeast-1', 'MMIV_BUCKET_NAME': 'test',
                 'MMIV_LOG_QUEUE_URL': 'https://sqs.example/queue',
                 'USER_ACCESS_JWT_ISSUER': 'https://auth.mmiv.example',
                 'USER_ACCESS_CONFIG_ID': 'a' * 64,
                 'services': {name: {'image': 'example/' + name, 'port': port}
                              for name, port in zip(names, [8761, 8080, 8081, 8082, 9082, 8083, 8084, 8085])}}
        with patch.object(remote, 'managed_access', return_value=False):
            services = remote.config_for(state)['services']
        gateway = services['api-gateway']
        self.assertEqual(gateway['ports'], ['0.0.0.0:8080:8080'])
        self.assertEqual(gateway['environment']['JWT_COOKIE_SECURE'], 'false')
        self.assertIn('USER_ACCESS_JWK_SET_URI', gateway['environment'])
        for name in names:
            environment = services[name]['environment']
            self.assertNotIn('EUREKA_INSTANCE_INSTANCE_ID', environment)
            self.assertNotIn('EUREKA_INSTANCE_HOSTNAME', environment)
            self.assertNotIn('EUREKA_INSTANCE_PREFER_IP_ADDRESS', environment)
        self.assertNotIn('JWT_COOKIE_SECURE', services['transfer-service']['environment'])
        dependency_keys = {'METADATA_URL', 'USER_ACCESS_URL', 'TRANSFER_URL', 'REPORTING_URL'}
        expected_dependencies = {
            'service-registry': set(),
            'api-gateway': set(),
            'transfer-service': {'METADATA_URL', 'USER_ACCESS_URL'},
            'integrity-service': {'METADATA_URL'},
            'integrity-service-2': {'METADATA_URL'},
            'user-access-service': set(),
            'reporting-service': {'METADATA_URL', 'USER_ACCESS_URL'},
            'discovery-service': {'USER_ACCESS_URL', 'TRANSFER_URL', 'REPORTING_URL'}}
        for name, expected in expected_dependencies.items():
            self.assertEqual(dependency_keys & services[name]['environment'].keys(), expected, name)
        self.assertNotIn('USER_ACCESS_JWK_SET_URI', services['service-registry']['environment'])
        self.assertNotIn('USER_ACCESS_JWK_SET_URI', services['user-access-service']['environment'])
        for name in {'api-gateway', 'transfer-service', 'integrity-service',
                     'integrity-service-2', 'reporting-service', 'discovery-service'}:
            self.assertIn('USER_ACCESS_JWK_SET_URI', services[name]['environment'], name)
        for name in set(services) - {'api-gateway'}:
            for binding in services[name].get('ports', []):
                self.assertTrue(binding.startswith('127.0.0.1:'), name)
        with patch.object(remote, 'managed_access', return_value=False):
            tls_services = remote.config_for({**state, 'JWT_COOKIE_SECURE': 'true'})['services']
        self.assertEqual(tls_services['api-gateway']['environment']['JWT_COOKIE_SECURE'], 'true')
        with patch.object(remote, 'managed_access', return_value=False), self.assertRaises(ValueError):
            remote.config_for({**state, 'JWT_COOKIE_SECURE': 'yes'})

    def test_compose_configuration_for_each_service(self):
        environment = Environment(undefined=StrictUndefined)
        environment.filters['to_json'] = json.dumps
        defaults = yaml.safe_load((ROLE / 'defaults/main.yml').read_text())
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'compose.yaml'
            env_file = Path(temporary) / 'integrity.env'
            env_file.write_text('')
            for name, port in [('service-registry', 8761), ('api-gateway', 8080),
                               ('transfer-service', 8081), ('integrity-service', 8082)]:
                with self.subTest(service=name):
                    bind = environment.from_string(defaults['microservice_bind_address']).render(microservice_name=name)
                    path.write_text(environment.from_string((ROLE / 'templates/compose.yaml.j2').read_text()).render(
                        microservice_name=name, microservice_image='test:previous',
                        microservice_host_port=port, microservice_container_port=port,
                        microservice_bind_address=bind, mmiv_docker_network='mmiv-backend',
                        microservice_media_directory='/var/lib/mmiv/media',
                        microservice_integrity_env_file=str(env_file),
                        aws_region='ap-southeast-1', microservice_bucket='test',
                        microservice_eureka_url='http://service-registry:8761/eureka/'))
                    result = subprocess.run(['docker', 'compose', '-f', str(path), 'config',
                                             '--no-env-resolution', '--format', 'json'],
                                            capture_output=True, text=True, check=True)
                    config = json.loads(result.stdout)
                    service = config['services'][name]
                    self.assertEqual(service['image'], 'test:previous')
                    self.assertEqual(service['ports'][0]['host_ip'], bind)
                    self.assertEqual(service['ports'][0]['target'], port)
                    self.assertEqual(service['environment']['EUREKA_DEFAULT_ZONE'], 'http://service-registry:8761/eureka/')
                    self.assertEqual(config['networks']['backend']['name'], 'mmiv-backend')
                    self.assertTrue(config['networks']['backend']['external'])
                    self.assertTrue(service['volumes'][0]['read_only'])
                    self.assertEqual('env_file' in yaml.safe_load(path.read_text())['services'][name], name == 'integrity-service')

    def test_every_deployed_service_has_functional_configuration(self):
        config = json.loads((ROOT / 'tests/api/endpoints.json').read_text())
        workflow = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())
        services = ['service-registry', 'api-gateway', 'integrity-service'] + [s['name'] for s in
            workflow['jobs']['build-images']['strategy']['matrix']['service']]
        self.assertNotIn('/actuator/health',
                         [case['path'] for case in config.get('common', [])],
                         'Gateway health cannot prove a backend service is healthy')
        for service in services:
            self.assertIn(service, config['services'])
            self.assertTrue(config['services'][service], service)

    def test_ec2_adoption(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            executable = '''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['TEST_LOG'], 'a') as f:
    f.write(json.dumps([os.path.basename(sys.argv[0]), *sys.argv[1:]]) + '\\n')
if os.path.basename(sys.argv[0]) == 'terraform' and sys.argv[1:3] == ['state', 'list']:
    print(os.environ['TEST_STATE'])
    sys.exit(int(os.environ.get('TEST_STATE_EXIT', '0')))
if os.path.basename(sys.argv[0]) == 'aws':
    print(os.environ['TEST_INSTANCES'])
    sys.exit(int(os.environ.get('TEST_AWS_EXIT', '0')))
if sys.argv[1:3] == ['show', '-json']:
    print(json.dumps({'values': {'root_module': {'resources': [
        {'address': 'aws_instance.mmiv', 'values': {'id': 'i-existing'}}]}}}))
'''
            for name in ['terraform', 'aws']:
                file = directory / name
                file.write_text(executable)
                file.chmod(0o755)
            cases = [('', [], 0, 0, False), ('', ['i-existing'], 0, 0, True),
                     ('', ['i-one', 'i-two'], 0, 1, False),
                     ('aws_instance.mmiv', ['i-existing'], 0, 0, False),
                     ('aws_instance.mmiv', ['i-other'], 0, 1, False),
                     ('', [], 1, 1, False)]
            for state, instances, aws_exit, expected, imported in cases:
                with self.subTest(state=state, instances=instances, aws_exit=aws_exit):
                    log = directory / 'log'
                    log.write_text('')
                    result = subprocess.run(['bash', str(ROOT / 'scripts/cd/adopt-existing-ec2.sh')],
                        env={**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                             'TEST_LOG': str(log), 'TEST_STATE': state,
                             'TEST_INSTANCES': json.dumps(instances), 'TEST_AWS_EXIT': str(aws_exit)},
                        capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected, result.stderr)
                    calls = [json.loads(line) for line in log.read_text().splitlines()]
                    imports = [c for c in calls if c[:2] == ['terraform', 'import']]
                    self.assertEqual(bool(imports), imported)
                    if imported:
                        self.assertEqual(imports[0][-2:], ['aws_instance.mmiv', 'i-existing'])
                    if state:
                        self.assertEqual(len(calls), 3)

    def test_ec2_plan_rejects_deletion_and_both_replacement_orders(self):
        for actions in [['create'], ['update'], ['no-op'], ['delete'],
                        ['delete', 'create'], ['create', 'delete']]:
            with self.subTest(actions=actions):
                result = subprocess.run(['python3', str(ROOT / 'scripts/cd/check-ec2-plan.py')],
                    input=json.dumps({'resource_changes': [{'type': 'aws_instance',
                        'address': 'aws_instance.mmiv', 'change': {'actions': actions}}]}),
                    capture_output=True, text=True)
                self.assertEqual(result.returncode, int('delete' in actions))

    def test_workflow_reuses_existing_images_and_rejects_registry_errors(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/build-service.yml').read_text())
        step = next(s for s in workflow['jobs']['build']['steps'] if s.get('id') == 'image')
        script = step['run'].replace('${{ inputs.service-path }}', 'services/transfer-service')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            aws = directory / 'aws'
            aws.write_text('#!/usr/bin/env python3\nimport os\nprint(os.environ["TEST_ECR"])\n')
            aws.chmod(0o755)
            docker = directory / 'docker'
            docker.write_text('#!/usr/bin/env python3\nimport os,sys,json\n'
                              'with open(os.environ["TEST_LOG"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n')
            docker.chmod(0o755)
            for response, expected, command in [
                ({'images': [{'imageTag': 'existing'}], 'failures': []}, 0, 'pull'),
                ({'images': [], 'failures': ['ImageNotFound']}, 0, 'build'),
                ({'images': [], 'failures': ['AccessDenied']}, 1, None),
            ]:
                with self.subTest(response=response):
                    log = directory / 'log'
                    log.write_text('')
                    result = subprocess.run(['bash', '-c', script], cwd=directory,
                        env={**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                             'TEST_ECR': json.dumps(response), 'TEST_LOG': str(log),
                             'ECR_REGISTRY': 'example.invalid', 'ECR_REPOSITORY': 'mmiv',
                             'IMAGE_TAG': 'transfer-service-commit',
                             'GITHUB_ENV': str(directory / 'github-env'),
                             'GITHUB_OUTPUT': str(directory / 'github-output')},
                        capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected, result.stderr)
                    calls = [json.loads(line) for line in log.read_text().splitlines()]
                    self.assertEqual(calls[0][0] if calls else None, command)

if __name__ == '__main__':
    unittest.main()
