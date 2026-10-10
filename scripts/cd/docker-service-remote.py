#!/usr/bin/env python3
"""EC2-side Docker lifecycle. Called through SSM, using the host instance role."""
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def command(*args, check=True, timeout=120):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=timeout)


def healthy(port):
    deadline = time.monotonic() + 150
    for attempt in range(30):
        if time.monotonic() >= deadline:
            break
        try:
            with urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                body = json.load(response)
                if response.status == 200 and isinstance(body, dict) and body.get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        if attempt < 29:
            time.sleep(5)
    raise RuntimeError('Container health did not become UP')


def registered(name, address=None, port=None):
    # Discovery is a placeholder without Eureka registration.
    if name in ('service-registry', 'discovery-service'):
        return
    deadline = time.monotonic() + 180
    for attempt in range(30):
        if time.monotonic() >= deadline:
            break
        try:
            request = Request('http://127.0.0.1:8761/eureka/apps/' + name.upper(),
                              headers={'Accept': 'application/json'})
            with urlopen(request, timeout=3) as response:
                instances = json.load(response)['application'].get('instance', [])
            if isinstance(instances, dict):
                instances = [instances]
            ready = any(isinstance(instance, dict) and instance.get('status') == 'UP'
                        and (address is None or instance.get('ipAddr') == address)
                        and (port is None or (isinstance(instance.get('port'), dict)
                             and str(instance['port'].get('$')) == str(port)))
                        for instance in instances)
            if ready and name == 'api-gateway':
                for path, expected in [('/upload.html', 200), ('/files.html', 200),
                                       ('/api/v1/files/invalid!/content', 400)]:
                    try:
                        with urlopen('http://127.0.0.1:8080' + path, timeout=3) as response:
                            status = response.status
                    except HTTPError as error:
                        status = error.code
                    if status != expected:
                        ready = False
                        break
            if ready:
                return
        except (OSError, ValueError, KeyError, TypeError):
            pass
        if attempt < 29:
            time.sleep(5)
    raise RuntimeError('Service registration or Gateway routes did not become ready')


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.next')
    temporary.write_text(content)
    temporary.chmod(0o600)
    temporary.replace(path)


def inspect(name):
    result = command('docker', 'inspect', name, check=False)
    if result.returncode == 0:
        return json.loads(result.stdout)[0]
    # Do not mistake a stopped Docker daemon for an absent container.
    command('docker', 'info')
    if 'No such' not in result.stderr:
        raise RuntimeError('Could not inspect the service container')
    return None


