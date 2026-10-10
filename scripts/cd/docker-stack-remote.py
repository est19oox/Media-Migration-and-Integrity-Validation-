#!/usr/bin/env python3
"""EC2-side Docker lifecycle. Called through SSM, using the host instance role."""
import json
import fcntl
import re
from concurrent.futures import ThreadPoolExecutor
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


PROJECT = 'mmiv-stack'
DIRECTORY = '/opt/mmiv/stack'
ENVIRONMENT = '/etc/mmiv/integrity.env'
def staged_path(state):
    return Path('/etc/mmiv/next-integrity-' + state['RELEASE_ID'] + '.env')


def parallel(function, values):
    values = list(values)
    if not values:
        return
    with ThreadPoolExecutor(max_workers=min(5, len(values))) as pool:
        # Await all workers before returning an error or beginning recovery.
        futures = [pool.submit(function, value) for value in values]
        errors = []
        for future in futures:
            try:
                future.result()
            except Exception as error:
                errors.append(error)
        if errors:
            raise errors[0]


def config_for(state):
    services = {}
    for name, item in state['services'].items():
        port = item['port']
        service = {'image': item['image'], 'container_name': name, 'restart': 'unless-stopped',
                   'ports': [f'{"0.0.0.0" if name == "api-gateway" else "127.0.0.1"}:{port}:{port}'],
                   'environment': {'MMIV_MEDIA_ROOT': '/media', 'AWS_REGION': state['AWS_REGION'],
                                   'MMIV_BUCKET_NAME': state['MMIV_BUCKET_NAME'],
                                   'EUREKA_DEFAULT_ZONE': 'http://service-registry:8761/eureka/'},
                   'volumes': [{'type': 'bind', 'source': '/var/lib/mmiv/media', 'target': '/media',
                                'read_only': True}], 'networks': ['backend']}
        if name == 'integrity-service':
            service['env_file'] = [{'path': ENVIRONMENT, 'format': 'raw'}]
        services[name] = service
    return {'services': services, 'networks': {'backend': {'external': True, 'name': 'mmiv-backend'}}}


def ensure_images(state, images):
    missing = sorted({image for image in images
                      if command('docker', 'image', 'inspect', image, check=False).returncode})
    if missing:
        password = command('aws', 'ecr', 'get-login-password', '--region', state['AWS_REGION']).stdout
        subprocess.run(['docker', 'login', '--username', 'AWS', '--password-stdin', state['ECR_REGISTRY']],
                       input=password, text=True, capture_output=True, check=True, timeout=60)
        # Bound network/disk pressure while avoiding sequential image downloads.
        with ThreadPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(command, 'docker', 'pull', image, timeout=600) for image in missing]
            for future in futures:
                future.result()


def compose_config(path):
    return json.loads(command('docker', 'compose', '-f', str(path), 'config',
                              '--no-env-resolution', '--format', 'json').stdout)


def up(compose, names, force=()):
    names = list(names)
    if not names:
        return
    # Migrate earlier per-service Compose projects or unmanaged containers once.
    for name in names:
        current = inspect(name)
        if current:
            owner = (current['Config'].get('Labels') or {}).get('com.docker.compose.project')
            if owner not in (None, PROJECT, 'mmiv-' + name):
                raise RuntimeError('Container belongs to another Compose project')
            if owner != PROJECT:
                command('docker', 'rm', '-f', name)
    base = ['docker', 'compose', '-p', PROJECT, '-f', str(compose),
            'up', '-d', '--no-deps', '--no-build', '--pull', 'never']
    forced = [name for name in names if name in force]
    if forced:
        command(*base, '--force-recreate', *forced)
    ordinary = [name for name in names if name not in forced]
    if ordinary:
        command(*base, *ordinary)


def ready(name, state):
    item = state['services'][name]
    healthy(item['port'])
    if name not in ('service-registry', 'discovery-service'):
        current = inspect(name)
        address = current['NetworkSettings']['Networks']['mmiv-backend']['IPAddress']
        if not address:
            raise RuntimeError('Service has no shared-network address')
        registered(name, address, item['port'])


