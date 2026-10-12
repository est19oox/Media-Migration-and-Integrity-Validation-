#!/usr/bin/env python3
"""Live integration: all seven built JARs, Oracle, RabbitMQ, MongoDB, and a local S3 HTTP fixture.
Requires Java 25, Docker, OpenSSL, and `mvn package` for every service. No AWS resources are used.
"""
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ['service-registry', 'user-access-service', 'discovery-service', 'integrity-service',
            'transfer-service', 'reporting-service', 'api-gateway']
IMAGES = {'oracle': os.environ.get('MMIV_ORACLE_TEST_IMAGE', 'gvenzl/oracle-free:23.26.3-slim@sha256:6d61d267a3b978c24c5ac1790e62e927416a0aec446bd86e4b3a1527562757bd'),
          'rabbit': 'rabbitmq:4.1.4-management-alpine@sha256:5cbd7145b0306399ad68422c3350b6cbd1bb95704b39f5896480e5b6d4238a04',
          'mongo': 'mongo:8.0.14-noble@sha256:877fa303326645cd0e50a3833fce2f3c03d6eb4aac82c97a02e98879f51126d3'}


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait(check, seconds=120):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except Exception as error:
            last = error
        time.sleep(1)
    raise AssertionError('Readiness/verification deadline expired') from last


def request(url, method='GET', body=None, token=None, headers=None):
    headers = dict(headers or {})
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


