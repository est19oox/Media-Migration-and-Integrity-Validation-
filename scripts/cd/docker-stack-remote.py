#!/usr/bin/env python3
"""EC2-side Docker lifecycle. Called through SSM, using the host instance role."""
import json
import hashlib
import os
import secrets
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
    # The registry server does not register itself as a client.
    if name == 'service-registry':
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
                for path, expected in [('/', 200), ('/login.html', 200),
                                       ('/api/v1/files/invalid!/content', 401),
                                       ('/api/metadata/plans/00000000-0000-0000-0000-000000000000', 401),
                                       ('/api/reports/00000000-0000-0000-0000-000000000000', 401)]:
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
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
        os.chmod(temporary, 0o600)
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


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
ORACLE_IMAGE = 'gvenzl/oracle-free:23.26.3-slim-faststart'
ORACLE_VOLUME = 'mmiv-oracle-data'
ORACLE_STORAGE_SQL = '''
SET SERVEROUTPUT ON
ALTER SESSION SET CONTAINER=FREEPDB1;
DECLARE
  high_block NUMBER;
  target_mb NUMBER;
  before_bytes NUMBER;
  after_bytes NUMBER;
  allocated_bytes NUMBER;
  max_mb NUMBER;
  unlimited_count NUMBER;
BEGIN
  SELECT NVL(SUM(bytes), 0) INTO allocated_bytes FROM dba_segments WHERE owner = 'USER_ACCESS';
  IF allocated_bytes > 128 * 1048576 THEN
    RAISE_APPLICATION_ERROR(-20010, 'Existing USER_ACCESS allocation exceeds the 128 MiB demo budget; increase quota before deployment');
  END IF;
  SELECT COUNT(*) INTO unlimited_count FROM dba_sys_privs
    WHERE grantee = 'USER_ACCESS' AND privilege = 'UNLIMITED TABLESPACE';
  IF unlimited_count > 0 THEN
    EXECUTE IMMEDIATE 'REVOKE UNLIMITED TABLESPACE FROM USER_ACCESS';
  END IF;
  EXECUTE IMMEDIATE 'ALTER USER USER_ACCESS QUOTA 128M ON USERS';
  SELECT NVL(SUM(bytes), 0) INTO before_bytes FROM
    (SELECT bytes FROM dba_data_files WHERE tablespace_name = 'USERS'
     UNION ALL SELECT bytes FROM dba_temp_files WHERE tablespace_name = 'TEMP');
  -- Only shrink unused file tails. Do not move rows, rebuild indexes, or
  -- touch SYSTEM, SYSAUX, UNDO, redo logs, or other containers.
  FOR f IN (SELECT d.file_id, d.bytes, t.block_size FROM dba_data_files d
            JOIN dba_tablespaces t ON t.tablespace_name = d.tablespace_name
            WHERE d.tablespace_name = 'USERS' AND d.online_status = 'ONLINE') LOOP
    SELECT NVL(MAX(block_id + blocks), 0) INTO high_block
      FROM dba_extents WHERE file_id = f.file_id;
    target_mb := GREATEST(32, CEIL(high_block * f.block_size / 1048576) + 16);
    IF target_mb * 1048576 < f.bytes THEN
      BEGIN
        EXECUTE IMMEDIATE 'ALTER DATABASE DATAFILE ' || f.file_id ||
                          ' RESIZE ' || target_mb || 'M';
      EXCEPTION WHEN OTHERS THEN
        -- Oracle checks file metadata and concurrent allocation too.
        -- An occupied tail or minimum-file constraint leaves the file intact.
        IF SQLCODE NOT IN (-3297, -3214) THEN RAISE; END IF;
        DBMS_OUTPUT.PUT_LINE('MMIV USERS file retained: ' || f.file_id);
      END;
    END IF;
  END LOOP;
  FOR t IN (SELECT tablespace_name FROM dba_temp_files
            WHERE tablespace_name = 'TEMP'
            GROUP BY tablespace_name HAVING SUM(bytes) > 64 * 1048576) LOOP
    EXECUTE IMMEDIATE 'ALTER TABLESPACE TEMP SHRINK SPACE KEEP 64M';
  END LOOP;
  -- Bound growth after reclaiming free space. Existing larger files are
  -- retained, never truncated below allocated data. Caps are per file.
  FOR f IN (SELECT file_id, bytes, autoextensible FROM dba_data_files
            WHERE tablespace_name = 'USERS') LOOP
    max_mb := GREATEST(512, CEIL(f.bytes / 1048576));
    IF f.autoextensible = 'YES' THEN
      EXECUTE IMMEDIATE 'ALTER DATABASE DATAFILE ' || f.file_id ||
                        ' AUTOEXTEND ON NEXT 8M MAXSIZE ' || max_mb || 'M';
    END IF;
  END LOOP;
  FOR f IN (SELECT file_id, bytes, autoextensible FROM dba_temp_files
            WHERE tablespace_name = 'TEMP') LOOP
    max_mb := GREATEST(128, CEIL(f.bytes / 1048576));
    IF f.autoextensible = 'YES' THEN
      EXECUTE IMMEDIATE 'ALTER DATABASE TEMPFILE ' || f.file_id ||
                        ' AUTOEXTEND ON NEXT 8M MAXSIZE ' || max_mb || 'M';
    END IF;
  END LOOP;
  SELECT NVL(SUM(bytes), 0) INTO after_bytes FROM
    (SELECT bytes FROM dba_data_files WHERE tablespace_name = 'USERS'
     UNION ALL SELECT bytes FROM dba_temp_files WHERE tablespace_name = 'TEMP');
  DBMS_OUTPUT.PUT_LINE('MMIV User Access storage bytes: before=' || before_bytes ||
                      ', after=' || after_bytes || ', reclaimed=' || (before_bytes-after_bytes));
END;
/
'''
ORACLE_STARTUP = '''
# Sourced by the Oracle image after startup; contains no credential values.
rm -f /opt/oracle/oradata/mmiv-app-user-ready
if ! sqlplus -L -s / as sysdba >/dev/null 2>&1 <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
ALTER SESSION SET CONTAINER=FREEPDB1;
DECLARE
  account_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO account_count FROM DBA_USERS WHERE USERNAME = 'USER_ACCESS';
  IF account_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE USER USER_ACCESS IDENTIFIED BY "${APP_USER_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS';
  END IF;
  EXECUTE IMMEDIATE 'GRANT DB_DEVELOPER_ROLE TO USER_ACCESS';
END;
/
''' + ORACLE_STORAGE_SQL + '''
EXIT;
SQL
then
  echo 'MMIV User Access schema initialization or storage reclaim failed.' >&2
  return 1
fi
if ! sqlplus -L -s /nolog >/dev/null 2>&1 <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
CONNECT USER_ACCESS/"${APP_USER_PASSWORD}"@//localhost:1521/FREEPDB1
SELECT 1 FROM DUAL;
EXIT;
SQL
then
  echo 'MMIV User Access database login check failed.' >&2
  return 1
fi
touch /opt/oracle/oradata/mmiv-app-user-ready
'''


