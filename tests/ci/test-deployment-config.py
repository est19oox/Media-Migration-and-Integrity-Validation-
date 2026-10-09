#!/usr/bin/env python3
"""Validate rendered Compose networking and exercise EC2 adoption/plan guards."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest
import yaml
from jinja2 import Environment, StrictUndefined

ROOT = Path(__file__).resolve().parents[2]
ROLE = ROOT / 'infrastructure/ansible/roles/microservice'

class DeploymentConfigurationTest(unittest.TestCase):
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
        services = ['service-registry', 'api-gateway'] + [s['name'] for s in
            workflow['jobs']['deploy-backends']['strategy']['matrix']['service']]
        for service in services:
            self.assertIn(service, config['services'])
            self.assertTrue(config['common'] + config['services'][service])

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
        workflow = yaml.safe_load((ROOT / '.github/workflows/deploy-service.yml').read_text())
        step = next(s for s in workflow['jobs']['deploy']['steps'] if s.get('id') == 'image')
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