def save(path, data):
    atomic_write(path, json.dumps(data, indent=2, sort_keys=True) + '\n')


def restore(state, directory, journal_path, journal):
    if journal['release_id'] != state['RELEASE_ID']:
        raise RuntimeError('Rollback journal does not match this release')
    current_path = directory / 'current-release.json'
    if current_path.exists() and json.loads(current_path.read_text())['RELEASE_ID'] != state['RELEASE_ID']:
        raise RuntimeError('A newer release owns this stack; refusing stale rollback')
    if journal.get('restored'):
        return {**state, 'READY': False, 'ROLLED_BACK': True, 'changes': journal['changes'],
                'recovery': journal.get('recovery', {})}
    previous = journal['previous_config']
    selected = [name for name in journal['touched'] if journal['changes'][name]['changed']]
    ensure_images(state, [previous['services'][name]['image'] for name in selected
                          if name in previous['services']])
    environment = Path(ENVIRONMENT)
    if journal['environment_existed']:
        atomic_write(environment, (journal_path.parent / 'previous-integrity.env').read_text())
    else:
        environment.unlink(missing_ok=True)
    compose = directory / 'compose.yaml'
    save(compose, previous)
    recovery = journal.setdefault('recovery', {})
    # Gateway first, then backends, then Registry. Unchanged containers stay put.
    order = {'api-gateway': 0, 'service-registry': 2}
    for name in sorted(selected, key=lambda n: (order.get(n, 1), -int(state['services'][n]['port']))):
        if name in recovery:
            continue
        if name not in previous['services']:
            current = inspect(name)
            if current:
                owner = (current['Config'].get('Labels') or {}).get('com.docker.compose.project')
                if owner != PROJECT:
                    raise RuntimeError('A foreign container replaced a new service; refusing removal')
                command('docker', 'rm', '-f', name)
            recovery[name] = 'REMOVED_NEW_SERVICE'
        else:
            force = [name] if name == 'integrity-service' and journal['environment_changed'] else []
            up(compose, [name], force)
            healthy(state['services'][name]['port'])
            recovery[name] = 'RESTORED'
        save(journal_path, journal)
    if not journal['compose_existed'] and not previous['services']:
        compose.unlink(missing_ok=True)
    parallel(lambda name: ready(name, state),
             [name for name in previous['services'] if name in state['services']])
    journal['restored'] = True
    save(journal_path, journal)
    result = {**state, 'READY': False, 'ROLLED_BACK': True, 'changes': journal['changes'], 'recovery': recovery}
    result.pop('recovery_error', None)
    save(current_path, result)
    return result