# Only literal errors authored here may be exported. Raw subprocess output,
# arguments and arbitrary exception strings can contain credential material.
SAFE_FAILURE_MESSAGES = frozenset(['A foreign container replaced a new service; refusing removal', 'A newer release owns this stack; refusing stale rollback', 'An interrupted release has no recovery journal', 'Container belongs to another Compose project', 'Container health did not become UP', 'Could not inspect Oracle volume', 'Could not inspect the service container', 'Immutable User Access bundle was modified', 'Invalid JWT issuer or key identity', 'Invalid User Access configuration bundle identity', 'Invalid managed Oracle credentials; restore their backup', 'Invalid release action or identifier', 'Oracle container belongs to another owner', 'Oracle data exists without credentials; restore the host secret backup', 'Oracle exists without its saved Compose configuration', 'Rollback journal does not match this release', 'Service has no shared-network address', 'Service registration or Gateway routes did not become ready', 'Signing key is missing; restore its backup rather than rotate silently', 'Stored JWT keys do not match', 'The deployed release requires MMIV_LOG_QUEUE_URL', 'This release was interrupted or restored; recover it or use a new release ID', 'User Access configuration bundle is incomplete', 'User Access requires a staged immutable configuration bundle'])


def stage(label, function, *args):
    try:
        return function(*args)
    except Exception as error:
        if not hasattr(error, 'deployment_phase'):
            error.deployment_phase = label
        raise