class S3Fixture(http.server.BaseHTTPRequestHandler):
    objects = {}
    def log_message(self, *args):
        pass
    def do_PUT(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if 'aws-chunked' in self.headers.get('Content-Encoding', ''):
            # S3 removes the AWS streaming chunk framing before storing object bytes.
            decoded = bytearray()
            offset = 0
            while True:
                end = body.index(b'\r\n', offset)
                size = int(body[offset:end].split(b';', 1)[0], 16)
                offset = end + 2
                if size == 0:
                    break
                decoded.extend(body[offset:offset + size])
                offset += size
                assert body[offset:offset + 2] == b'\r\n'
                offset += 2
            body = bytes(decoded)
            assert len(body) == int(self.headers['X-Amz-Decoded-Content-Length'])
        self.objects[urllib.parse.urlsplit(self.path).path] = (body, self.headers.get('Content-Type', 'application/octet-stream'))
        self.send_response(200)
        self.send_header('ETag', '"' + hashlib.md5(body).hexdigest() + '"')
        self.send_header('x-amz-checksum-sha256', base64.b64encode(hashlib.sha256(body).digest()).decode())
        self.send_header('Content-Length', '0')
        self.end_headers()
    def do_GET(self):
        self.read_object(False)
    def do_HEAD(self):
        self.read_object(True)
    def read_object(self, head):
        value = self.objects.get(urllib.parse.urlsplit(self.path).path)
        if value is None:
            self.send_error(404)
            return
        body, content_type = value
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Content-Type', content_type)
        self.send_header('ETag', '"' + hashlib.md5(body).hexdigest() + '"')
        self.end_headers()
        if not head:
            self.wfile.write(body)
    def do_DELETE(self):
        self.objects.pop(urllib.parse.urlsplit(self.path).path, None)
        self.send_response(204)
        self.end_headers()


def main():
    subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    java = os.environ.get('JAVA', 'java')
    for name in SERVICES:
        if not (ROOT / 'services' / name / 'target' / (name + '.jar')).exists():
            raise ValueError('Build JAR first: ' + name)
    containers, processes, outputs = [], [], []
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), S3Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    private = tempfile.TemporaryDirectory(prefix='mmiv-integration-')
    directory = Path(private.name)
    directory.chmod(0o700)
    artifacts = Path(os.environ.get('MMIV_INTEGRATION_REPORT_DIR', str(ROOT / '.ci/reports/integration')))
    artifacts.mkdir(parents=True, exist_ok=True)
    names = {service: 'mmiv-it-' + service + '-' + uuid.uuid4().hex[:8] for service in IMAGES}
    ports = {service: free_port() for service in SERVICES + ['oracle', 'rabbit', 'mongo']}
    secret = secrets.token_hex(24)
    def docker(*args, input=None):
        return subprocess.run(['docker', *args], input=input, capture_output=True, text=True, check=True, timeout=90).stdout
    def run_container(service, port, env):
        path = directory / (service + '.env')
        path.write_text('\n'.join(k + '=' + v for k, v in env.items()) + '\n')
        path.chmod(0o600)
        docker('run', '-d', '--name', names[service], '-p', f'127.0.0.1:{ports[service]}:{port}',
               '--env-file', str(path), IMAGES[service])
        containers.append(names[service])
    def sql(value):
        shell = 'exec sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost:1521/FREEPDB1"'
        return docker('exec', '-i', names['oracle'], 'bash', '-c', shell,
                      input='WHENEVER SQLERROR EXIT FAILURE ROLLBACK\nSET HEADING OFF FEEDBACK OFF\n' + value + '\nEXIT\n')
    def mongo(value):
        return docker('exec', names['mongo'], 'mongosh', '--quiet', '--eval', value).strip()
    try:
        run_container('oracle', 1521, {'ORACLE_PASSWORD': secret, 'APP_USER': 'USER_ACCESS', 'APP_USER_PASSWORD': secret})
        run_container('rabbit', 5672, {'RABBITMQ_DEFAULT_USER': 'mmiv', 'RABBITMQ_DEFAULT_PASS': secret,
                                     'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2'})
        run_container('mongo', 27017, {})  # Isolated test database bound only to loopback; removed in finally.
        wait(lambda: docker('exec', names['oracle'], 'healthcheck.sh') is not None, 240)
        wait(lambda: docker('exec', names['rabbit'], 'rabbitmq-diagnostics', '-q', 'ping'))
        wait(lambda: mongo('db.adminCommand({ping:1}).ok'))
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                        '-out', str(directory / 'private.pem')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['openssl', 'pkey', '-in', str(directory / 'private.pem'), '-pubout',
                        '-out', str(directory / 'public.pem')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (directory / 'media').mkdir()
        (directory / 'media/test.txt').write_text('MMIV source bytes\n')
        env = dict(os.environ)
        env.update({'AWS_ACCESS_KEY_ID': 'local-fixture', 'AWS_SECRET_ACCESS_KEY': 'local-fixture', 'AWS_EC2_METADATA_DISABLED': 'true',
                    'AWS_REGION': 'ap-southeast-1', 'MMIV_BUCKET_NAME': 'it-landing', 'MMIV_S3_ENDPOINT': f'http://127.0.0.1:{server.server_port}',
                    'ORACLE_JDBC_URL': f'jdbc:oracle:thin:@//127.0.0.1:{ports["oracle"]}/FREEPDB1',
                    'ORACLE_USER': 'USER_ACCESS', 'ORACLE_PASSWORD': secret, 'JWT_ISSUER': 'https://auth.mmiv.integration',
                    'USER_ACCESS_JWT_ISSUER': 'https://auth.mmiv.integration', 'DISCOVERY_ROOT': str(directory / 'media'),
                    'MMIV_MEDIA_ROOT': str(directory / 'media'), 'JWT_PRIVATE_KEY_PATH': 'file:' + str(directory / 'private.pem'),
                    'JWT_PUBLIC_KEY_PATH': 'file:' + str(directory / 'public.pem'),
                    'USER_ACCESS_URL': f'http://127.0.0.1:{ports["user-access-service"]}',
                    'USER_ACCESS_JWK_SET_URI': f'http://127.0.0.1:{ports["user-access-service"]}/.well-known/jwks.json',
                    'METADATA_URL': f'http://127.0.0.1:{ports["discovery-service"]}',
                    'TRANSFER_URL': f'http://127.0.0.1:{ports["transfer-service"]}',
                    'EUREKA_DEFAULT_ZONE': f'http://127.0.0.1:{ports["service-registry"]}/eureka/',
                    'EUREKA_INSTANCE_IP_ADDRESS': '127.0.0.1', 'EUREKA_INSTANCE_PREFER_IP_ADDRESS': 'true',
                    'SPRING_RABBITMQ_HOST': '127.0.0.1', 'SPRING_RABBITMQ_PORT': str(ports['rabbit']),
                    'SPRING_RABBITMQ_USERNAME': 'mmiv', 'SPRING_RABBITMQ_PASSWORD': secret,
                    'MONGODB_URI': f'mongodb://127.0.0.1:{ports["mongo"]}/mmiv_reporting',
                    'JAVA_TOOL_OPTIONS': '-Xms32m -Xmx256m -XX:MaxDirectMemorySize=64m',
                    'MMIV_EVENTS_ENABLED': 'true'})
        for name in SERVICES:
            output = (directory / (name + '.log')).open('w')
            outputs.append(output)
            process = subprocess.Popen([java, '-jar', str(ROOT / 'services' / name / 'target' / (name + '.jar'))],
                    env={**env, 'SERVER_PORT': str(ports[name]), 'MMIV_EVENTS_OUTBOX_DIRECTORY': str(directory / 'outbox' / name)},
                    stdout=output, stderr=subprocess.STDOUT)
            processes.append(process)
            wait(lambda: request(f'http://127.0.0.1:{ports[name]}/actuator/health')[0] == 200, 150)
            print('READY ' + name, flush=True)
        gateway = f'http://127.0.0.1:{ports["api-gateway"]}'
        native = f'http://127.0.0.1:{ports["user-access-service"]}'
        auth = {'username': 'mmiv_ci_' + uuid.uuid4().hex, 'password': secrets.token_urlsafe(24)}
        status, body = request(native + '/api/auth/signup', 'POST', auth)
        assert status == 201, (status, body)
        user_id = json.loads(body)['userId']
        status, body = request(native + '/api/auth/login', 'POST', auth)
        assert status == 200
        token = json.loads(body)['accessToken']
        # Wait for gateway discovery cache, then test no grant, arbitrary key rejection, and foreign owner.
        wait(lambda: request(gateway + '/user-access/.well-known/jwks.json')[0] == 200, 120)
        wait(lambda: request(gateway + '/api/v1/transfers', 'POST', {})[0] == 401, 120)
        wait(lambda: request(gateway + '/api/reports/00000000-0000-0000-0000-000000000000')[0] == 401, 120)
        plan_request = {'destinationId': 'it-landing', 'sourcePath': 'test.txt'}
        assert request(gateway + '/api/metadata/plans', 'POST', plan_request)[0] == 401
        status,body=request(gateway + '/api/metadata/plans', 'POST', plan_request, token)
        assert status == 403, (status,body)
        status,body=request(gateway + '/api/v1/transfers', 'POST', {'sourcePath': 'test.txt', 'destinationKey': 'evil'}, token)
        assert status == 400, (status,body)
        sql(f"INSERT INTO USER_DESTINATION_ACCESS VALUES ('{user_id}','it-landing','WRITE');\nCOMMIT;")
        status, body = request(gateway + '/api/metadata/plans', 'POST', plan_request, token)
        assert status == 200, (status, body)
        plan = json.loads(body)
        status, body = request(gateway + '/api/metadata/plans/' + plan['metadataId'] + '/transfer', 'POST', {}, token)
        assert status == 200, (status, body)
        result = json.loads(body)
        assert result['success'] is True, result
        assert result['key'] == plan['destinationKey']
        assert result['sha256'] == hashlib.sha256((directory / 'media/test.txt').read_bytes()).hexdigest()
        assert '/it-landing/' + plan['destinationKey'] in S3Fixture.objects
        status, body = request(gateway + '/api/v1/files/' + result['fileId'] + '/content', token=token)
        assert status == 200 and body == (directory / 'media/test.txt').read_bytes()
        # Reporting must project the v2 producers' events end-to-end.
        def completed_report():
            status, body = request(gateway + '/api/reports/' + plan['migrationId'], token=token)
            if status != 200:
                return False
            report = json.loads(body)
            return report if report.get('status') == 'COMPLETED' else False
        report = wait(completed_report, 90)
        print('PASS metadata -> transfer -> integrity -> RabbitMQ -> report', flush=True)
        sql(f"UPDATE USER_DESTINATION_ACCESS SET PERMISSION='READ' WHERE USER_ID='{user_id}';\nCOMMIT;")
        assert request(gateway + '/api/v1/transfers', 'POST', {'metadataId': plan['metadataId']}, token)[0] == 403
        sql(f"DELETE FROM USER_DESTINATION_ACCESS WHERE USER_ID='{user_id}';\nCOMMIT;")
        assert request(gateway + '/api/v1/files/' + result['fileId'] + '/content', token=token)[0] == 403
        assert request(gateway + '/api/reports/' + plan['migrationId'], token=token)[0] == 403
        print('PASS permission revocation applies to existing JWTs', flush=True)
        # Every service emits lifecycle + request audit, and broker outage preserves pending events.
        def source_count():
            return int(mongo("db.getSiblingDB('mmiv_reporting').service_logs.distinct('sourceService').length")) == len(SERVICES)
        wait(source_count, 90)
        outbox = directory / 'outbox/user-access-service'
        previous_ids = {file.stem for file in outbox.glob('*.json')}
        docker('stop', names['rabbit'])
        assert request(native + '/.well-known/jwks.json')[0] == 200
        def new_http_event():
            for file in outbox.glob('*.json'):
                if file.stem not in previous_ids and json.loads(file.read_text())['event']['operation'] == 'HTTP_REQUEST':
                    return file.stem
            return False
        pending_id = wait(new_http_event, 30)
        docker('start', names['rabbit'])
        wait(lambda: mongo("db.getSiblingDB('mmiv_reporting').service_logs.countDocuments({_id:'" + pending_id + "'})") == '1', 90)
        duplicates = mongo("db.getSiblingDB('mmiv_reporting').service_logs.aggregate([{$group:{_id:'$eventId',n:{$sum:1}}},{$match:{n:{$gt:1}}}]).toArray().length")
        assert duplicates == '0'
        print('PASS all seven service logs and RabbitMQ outage recovery', flush=True)
        assert request(native + '/api/auth/verification-account', 'DELETE', token=token)[0] == 204
        assert request(native + '/api/auth/login', 'POST', auth)[0] == 401
        print('PASS generated account deleted after tests', flush=True)
        maven = os.environ.get('MMIV_ORACLE_MAVEN')
        if maven:
            test_env = {**env, 'DB_URL': env['ORACLE_JDBC_URL'], 'DB_USERNAME': 'USER_ACCESS',
                        'DB_PASSWORD': secret, 'RUN_ORACLE_IT': 'true', 'EUREKA_CLIENT_ENABLED': 'false',
                        'MMIV_EVENTS_ENABLED': 'false', 'MANAGEMENT_HEALTH_RABBIT_ENABLED': 'false',
                        'JWT_ISSUER': 'https://auth.mmiv.example', 'USER_ACCESS_JWT_ISSUER': 'https://auth.mmiv.example'}
            for name, options in [('user-access-service', ['-Poracle-it', 'verify']), ('discovery-service', ['test'])]:
                with (artifacts / (name + '-oracle-tests.log')).open('w') as log:
                    subprocess.run([maven, '-q', '-f', str(ROOT / 'services' / name / 'pom.xml'), *options],
                                   env=test_env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
            print('PASS User Access and Discovery Oracle integration suites', flush=True)
        (artifacts / 'summary.json').write_text(json.dumps({'passed': True, 'services': SERVICES, 'reportStatus': report['status'],
                                                         'checks': ['permission-denial', 'assigned-destination', 'integrity', 'report', 'revocation', 'all-service-logs', 'broker-recovery', 'account-cleanup']}, indent=2) + '\n')
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for output in outputs:
            output.close()
        for name in SERVICES:
            if (directory / (name + '.log')).exists():
                # Keep only sanitized startup evidence, never synthetic secret or JWT values.
                content = (directory / (name + '.log')).read_text().replace(secret, '[redacted]')
                (artifacts / (name + '.log')).write_text(content)
        for name in reversed(containers):
            subprocess.run(['docker', 'rm', '-fv', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server.shutdown()
        private.cleanup()


if __name__ == '__main__':
    main()