def reconcile(action, state):
    name, image, port = state['SERVICE_NAME'], state['SERVICE_IMAGE'], state['SERVICE_PORT']
    project = 'mmiv-' + name
    directory = Path('/opt/mmiv') / name
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    compose = directory / 'compose.yaml'
    backup = directory / 'previous-compose.yaml'
    previous_image = Path('/var/lib/mmiv') / (name + '.previous-image')
    environment = Path('/etc/mmiv/integrity.env')
    old_environment = directory / 'previous-integrity.env'
    staged_environment = Path('/etc/mmiv/next-integrity.env')
    before = inspect(name)
    if before and (before['Config'].get('Labels') or {}).get('com.docker.compose.project', project) != project:
        raise RuntimeError('Container belongs to another Compose project')

    def ensure_image(reference):
        if command('docker', 'image', 'inspect', reference, check=False).returncode:
            password = command('aws', 'ecr', 'get-login-password', '--region', state['AWS_REGION']).stdout
            subprocess.run(['docker', 'login', '--username', 'AWS', '--password-stdin', state['ECR_REGISTRY']],
                           input=password, text=True, capture_output=True, check=True, timeout=60)
            command('docker', 'pull', reference, timeout=600)

    def apply(reference, force_recreate=False):
        ensure_image(reference)
        current = inspect(name)
        if current and 'com.docker.compose.project' not in (current['Config'].get('Labels') or {}):
            command('docker', 'rm', '-f', name)
        args = ['docker', 'compose', '-p', project, '-f', str(compose),
                'up', '-d', '--no-build', '--pull', 'missing']
        if force_recreate:
            args.append('--force-recreate')
        command(*args)
        healthy(port)

    def restore():
        if not backup.exists() or not previous_image.exists():
            raise RuntimeError('No previous deployment is available for rollback')
        reference = previous_image.read_text().strip()
        # Compose parses historical YAML too; retain the captured runtime image.
        config = json.loads(command('docker', 'compose', '-p', project, '-f', str(backup),
                                    'config', '--no-env-resolution', '--format', 'json').stdout)
        config['services'][name]['image'] = reference
        atomic_write(compose, json.dumps(config, indent=2) + '\n')
        environment_changed = False
        if name == 'integrity-service' and old_environment.exists():
            environment_changed = not environment.exists() or environment.read_text() != old_environment.read_text()
            atomic_write(environment, old_environment.read_text())
        apply(reference, environment_changed)

    if action == 'rollback':
        restore()
        state['ROLLED_BACK'] = True
        return state

    service = {'image': image, 'container_name': name, 'restart': 'unless-stopped',
               'ports': [f'{"0.0.0.0" if name == "api-gateway" else "127.0.0.1"}:{port}:{port}'],
               'environment': {'MMIV_MEDIA_ROOT': '/media', 'AWS_REGION': state['AWS_REGION'],
                               'MMIV_BUCKET_NAME': state['MMIV_BUCKET_NAME'],
                               'EUREKA_DEFAULT_ZONE': 'http://service-registry:8761/eureka/'},
               'volumes': [{'type': 'bind', 'source': '/var/lib/mmiv/media', 'target': '/media', 'read_only': True}],
               'networks': ['backend']}
    desired_environment = None
    if name == 'integrity-service':
        service['env_file'] = [{'path': str(environment), 'format': 'raw'}]
        desired_environment = (staged_environment.read_text() if state.get('ORACLE_CONFIG_STAGED')
                               else '# Embedded H2 mode: no Oracle secrets required.\n')
    desired = json.dumps({'services': {name: service}, 'networks': {
        'backend': {'external': True, 'name': 'mmiv-backend'}}}, indent=2) + '\n'
    changed = (not compose.exists() or compose.read_text() != desired or not before
               or before['Config']['Image'] != image
               or 'com.docker.compose.project' not in (before['Config'].get('Labels') or {}))
    environment_changed = (desired_environment is not None and
                           (not environment.exists() or environment.read_text() != desired_environment))
    changed = changed or environment_changed
    # Pull succeeds before changing the running deployment or its rollback record.
    ensure_image(image)
    if changed:
        if before:
            if compose.exists():
                atomic_write(backup, compose.read_text())
            else:
                legacy = json.loads(desired)
                legacy['services'][name]['image'] = before['Config']['Image']
                atomic_write(backup, json.dumps(legacy, indent=2) + '\n')
            atomic_write(previous_image, before['Config']['Image'] + '\n')
            if name == 'integrity-service':
                atomic_write(old_environment, environment.read_text() if environment.exists() else '')
        else:
            backup.unlink(missing_ok=True)
            previous_image.unlink(missing_ok=True)
        atomic_write(compose, desired)
        if desired_environment is not None:
            atomic_write(environment, desired_environment)
    try:
        apply(image, environment_changed)
        if name not in ('service-registry', 'discovery-service'):
            current = inspect(name)
            address = current['NetworkSettings']['Networks']['mmiv-backend']['IPAddress']
            if not address:
                raise RuntimeError('Service has no address on the shared Docker network')
            registered(name, address, port)
    except Exception:
        if changed and before:
            restore()
        raise
    after = inspect(name)
    state.update(DEPLOYMENT_CHANGED=bool(changed or before['Id'] != after['Id']),
                 ROLLBACK_AVAILABLE=bool(before), ROLLED_BACK=False)
    return state


def run(action, state):
    try:
        return reconcile(action, state)
    finally:
        # Also clean secrets after an early pull/config/ownership failure.
        if action == 'deploy' and state.get('SERVICE_NAME') == 'integrity-service':
            Path('/etc/mmiv/next-integrity.env').unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        state = run(sys.argv[1], json.loads(sys.argv[2]))
        print('MMIV_DEPLOYMENT_STATE=' + json.dumps(state))
    except Exception as error:
        # Never print credentials or subprocess input/output on lifecycle failure.
        detail = ': ' + str(error) if isinstance(error, RuntimeError) else ''
        print('Docker lifecycle failed: ' + type(error).__name__ + detail, file=sys.stderr)
        sys.exit(1)