def failure_details(error, state):
    detail = str(error) if isinstance(error, (RuntimeError, ValueError)) else ''
    result = {'error': type(error).__name__,
              'error_detail': detail if detail in SAFE_FAILURE_MESSAGES else
                              'Remote lifecycle operation failed; inspect deployment evidence.',
              'failure_phase': getattr(error, 'deployment_phase', 'release_reconciliation')}
    if isinstance(error, subprocess.CalledProcessError):
        result['command_exit_code'] = error.returncode
        args = error.cmd if isinstance(error.cmd, (list, tuple)) else []
        if args and args[0] in ('docker', 'aws', 'openssl'):
            result['failed_operation'] = args[0] + (' compose' if tuple(args[:2]) == ('docker', 'compose') else '')
    containers = {}
    for name in list(state.get('services', {})) + (['oracle'] if state.get('MANAGED_USER_ACCESS_ORACLE') else []):
        try:
            current = inspect(name)
            if not current:
                containers[name] = {'status': 'absent'}
                continue
            status = current.get('State') or {}
            containers[name] = {'status': status.get('Status', 'unknown'),
                'health': (status.get('Health') or {}).get('Status', 'unavailable'),
                'exit_code': status.get('ExitCode'), 'oom_killed': status.get('OOMKilled')}
        except Exception:
            containers[name] = {'status': 'inspection_failed'}
    result['container_states'] = containers
    return result


def staged_path(state):
    return Path('/etc/mmiv/next-integrity-' + state['RELEASE_ID'] + '.env')


def access_staged_path(state):
    return Path('/etc/mmiv/next-user-access-' + state['RELEASE_ID'] + '.json')