def reconcile(action, state):
    directory = Path(DIRECTORY)
    compose = directory / 'compose.yaml'
    journal_path = directory / 'releases' / state['RELEASE_ID'] / 'rollback.json'
    if action == 'rollback':
        if not journal_path.exists():
            return {**state, 'READY': False, 'ROLLED_BACK': False, 'recovery': {'release': 'NOT_STARTED'}}
        return restore(state, directory, journal_path, json.loads(journal_path.read_text()))
    # A repeated command for the same release must never overwrite its original backup.
    if journal_path.exists():
        current = json.loads((directory / 'current-release.json').read_text())
        if current['RELEASE_ID'] != state['RELEASE_ID'] or not current.get('READY'):
            raise RuntimeError('This release was interrupted or restored; recover it or use a new release ID')
        parallel(lambda name: ready(name, state), state['services'])
        return current
    current_path = directory / 'current-release.json'
    if current_path.exists():
        current = json.loads(current_path.read_text())
        if not current.get('READY') and not current.get('ROLLED_BACK'):
            interrupted = directory / 'releases' / current['RELEASE_ID'] / 'rollback.json'
            if not interrupted.exists():
                raise RuntimeError('An interrupted release has no recovery journal')
            restore(current, directory, interrupted, json.loads(interrupted.read_text()))
    desired = config_for(state)
    compose_existed = compose.exists()
    previous = json.loads(compose.read_text()) if compose_existed else {'services': {}, 'networks': desired['networks']}
    before = {name: inspect(name) for name in state['services']}
    for name, container in before.items():
        if not container:
            previous['services'].pop(name, None)
            continue
        owner = (container['Config'].get('Labels') or {}).get('com.docker.compose.project')
        if owner not in (None, PROJECT, 'mmiv-' + name):
            raise RuntimeError('Container belongs to another Compose project')
        if name not in previous['services']:
            legacy = Path('/opt/mmiv') / name / 'compose.yaml'
            previous['services'][name] = (compose_config(legacy)['services'][name]
                                          if legacy.exists() else dict(desired['services'][name]))
        previous['services'][name]['image'] = container['Config']['Image']
    environment = Path(ENVIRONMENT)
    desired_environment = (staged_path(state).read_text() if state.get('ORACLE_CONFIG_STAGED')
                           else '# Embedded H2 mode: no Oracle secrets required.\n')
    env_changed = ('integrity-service' in state['services'] and
                   (not environment.exists() or environment.read_text() != desired_environment))
    changes = {name: {'changed': not container or previous['services'].get(name) != desired['services'][name]
                      or (container['Config'].get('Labels') or {}).get('com.docker.compose.project') != PROJECT
                      or (name == 'integrity-service' and env_changed),
                      'previous': container is not None,
                      'previous_image': container['Config']['Image'] if container else None}
               for name, container in before.items()}
    # Finish every image download before saving backups or changing any running service.
    ensure_images(state, [item['image'] for item in state['services'].values()])
    journal = {'release_id': state['RELEASE_ID'], 'previous_config': previous, 'changes': changes,
               'compose_existed': compose_existed, 'environment_existed': environment.exists(),
               'environment_changed': env_changed, 'touched': [], 'restored': False}
    if environment.exists():
        atomic_write(journal_path.parent / 'previous-integrity.env', environment.read_text())
    save(journal_path, journal)
    result = {**state, 'READY': False, 'ROLLED_BACK': False, 'changes': changes}
    save(directory / 'current-release.json', result)
    try:
        save(compose, desired)
        if 'integrity-service' in state['services']:
            atomic_write(environment, desired_environment)
        groups = [['service-registry'], sorted(n for n in state['services']
                  if n not in ('service-registry', 'api-gateway')), ['api-gateway']]
        for names in groups:
            journal['touched'].extend(names)
            save(journal_path, journal)
            force = ['integrity-service'] if env_changed and before.get('integrity-service') else []
            up(compose, names, force)
            parallel(lambda name: ready(name, state), names)
        result['READY'] = True
        save(directory / 'current-release.json', result)
        return result
    except Exception as error:
        result['error'] = type(error).__name__
        try:
            result = restore(result, directory, journal_path, journal)
        except Exception as recovery_error:
            result['recovery_error'] = type(recovery_error).__name__
            save(directory / 'current-release.json', result)
        return result


def run(action, state):
    if action not in ('deploy', 'rollback') or not re.fullmatch(r'[a-zA-Z0-9-]{1,120}', state['RELEASE_ID']):
        raise ValueError('Invalid release action or identifier')
    directory = Path(DIRECTORY)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory / 'deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            return reconcile(action, state)
        finally:
            if action == 'deploy':
                staged_path(state).unlink(missing_ok=True)


if __name__ == '__main__':
    state = json.loads(sys.argv[2])
    try:
        result = run(sys.argv[1], state)
    except Exception as error:
        result = {**state, 'READY': False, 'ROLLED_BACK': False, 'error': type(error).__name__}
    print('MMIV_RELEASE_STATE=' + json.dumps(result))
    sys.exit(0 if (result.get('READY') if sys.argv[1] == 'deploy' else
                   'recovery_error' not in result and (result.get('ROLLED_BACK') or result.get('recovery', {}).get('release') == 'NOT_STARTED')) else 1)