def managed_access(state):
    """Create host-only credentials once, before first database initialization."""
    directory = Path('/etc/mmiv/managed-oracle')
    settings = directory / 'credentials.json'
    existing = command('docker', 'volume', 'inspect', ORACLE_VOLUME, check=False)
    if existing.returncode != 0:
        # Verify absence through a successful daemon query, not localized stderr.
        command('docker', 'info')
        volumes = command('docker', 'volume', 'ls', '--format', '{{.Name}}')
        if ORACLE_VOLUME in volumes.stdout.splitlines():
            raise RuntimeError('Could not inspect Oracle volume')
    if not settings.exists():
        if existing.returncode == 0 or inspect('oracle'):
            raise RuntimeError('Oracle data exists without credentials; restore the host secret backup')
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        # Letter prefix avoids Oracle password identifier restrictions.
        save(settings, {'administrator': 'A' + secrets.token_hex(24),
                        'user_access': 'U' + secrets.token_hex(24)})
    values = json.loads(settings.read_text())
    if set(values) != {'administrator', 'user_access'} or not all(
            isinstance(v, str) and re.fullmatch(r'[AU][a-f0-9]{48}', v) for v in values.values()):
        raise RuntimeError('Invalid managed Oracle credentials; restore their backup')
    private, public = directory / 'private.pem', directory / 'public.pem'
    if not private.exists():
        if public.exists() or existing.returncode == 0:
            raise RuntimeError('Signing key is missing; restore its backup rather than rotate silently')
        application = inspect('user-access-service')
        if application:
            # Switching from an external database must retain the deployed JWT key.
            current_compose = Path(DIRECTORY) / 'compose.yaml'
            if not current_compose.exists():
                raise RuntimeError('Signing key is missing; restore its backup rather than rotate silently')
            volumes = json.loads(current_compose.read_text())['services'].get('user-access-service', {}).get('volumes', [])
            sources = {v.get('target'): v.get('source', '') for v in volumes if isinstance(v, dict)}
            previous_private = sources.get('/run/mmiv-jwt/private.pem', '')
            previous_public = sources.get('/run/mmiv-jwt/public.pem', '')
            if not all(re.fullmatch(r'/etc/mmiv/user-access/versions/[a-f0-9]{64}/' + filename, source)
                       and Path(source).is_file() for source, filename in
                       [(previous_private, 'private.pem'), (previous_public, 'public.pem')]):
                raise RuntimeError('Signing key is missing; restore its backup rather than rotate silently')
            atomic_write(private, Path(previous_private).read_text())
            atomic_write(public, Path(previous_public).read_text())
        else:
            key = command('openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048').stdout
            atomic_write(private, key)
    derived = command('openssl', 'pkey', '-in', str(private), '-pubout').stdout
    if public.exists() and public.read_text() != derived:
        raise RuntimeError('Stored JWT keys do not match')
    if not public.exists():
        atomic_write(public, derived)
    issuer = state.get('USER_ACCESS_JWT_ISSUER', 'https://auth.mmiv.example')
    key_id = state.get('USER_ACCESS_JWT_KEY_ID', 'mmiv-user-access-key-1')
    if any(not isinstance(v, str) or not v or '\n' in v or '\r' in v for v in (issuer, key_id)):
        raise ValueError('Invalid JWT issuer or key identity')
    bootstrap = directory / 'bootstrap-admin.json'
    if not bootstrap.exists():
        application = inspect('user-access-service')
        already_enabled = application and 'MMIV_BOOTSTRAP_ADMIN_ENABLED=true' in application.get('Config', {}).get('Env', [])
        if (directory / 'bootstrap-admin.env').exists() or already_enabled:
            raise RuntimeError('Bootstrap administrator credentials are missing; restore their private backup')
        save(bootstrap, {'username': 'admin', 'password': secrets.token_hex(24)})
    administrator = json.loads(bootstrap.read_text())
    if set(administrator) != {'username', 'password'} or administrator['username'] != 'admin' or not re.fullmatch(r'[a-f0-9]{48}', administrator['password']):
        raise RuntimeError('Invalid bootstrap administrator configuration; restore its private backup')
    environment = ('ORACLE_JDBC_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1\n'
                   'ORACLE_USER=USER_ACCESS\nORACLE_PASSWORD=' + values['user_access'] + '\n'
                   'JWT_ISSUER=' + issuer + '\nJWT_KEY_ID=' + key_id + '\n'
                   'JWT_PRIVATE_KEY_PATH=file:/run/mmiv-jwt/private.pem\n'
                   'JWT_PUBLIC_KEY_PATH=file:/run/mmiv-jwt/public.pem\n')
    bootstrap_env = ('MMIV_BOOTSTRAP_ADMIN_ENABLED=true\nMMIV_BOOTSTRAP_ADMIN_USERNAME=admin\n'
                     'MMIV_BOOTSTRAP_ADMIN_PASSWORD=' + administrator['password'] + '\n')
    atomic_write(directory / 'bootstrap-admin.env', bootstrap_env)
    identity = hashlib.sha256((environment + private.read_text() + derived).encode()).hexdigest()
    bundle = Path('/etc/mmiv/user-access/versions/' + identity)
    bundle.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name, content in [('environment.env', environment), ('private.pem', private.read_text()), ('public.pem', derived)]:
        target = bundle / name
        if target.exists() and target.read_text() != content:
            raise RuntimeError('Immutable User Access bundle was modified')
        if not target.exists():
            atomic_write(target, content)
    oracle_env = ('ORACLE_PASSWORD=' + values['administrator'] + '\nAPP_USER=USER_ACCESS\n'
                  'APP_USER_PASSWORD=' + values['user_access'] + '\n')
    target = directory / 'oracle.env'
    if not target.exists() or target.read_text() != oracle_env:
        atomic_write(target, oracle_env)
    if existing.returncode != 0:
        command('docker', 'volume', 'create', ORACLE_VOLUME)
    return {**state, 'USER_ACCESS_CONFIG_ID': identity}


def managed_messaging():
    directory = Path('/etc/mmiv/messaging')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    credentials = directory / 'credentials.json'
    if credentials.exists():
        values = json.loads(credentials.read_text())
        if set(values) != {'rabbit', 'mongo_root', 'mongo_app'} or any(not re.fullmatch(r'[a-f0-9]{64}', value) for value in values.values()):
            raise RuntimeError('Invalid messaging credentials; restore their backup')
    else:
        # Existing persistent broker/database data must never be silently given new passwords.
        for volume in ('mmiv-rabbitmq-data', 'mmiv-reporting-data'):
            current = command('docker', 'volume', 'inspect', volume, check=False)
            if current.returncode == 0:
                raise RuntimeError('Messaging data exists without credentials; restore the host secret backup')
            if 'no such volume' not in current.stderr.lower():
                raise RuntimeError('Could not inspect messaging volume')
        values = {key: secrets.token_hex(32) for key in ('rabbit', 'mongo_root', 'mongo_app')}
        atomic_write(credentials, json.dumps(values))
    files = {
        'rabbit-client.env': 'SPRING_RABBITMQ_USERNAME=mmiv\nSPRING_RABBITMQ_PASSWORD=' + values['rabbit'] + '\n',
        'rabbit-server.env': 'RABBITMQ_DEFAULT_USER=mmiv\nRABBITMQ_DEFAULT_PASS=' + values['rabbit'] + '\n',
        'mongo-server.env': 'MONGO_INITDB_ROOT_USERNAME=mmiv_admin\nMONGO_INITDB_ROOT_PASSWORD=' + values['mongo_root'] + '\nMMIV_REPORTING_PASSWORD=' + values['mongo_app'] + '\n',
        'reporting.env': 'MONGODB_URI=mongodb://mmiv_reporting:' + values['mongo_app'] + '@mongodb:27017/mmiv_reporting?authSource=mmiv_reporting\n',
        'initialize-reporting.js': "db.getSiblingDB('mmiv_reporting').createUser({user:'mmiv_reporting',pwd:process.env.MMIV_REPORTING_PASSWORD,roles:[{role:'readWrite',db:'mmiv_reporting'}]});\n"}
    for name, content in files.items():
        target = directory / name
        if not target.exists() or target.read_text() != content:
            atomic_write(target, content)
        if name.endswith(".js"):
            target.chmod(0o644)
    for name in ('service-registry','api-gateway','transfer-service','integrity-service','user-access-service','reporting-service','discovery-service'):
        Path('/var/lib/mmiv/event-outbox/' + name).mkdir(parents=True, exist_ok=True, mode=0o700)


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
        service['mem_limit'] = '384m'
        service['memswap_limit'] = '384m'
        service['environment'].update({
            'JAVA_TOOL_OPTIONS': '-Xms32m -Xmx192m -XX:MaxDirectMemorySize=64m',
            'SERVER_TOMCAT_THREADS_MAX': '16', 'SERVER_TOMCAT_THREADS_MIN_SPARE': '2',
            'SPRING_RABBITMQ_HOST': 'rabbitmq', 'MMIV_EVENTS_ENABLED': 'true',
            'MMIV_EVENTS_OUTBOX_DIRECTORY': '/var/lib/mmiv/event-outbox',
            'USER_ACCESS_JWT_ISSUER': state.get('USER_ACCESS_JWT_ISSUER', 'https://auth.mmiv.example'),
            'USER_ACCESS_JWK_SET_URI': 'http://user-access-service:8083/.well-known/jwks.json',
            'METADATA_URL': 'http://discovery-service:8085', 'USER_ACCESS_URL': 'http://user-access-service:8083',
            'TRANSFER_URL': 'http://transfer-service:8081'})
        service['env_file'] = [{'path': '/etc/mmiv/messaging/rabbit-client.env', 'format': 'raw'}]
        service['volumes'].append({'type': 'bind', 'source': '/var/lib/mmiv/event-outbox/' + name,
                                  'target': '/var/lib/mmiv/event-outbox'})
        if name == 'discovery-service':
            config_id = state.get('USER_ACCESS_CONFIG_ID', '')
            if not re.fullmatch(r'[a-f0-9]{64}', config_id):
                raise ValueError('Metadata requires the staged Oracle configuration')
            service['env_file'].append({'path': '/etc/mmiv/user-access/versions/' + config_id + '/environment.env', 'format': 'raw'})
            service['environment'].update({'DISCOVERY_ROOT': '/media',
                                          'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '3',
                                          'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE': '1'})
        if name == 'reporting-service':
            service['env_file'].append({'path': '/etc/mmiv/messaging/reporting.env', 'format': 'raw'})
        if name == 'transfer-service':
            service['environment']['USER_ACCESS_JWT_ISSUER'] = state.get('USER_ACCESS_JWT_ISSUER', 'https://auth.mmiv.example')
            service['environment']['USER_ACCESS_JWK_SET_URI'] = 'http://user-access-service:8083/.well-known/jwks.json'
        if name == 'user-access-service':
            service['mem_limit'] = '512m'
            service['memswap_limit'] = '512m'
            service['environment'].update({
                'JAVA_TOOL_OPTIONS': '-Xms64m -Xmx256m -XX:MaxDirectMemorySize=64m',
                'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '4',
                'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE': '1',
                'SERVER_TOMCAT_THREADS_MAX': '24',
                'SERVER_TOMCAT_THREADS_MIN_SPARE': '2'})
            config_id = state.get('USER_ACCESS_CONFIG_ID', '')
            if not re.fullmatch(r'[a-f0-9]{64}', config_id):
                raise ValueError('User Access requires a staged immutable configuration bundle')
            directory = '/etc/mmiv/user-access/versions/' + config_id
            if state.get('MANAGED_USER_ACCESS_ORACLE'):
                service['env_file'].append({'path': '/etc/mmiv/managed-oracle/bootstrap-admin.env', 'format': 'raw'})
            service['env_file'].append({'path': directory + '/environment.env', 'format': 'raw'})
            service['volumes'].extend([
                {'type': 'bind', 'source': directory + '/private.pem',
                 'target': '/run/mmiv-jwt/private.pem', 'read_only': True},
                {'type': 'bind', 'source': directory + '/public.pem',
                 'target': '/run/mmiv-jwt/public.pem', 'read_only': True}])
        if name == 'integrity-service':
            service['env_file'].append({'path': ENVIRONMENT, 'format': 'raw'})
        service['healthcheck'] = {'test': ['CMD-SHELL',
            'wget -q --spider http://127.0.0.1:' + str(port) + '/actuator/health'],
            'interval': '10s', 'timeout': '5s', 'retries': 60, 'start_period': '60s'}
        dependencies = {'rabbitmq': {'condition': 'service_healthy'}}
        if name == 'reporting-service':
            dependencies['mongodb'] = {'condition': 'service_healthy'}
        if name != 'service-registry' and 'service-registry' in state['services']:
            dependencies['service-registry'] = {'condition': 'service_healthy'}
        if name in ('user-access-service', 'discovery-service') and state.get('MANAGED_USER_ACCESS_ORACLE'):
            dependencies['oracle'] = {'condition': 'service_healthy'}
        if name == 'api-gateway':
            dependencies.update({n: {'condition': 'service_healthy'} for n in state['services']
                                 if n != 'api-gateway'})
        if dependencies:
            service['depends_on'] = dependencies
        services[name] = service
    if state.get('MANAGED_USER_ACCESS_ORACLE') and 'user-access-service' in services:
        services['oracle'] = {'image': ORACLE_IMAGE, 'container_name': 'oracle',
            'labels': {'mmiv.oracle-startup-sha256': hashlib.sha256(ORACLE_STARTUP.encode()).hexdigest()},
            'restart': 'unless-stopped', 'networks': ['backend'], 'shm_size': '1g',
            'mem_limit': '3g', 'memswap_limit': '3g',
            'logging': {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}},
            'env_file': [{'path': '/etc/mmiv/managed-oracle/oracle.env', 'format': 'raw'}],
            'volumes': [{'type': 'volume', 'source': 'oracle-data', 'target': '/opt/oracle/oradata'}],
            'healthcheck': {'test': ['CMD-SHELL', 'healthcheck.sh && test -f /opt/oracle/oradata/mmiv-app-user-ready'],
                           'interval': '10s', 'timeout': '5s', 'retries': 90, 'start_period': '180s'}}
        # Validate the application login and repair a missing schema after an
        # interrupted initialization, before declaring the database ready.
        hook = Path('/etc/mmiv/managed-oracle/99-mmiv-ready.sh')
        if not hook.exists() or hook.read_text() != ORACLE_STARTUP:
            atomic_write(hook, ORACLE_STARTUP)
        hook.chmod(0o644)
        services['oracle']['volumes'].append({'type': 'bind', 'source': str(hook),
            'target': '/container-entrypoint-startdb.d/99-mmiv-ready.sh', 'read_only': True})
    services['rabbitmq'] = {'image': 'rabbitmq:4.1.4-management-alpine@sha256:5cbd7145b0306399ad68422c3350b6cbd1bb95704b39f5896480e5b6d4238a04', 'container_name': 'rabbitmq',
        'hostname': 'mmiv-rabbitmq', 'restart': 'unless-stopped', 'networks': ['backend'],
        'mem_limit': '384m', 'memswap_limit': '384m',
        'env_file': [{'path': '/etc/mmiv/messaging/rabbit-server.env', 'format': 'raw'}],
        'environment': {'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2'},
        'volumes': [{'type': 'volume', 'source': 'rabbit-data', 'target': '/var/lib/rabbitmq'}],
        'healthcheck': {'test': ['CMD', 'rabbitmq-diagnostics', '-q', 'ping'], 'interval': '10s', 'timeout': '5s', 'retries': 30, 'start_period': '30s'}}
    services['mongodb'] = {'image': 'mongo:8.0.14-noble@sha256:877fa303326645cd0e50a3833fce2f3c03d6eb4aac82c97a02e98879f51126d3', 'container_name': 'mongodb', 'restart': 'unless-stopped',
        'networks': ['backend'], 'mem_limit': '512m', 'memswap_limit': '512m',
        'command': ['mongod', '--wiredTigerCacheSizeGB', '0.25'],
        'env_file': [{'path': '/etc/mmiv/messaging/mongo-server.env', 'format': 'raw'}],
        'volumes': [{'type': 'volume', 'source': 'mongo-data', 'target': '/data/db'},
                    {'type': 'bind', 'source': '/etc/mmiv/messaging/initialize-reporting.js',
                     'target': '/docker-entrypoint-initdb.d/initialize-reporting.js', 'read_only': True}],
        'healthcheck': {'test': ['CMD-SHELL', "mongosh --quiet --username \"$$MONGO_INITDB_ROOT_USERNAME\" --password \"$$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin --eval 'quit(db.adminCommand({ping:1}).ok ? 0 : 1)'"],
                        'interval': '10s', 'timeout': '5s', 'retries': 30, 'start_period': '30s'}}
    config = {'services': services, 'networks': {'backend': {'external': True, 'name': 'mmiv-backend'}}}
    config['volumes'] = {'rabbit-data': {'name': 'mmiv-rabbitmq-data'}, 'mongo-data': {'name': 'mmiv-reporting-data'}}
    if 'oracle' in services:
        config['volumes']['oracle-data'] = {'external': True, 'name': ORACLE_VOLUME}
    return config


def ensure_images(state, images):
    missing = sorted({image for image in images
                      if command('docker', 'image', 'inspect', image, check=False).returncode})
    if any(image.startswith(state['ECR_REGISTRY'] + '/') for image in missing):
        password = command('aws', 'ecr', 'get-login-password', '--region', state['AWS_REGION']).stdout
        subprocess.run(['docker', 'login', '--username', 'AWS', '--password-stdin', state['ECR_REGISTRY']],
                       input=password, text=True, capture_output=True, check=True, timeout=60)
    if missing:
        # Bound network/disk pressure while avoiding sequential image downloads.
        with ThreadPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(command, 'docker', 'pull', image, timeout=600) for image in missing]
            for future in futures:
                future.result()


def compose_config(path):
    return json.loads(command('docker', 'compose', '-f', str(path), 'config',
                              '--no-env-resolution', '--format', 'json').stdout)


def up(compose, names, force=(), dependencies=True):
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
            'up', '-d', '--no-build', '--pull', 'never']
    # Forward deployment uses one invocation; Compose enforces health dependencies.
    # Recovery of a single service must not start unrelated dependencies.
    if not dependencies:
        base.append('--no-deps')
    if force:
        base.append('--force-recreate')
    command(*base, *names, timeout=1800)


def ready(name, state):
    item = state['services'][name]
    healthy(item['port'])
    if name != 'service-registry':
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
            up(compose, [name], force, dependencies=False)
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
    if 'user-access-service' in state['services']:
        if state.get('MANAGED_USER_ACCESS_ORACLE'):
            state = stage('initialize_user_access', managed_access, state)
            bundle = {'config_id': state['USER_ACCESS_CONFIG_ID']}
        else:
            bundle = json.loads(access_staged_path(state).read_text())
        state = {**state, 'USER_ACCESS_CONFIG_ID': bundle['config_id']}
        if not re.fullmatch(r'[a-f0-9]{64}', state['USER_ACCESS_CONFIG_ID']):
            raise ValueError('Invalid User Access configuration bundle identity')
        for filename in ('environment.env', 'private.pem', 'public.pem'):
            if not (Path('/etc/mmiv/user-access/versions/' + bundle['config_id']) / filename).is_file():
                raise RuntimeError('User Access configuration bundle is incomplete')
    stage('initialize_messaging', managed_messaging)
    desired = stage('build_compose_config', config_for, state)
    compose_existed = compose.exists()
    previous = json.loads(compose.read_text()) if compose_existed else {'services': {}, 'networks': desired['networks']}
    before = {name: stage('inspect_service_containers', inspect, name) for name in state['services']}
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
                           else environment.read_text() if environment.exists()
                           else '# Embedded H2 mode: no Oracle secrets required.\n')
    env_changed = ('integrity-service' in state['services'] and
                   (not environment.exists() or environment.read_text() != desired_environment))
    if 'integrity-service' in desired['services'] and env_changed:
        # Preserve Integrity's database mode. A config fingerprint lets Compose
        # detect env-file updates without a second force-recreate invocation.
        desired['services']['integrity-service']['labels'] = {
            'mmiv.environment': hashlib.sha256(desired_environment.encode()).hexdigest()}
    elif 'integrity-service' in desired['services'] and 'integrity-service' in previous['services']:
        labels = previous['services']['integrity-service'].get('labels')
        if labels:
            desired['services']['integrity-service']['labels'] = labels
    for infrastructure in ('rabbitmq', 'mongodb'):
        previous['services'].setdefault(infrastructure, desired['services'][infrastructure])
    previous['volumes'] = desired['volumes']
    if 'oracle' in desired['services']:
        current_oracle = inspect('oracle')
        if current_oracle and (current_oracle['Config'].get('Labels') or {}).get('com.docker.compose.project') != PROJECT:
            raise RuntimeError('Oracle container belongs to another owner')
        if current_oracle and 'oracle' not in previous['services']:
            raise RuntimeError('Oracle exists without its saved Compose configuration')
        # Database infrastructure survives application rollback, including first release failure.
        previous['services'].setdefault('oracle', desired['services']['oracle'])
        previous['volumes'] = desired['volumes']
    changes = {name: {'changed': not container or previous['services'].get(name) != desired['services'][name]
                      or (container['Config'].get('Labels') or {}).get('com.docker.compose.project') != PROJECT
                      or (name == 'integrity-service' and env_changed),
                      'previous': container is not None,
                      'previous_image': container['Config']['Image'] if container else None}
               for name, container in before.items()}
    # Finish every image download before saving backups or changing any running service.
    stage('download_images', ensure_images, state, [item['image'] for item in desired['services'].values()])
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
        if 'integrity-service' in state['services'] and env_changed:
            atomic_write(environment, desired_environment)
        names = list(state['services'])
        journal['touched'].extend(names)
        save(journal_path, journal)
        stage('compose_startup', up, compose, names + (['oracle'] if 'oracle' in desired['services'] else []))
        parallel(lambda name: stage('readiness_' + name, ready, name, state), names)
        result['READY'] = True
        save(directory / 'current-release.json', result)
        return result
    except Exception as error:
        result.update(failure_details(error, state))
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
                access_staged_path(state).unlink(missing_ok=True)


if __name__ == '__main__':
    state = json.loads(sys.argv[2])
    try:
        result = run(sys.argv[1], state)
    except Exception as error:
        result = {**state, 'READY': False, 'ROLLED_BACK': False, **failure_details(error, state)}
    print('MMIV_RELEASE_STATE=' + json.dumps(result))
    sys.exit(0 if (result.get('READY') if sys.argv[1] == 'deploy' else
                   'recovery_error' not in result and (result.get('ROLLED_BACK') or result.get('recovery', {}).get('release') == 'NOT_STARTED')) else 1)
