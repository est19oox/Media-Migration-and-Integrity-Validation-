#!/usr/bin/env python3
"""Live integration: all seven built JARs, Oracle, RabbitMQ, MongoDB, and a local S3 HTTP fixture.
Requires Java 25, Docker, OpenSSL, and `mvn package` for every service. No AWS resources are used.
"""
import base64
import hashlib
import http.server
import http.cookiejar
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
          'redis': 'redis:7.4.2-alpine@sha256:02419de7eddf55aa5bcf49efb74e88fa8d931b4d77c07eff8a6b2144472b6952',
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
        self.objects[urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)] = (body, self.headers.get('Content-Type', 'application/octet-stream'))
        self.send_response(200)
        self.send_header('ETag', '"' + hashlib.md5(body).hexdigest() + '"')
        self.send_header('x-amz-checksum-sha256', base64.b64encode(hashlib.sha256(body).digest()).decode())
        self.send_header('Content-Length', '0')
        self.end_headers()
    def do_GET(self):
        parsed=urllib.parse.urlsplit(self.path)
        query=urllib.parse.parse_qs(parsed.query,keep_blank_values=True)
        if query.get('list-type')==['2']:
            from xml.sax.saxutils import escape
            bucket=parsed.path.strip('/');prefix=query.get('prefix',[''])[0];entries=[]
            for path,(content,_) in self.objects.items():
                if path.startswith('/'+bucket+'/'+prefix):
                    key=path[len(bucket)+2:];entries.append('<Contents><Key>'+escape(key)+'</Key><LastModified>2026-10-12T00:00:00Z</LastModified><ETag>fixture</ETag><Size>'+str(len(content))+'</Size><StorageClass>STANDARD</StorageClass></Contents>')
            body=('<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>'+escape(bucket)+'</Name><Prefix>'+escape(prefix)+'</Prefix><IsTruncated>false</IsTruncated>'+''.join(entries)+'</ListBucketResult>').encode()
            self.send_response(200);self.send_header('Content-Type','application/xml');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        if 'versions' in query:
            from xml.sax.saxutils import escape
            bucket=parsed.path.strip('/')
            prefix=query.get('prefix',[''])[0]
            entries=[]
            for path,(body,_) in self.objects.items():
                if path.startswith('/'+bucket+'/'):
                    key=path[len(bucket)+2:]
                    if key.startswith(prefix): entries.append('<Version><Key>'+escape(key)+'</Key><VersionId>null</VersionId><IsLatest>true</IsLatest><LastModified>2026-10-12T00:00:00Z</LastModified><ETag>fixture</ETag><Size>'+str(len(body))+'</Size><StorageClass>STANDARD</StorageClass></Version>')
            body=('<ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>'+escape(bucket)+'</Name><Prefix>'+escape(prefix)+'</Prefix><IsTruncated>false</IsTruncated>'+''.join(entries)+'</ListVersionsResult>').encode()
            self.send_response(200);self.send_header('Content-Type','application/xml');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        self.read_object(False)
    def do_HEAD(self):
        self.read_object(True)
    def read_object(self, head):
        value = self.objects.get(urllib.parse.unquote(urllib.parse.urlsplit(self.path).path))
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
    def do_POST(self):
        import xml.etree.ElementTree as ET
        root=ET.fromstring(self.rfile.read(int(self.headers['Content-Length'])))
        bucket=urllib.parse.unquote(urllib.parse.urlsplit(self.path).path).strip('/')
        for item in root.findall('{*}Object'):
            key=item.findtext('{*}Key');self.objects.pop('/'+bucket+'/'+key,None)
        body=b'<DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"/>'
        self.send_response(200);self.send_header('Content-Type','application/xml');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_DELETE(self):
        self.objects.pop(urllib.parse.unquote(urllib.parse.urlsplit(self.path).path), None)
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
    jars=directory/'jars';jars.mkdir()
    # Keep the running classpaths stable if a developer rebuilds target/ during this test.
    for name in SERVICES:
        shutil.copy2(ROOT/'services'/name/'target'/(name+'.jar'),jars/(name+'.jar'))
    artifacts = Path(os.environ.get('MMIV_INTEGRATION_REPORT_DIR', str(ROOT / '.ci/reports/integration')))
    artifacts.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(__file__, artifacts / 'test-service-stack-source.py')
    names = {service: 'mmiv-it-' + service + '-' + uuid.uuid4().hex[:8] for service in IMAGES}
    ports = {service: free_port() for service in SERVICES + ['oracle', 'rabbit', 'mongo', 'redis']}
    secret = secrets.token_hex(24)
    def docker(*args, input=None):
        return subprocess.run(['docker', *args], input=input, capture_output=True, text=True, check=True, timeout=90).stdout
    def run_container(service, port, env):
        path = directory / (service + '.env')
        path.write_text('\n'.join(k + '=' + v for k, v in env.items()) + '\n')
        path.chmod(0o600)
        docker('run', '-d', '--name', names[service], '-p', f'127.0.0.1:{ports[service]}:{port}',
               '--env-file', str(path), '--ulimit', 'core=0', IMAGES[service],
               *(['mongod', '--wiredTigerCacheSizeGB', '0.25'] if service == 'mongo' else []))
        containers.append(names[service])
    def sql(value):
        shell = 'exec sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost:1521/FREEPDB1"'
        return docker('exec', '-i', names['oracle'], 'bash', '-c', shell,
                      input='WHENEVER SQLERROR EXIT FAILURE ROLLBACK\nSET HEADING OFF FEEDBACK OFF\n' + value + '\nEXIT\n')
    def mongo(value):
        return docker('exec', names['mongo'], 'mongosh', '--quiet', '--eval', value).strip()
    try:
        run_container('oracle', 1521, {'ORACLE_PASSWORD': secret, 'APP_USER': 'USER_ACCESS', 'APP_USER_PASSWORD': secret})
        wait(lambda: docker('exec', names['oracle'], 'healthcheck.sh') is not None, 240)
        # Reuse the application's unused-tail reclaim routine on this disposable DB
        # before starting other databases, to stay within development disk budgets.
        import importlib.util
        spec = importlib.util.spec_from_file_location('storage_fixture', ROOT / 'scripts/cd/docker-stack-remote.py')
        storage_fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(storage_fixture)
        docker('exec', '-i', names['oracle'], 'sqlplus', '-s', '/', 'as', 'sysdba',
               input='WHENEVER SQLERROR EXIT FAILURE\n' + storage_fixture.ORACLE_STORAGE_SQL + '\nEXIT;\n')
        run_container('rabbit', 5672, {'RABBITMQ_DEFAULT_USER': 'mmiv', 'RABBITMQ_DEFAULT_PASS': secret,
                                     'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2'})
        run_container('redis', 6379, {})
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
                    'REPORTING_URL': f'http://127.0.0.1:{ports["reporting-service"]}',
                    'EUREKA_DEFAULT_ZONE': f'http://127.0.0.1:{ports["service-registry"]}/eureka/',
                    'EUREKA_INSTANCE_IP_ADDRESS': '127.0.0.1', 'EUREKA_INSTANCE_PREFER_IP_ADDRESS': 'true',
                    # Disposable services start together; shorten discovery caches for readiness checks.
                    'EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS': '2',
                    'EUREKA_CLIENT_INITIAL_INSTANCE_INFO_REPLICATION_INTERVAL_SECONDS': '1',
                    'EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS': '1000',
                    'SPRING_CLOUD_LOADBALANCER_CACHE_TTL': '1s',
                    'SPRING_RABBITMQ_HOST': '127.0.0.1', 'SPRING_RABBITMQ_PORT': str(ports['rabbit']),
                    'SPRING_RABBITMQ_USERNAME': 'mmiv', 'SPRING_RABBITMQ_PASSWORD': secret,
                    'MONGODB_URI': f'mongodb://127.0.0.1:{ports["mongo"]}/mmiv_reporting',
                    'SPRING_DATA_REDIS_HOST': '127.0.0.1', 'SPRING_DATA_REDIS_PORT': str(ports['redis']),
                    'MMIV_RATE_LIMIT_AUTH_PER_MINUTE': '1000', 'MMIV_RATE_LIMIT_API_PER_MINUTE': '100000',
                    'MMIV_RATE_LIMIT_UPLOADS_PER_MINUTE': '10000',
                    'JAVA_TOOL_OPTIONS': '-Xms32m -Xmx256m -XX:MaxDirectMemorySize=64m',
                    'MMIV_EVENTS_ENABLED': 'true', 'MMIV_BOOTSTRAP_ADMIN_ENABLED': 'true',
                    'MMIV_BOOTSTRAP_ADMIN_USERNAME': 'admin', 'MMIV_BOOTSTRAP_ADMIN_PASSWORD': secret})
        for name in SERVICES:
            output = (directory / (name + '.log')).open('w')
            outputs.append(output)
            process = subprocess.Popen([java, '-jar', str(jars / (name + '.jar'))],
                    env={**env, 'SERVER_PORT': str(ports[name]), 'MMIV_EVENTS_OUTBOX_DIRECTORY': str(directory / 'outbox' / name)},
                    stdout=output, stderr=subprocess.STDOUT)
            processes.append(process)
            if name == 'service-registry':
                wait(lambda: request(f'http://127.0.0.1:{ports[name]}/actuator/health')[0] == 200, 150)
        # The applications retry registry/broker connections; wait for functional readiness below.
        for name in SERVICES:
            wait(lambda: request(f'http://127.0.0.1:{ports[name]}/actuator/health')[0] == 200, 150)
            print('READY ' + name, flush=True)
        if os.environ.get('MMIV_PROMETHEUS_BINARY'):
            monitoring = {'global': {'scrape_interval': '1s'}, 'scrape_configs': [
                {'job_name': name, 'metrics_path': '/actuator/prometheus',
                 'static_configs': [{'targets': [f'127.0.0.1:{ports[name]}']} ]}
                for name in SERVICES if name != 'api-gateway']}
            config = directory / 'prometheus.json'
            config.write_text(json.dumps(monitoring))
            monitoring_port = free_port()
            output = (directory / 'prometheus.log').open('w'); outputs.append(output)
            processes.append(subprocess.Popen([os.environ['MMIV_PROMETHEUS_BINARY'],
                '--config.file=' + str(config), '--storage.tsdb.path=' + str(directory / 'metrics'),
                '--web.listen-address=127.0.0.1:' + str(monitoring_port)],
                stdout=output, stderr=subprocess.STDOUT))
            query = f'http://127.0.0.1:{monitoring_port}/api/v1/query?query=sum(up)'
            def scraped():
                status, body = request(query)
                result = json.loads(body).get('data', {}).get('result', [])
                return status == 200 and result and float(result[0]['value'][1]) == len(SERVICES) - 1
            wait(scraped, 60)
            (artifacts / 'prometheus-query.json').write_bytes(request(query)[1])
            print('PASS Prometheus scrapes all six private backend services', flush=True)
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
        wait(lambda: request(gateway + '/user-access/.well-known/jwks.json', token=token)[0] == 200, 120)
        wait(lambda: request(gateway + '/api/v1/transfers', 'POST', {})[0] == 401, 120)
        wait(lambda: request(gateway + '/api/reports/00000000-0000-0000-0000-000000000000')[0] == 401, 120)
        wait(lambda: request(gateway + '/api/integrity/validate', 'POST', {})[0] == 401, 120)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        opener = urllib.request.build_opener(NoRedirect())
        for base in (gateway,):
            try:
                opener.open(base + '/upload.html', timeout=30)
                raise AssertionError('Anonymous upload page must redirect')
            except urllib.error.HTTPError as response:
                assert response.code == 302, response.code
                assert response.headers.get('Location') == ('/pages/login.html' if base == gateway else '/login.html'), response.headers.get('Location')
        print('PASS Gateway login redirects', flush=True)
        admin_api = gateway + '/user-access/api/admin'
        assert request(admin_api + '/me')[0] == 401
        assert request(admin_api + '/me', token=token)[0] == 403
        status, body = request(native + '/api/auth/login', 'POST', {'username': 'admin', 'password': secret})
        assert status == 200, (status, body)
        admin_token = json.loads(body)['accessToken']
        status, body = request(admin_api + '/me', token=admin_token)
        assert status == 200 and json.loads(body)['destination'] == 'it-landing', (status, body)
        external_auth = {'username': 'outside_' + uuid.uuid4().hex[:12], 'password': secrets.token_hex(16)}
        status, body = request(native + '/api/auth/signup', 'POST', external_auth)
        assert status == 201
        external_id = json.loads(body)['userId']
        status, body = request(native + '/api/auth/login', 'POST', external_auth)
        external_token = json.loads(body)['accessToken']
        status, body = request(admin_api + '/invitations', 'POST', {'username': external_auth['username']}, admin_token)
        assert status == 200, (status, body)
        invitation = json.loads(body)['token']
        assert request(admin_api + '/invitations/accept', 'POST', {'token': invitation}, token)[0] == 403
        assert request(admin_api + '/invitations/accept', 'POST', {'token': invitation}, external_token)[0] == 200
        assert request(admin_api + '/invitations/accept', 'POST', {'token': invitation}, external_token)[0] == 403
        assert request(admin_api + '/me', token=external_token)[0] == 200
        print('PASS default administrator password login and account-bound single-use delegation', flush=True)
        # Real cookie session: administrator never has to log in again on the permissions page.
        cookiejar=http.cookiejar.CookieJar()
        browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookiejar))
        def browser_request(path,method='GET',body=None,csrf=None):
            headers={}
            if path=='/api/v1/session' and method=='POST': headers['Authorization']='Bearer '+admin_token
            if csrf: headers[csrf['csrfHeader']]=csrf['csrfToken']
            payload=json.dumps(body).encode() if body is not None else None
            if payload: headers['Content-Type']='application/json'
            req=urllib.request.Request(gateway+path,data=payload,headers=headers,method=method)
            try:
                with browser.open(req,timeout=30) as response:return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,error.read()
        assert browser_request('/api/v1/session','POST')[0]==204
        csrf=json.loads(browser_request('/api/v1/session')[1])
        options=json.loads(browser_request('/api/v1/portal/admin/users')[1]);assert any(u['username']=='admin' for u in options)
        for page in ['/home.html','/admin.html','/files.html','/logs.html']:
            status,body=browser_request(page);assert status==200 and b'<html lang="en">' in body,(page,status)
        assert b'Administrator login' not in browser_request('/admin.html')[1]
        status,body=browser_request('/api/v1/portal/admin/folders','POST',{'name':'SW001'});assert status==403,(status,body)
        for folder in ['SW001','semester2']:
            status,body=browser_request('/api/v1/portal/admin/folders','POST',{'name':folder},csrf)
            if status==403 and json.loads(body).get('error')=='CSRF_INVALID':
                # Same recovery as auth.js: refresh and replay only a pre-controller CSRF rejection.
                csrf=json.loads(browser_request('/api/v1/session')[1])
                status,body=browser_request('/api/v1/portal/admin/folders','POST',{'name':folder},csrf)
            assert status==200,(status,body)
            assert '/it-landing/'+folder+'/' in S3Fixture.objects
        assert request(gateway+'/api/v1/portal/admin/folders','POST',{'name':'unauthorized'},token)[0]==403
        assert request(gateway+'/api/v1/portal/admin/folders','POST',{'name':'../escape'},admin_token)[0]==400
        profile=json.loads(request(gateway+'/api/access/me',token=token)[1]);assert profile['destinations']==[]
        assert request(gateway+'/api/v1/portal/admin/users',token=token)[0]==403
        status,body=request(gateway+'/api/dashboard',token=token);assert status==403,(status,body)
        assert request(gateway+'/api/metadata/allocations','POST',{'destinationId':'it-landing','fileName':'root.txt','sizeBytes':1},admin_token)[0]==400
        print('PASS shared cookie session, CSRF, administrator folder creation and hidden bucket-root access',flush=True)

        verification_run=str(uuid.uuid4())
        run_header={'X-MMIV-Verification-Run':verification_run}
        plan_request = {'destinationId': 'SW001', 'sourcePath': 'test.txt'}
        assert request(gateway + '/api/metadata/plans', 'POST', plan_request)[0] == 401
        status,body=request(gateway + '/api/metadata/plans', 'POST', plan_request, token, headers=run_header)
        assert status == 403, (status,body)
        status,body=request(gateway + '/api/v1/transfers', 'POST', {'sourcePath': 'test.txt', 'destinationKey': 'evil'}, token)
        assert status == 400, (status,body)
        status, body = request(admin_api + '/users/' + user_id + '/destinations', 'PUT',
                               {'destination': 'SW001', 'permission': 'WRITE'}, external_token)
        assert status == 200, (status, body)
        status, body = request(gateway + '/api/metadata/plans', 'POST', plan_request, token, headers=run_header)
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
        if os.environ.get('MMIV_LOAD_TEST') == 'true':
            from integrity_load import compare
            load_bytes = b'x' * (4 * 1024 * 1024)
            status, body = request(gateway + '/api/metadata/allocations', 'POST',
                {'destinationId': 'SW001', 'fileName': 'load-test.txt', 'sizeBytes': len(load_bytes)}, token, run_header)
            assert status == 200, (status, body)
            load_plan = json.loads(body)
            S3Fixture.objects['/it-landing/' + load_plan['destinationKey']] = (load_bytes, 'text/plain')
            load_headers = {'X-MMIV-Metadata-Id': load_plan['metadataId'],
                'X-MMIV-Migration-Id': load_plan['migrationId'], 'X-MMIV-File-Id': load_plan['fileId']}
            load_payload = {'bucket': 'it-landing', 'objectKey': load_plan['destinationKey'],
                'sourceChecksum': hashlib.sha256(load_bytes).hexdigest(), 'transferId': str(uuid.uuid4())}
            def start_replica():
                port = free_port()
                output = (directory / 'integrity-service-2.log').open('w')
                outputs.append(output)
                processes.append(subprocess.Popen([java, '-jar', str(jars / 'integrity-service.jar')],
                    env={**env, 'SERVER_PORT': str(port),
                         'MMIV_EVENTS_OUTBOX_DIRECTORY': str(directory / 'outbox/integrity-service-2')},
                    stdout=output, stderr=subprocess.STDOUT))
                url = f'http://127.0.0.1:{port}'
                wait(lambda: request(url + '/actuator/health')[0] == 200, 150)
                return url
            compare(request, gateway, token, load_payload, load_headers,
                    f'http://127.0.0.1:{ports["integrity-service"]}', start_replica, artifacts)
        # Exercise the real shared Redis counter and Gateway admission path without
        # issuing thousands of requests merely to exhaust the benchmark's high limit.
        counter = 'mmiv:rate:api:' + hashlib.sha256(('user:' + user_id).encode()).hexdigest()
        docker('exec', names['redis'], 'redis-cli', 'SET', counter, '100000', 'EX', '60')
        try:
            status, body = request(gateway + '/api/access/me', token=token)
            assert status == 429 and json.loads(body)['error'] == 'RATE_LIMIT_EXCEEDED', (status, body)
        finally:
            docker('exec', names['redis'], 'redis-cli', 'DEL', counter)
        assert request(gateway + '/api/access/me', token=token)[0] == 200
        print('PASS Redis-backed admission rejects an exhausted user bucket and permits recovery', flush=True)
        assert plan['destinationKey'].startswith('SW001/migrations/')
        listed=json.loads(request(gateway+'/api/v1/portal/files?folder=SW001',token=token)[1])
        assert any(f['fileId']==result['fileId'] and f['name']=='test.txt' for f in listed['files'])
        # Metadata serializes concurrent name reservations and uses ASCII suffixes.
        from concurrent.futures import ThreadPoolExecutor
        assert request(gateway+'/api/v1/portal/admin/folders','POST',{'name':'collision-test'},admin_token)[0]==200
        assert request(admin_api+'/users/'+user_id+'/destinations','PUT',{'destination':'collision-test','permission':'WRITE'},admin_token)[0]==200
        with ThreadPoolExecutor(max_workers=2) as pool:
            allocations=list(pool.map(lambda _:request(gateway+'/api/metadata/allocations','POST',{'destinationId':'collision-test','fileName':'duplicate.pdf','sizeBytes':4},token),range(2)))
        assert all(status==200 for status,_ in allocations),allocations
        assigned_names={json.loads(body)['fileName'] for _,body in allocations};assert assigned_names=={'duplicate.pdf','duplicate (2).pdf'},assigned_names
        deletion = request(gateway+'/api/v1/portal/folders?folder=collision-test','DELETE',token=admin_token)
        assert deletion[0] == 200, ('collision folder deletion', deletion)
        # Exercise the actual multipart upload, including existing legacy S3 objects.
        def upload_fixture(name):
            boundary='mmiv-'+uuid.uuid4().hex
            payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="destinationId"\r\n\r\nSW001\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\nfixture bytes\r\n--{boundary}--\r\n').encode()
            req=urllib.request.Request(gateway+'/api/v1/transfers',data=payload,headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
            with urllib.request.urlopen(req,timeout=120) as response:return json.loads(response.read())
        S3Fixture.objects['/it-landing/SW001/legacy.txt']=(b'legacy','text/plain')
        uploaded=upload_fixture('legacy.txt');assert uploaded['success'] and uploaded['fileName']=='legacy (2).txt',uploaded
        uploaded_again=upload_fixture('legacy.txt');assert uploaded_again['success'] and uploaded_again['fileName']=='legacy (3).txt',uploaded_again
        print('PASS concurrent namespace reservations and collision-safe ASCII upload suffixes',flush=True)
        assert request(gateway+'/api/v1/portal/files?folder=semester2',token=token)[0]==403
        profile=json.loads(request(gateway+'/api/access/me',token=token)[1]);assert [f['destination'] for f in profile['destinations']]==['SW001']
        viewer={'username':'viewer_'+uuid.uuid4().hex[:12],'password':secrets.token_hex(16)}
        viewer_id=json.loads(request(native+'/api/auth/signup','POST',viewer)[1])['userId']
        viewer_token=json.loads(request(native+'/api/auth/login','POST',viewer)[1])['accessToken']
        assert request(admin_api+'/users/'+viewer_id+'/destinations','PUT',{'destination':'SW001','permission':'READ'},admin_token)[0]==200
        assert request(gateway+'/api/v1/files/'+result['fileId']+'/download',token=viewer_token)[0]==200
        # Reporting deliberately hides another owner's report as not found.
        foreign_report = request(gateway+'/api/reports/'+plan['migrationId'],token=viewer_token)
        assert foreign_report[0] == 404, foreign_report
        assert request(gateway+'/api/metadata/allocations','POST',{'destinationId':'SW001','fileName':'blocked.txt','sizeBytes':1},viewer_token)[0]==403
        assert request(gateway+'/api/dashboard?folder=SW001',token=viewer_token)[0]==403
        assert request(gateway+'/api/dashboard?folder=semester2',token=viewer_token)[0]==403
        assert request(gateway+'/api/dashboard',token=admin_token)[0]==200
        assert request(gateway+'/api/v1/portal/files?folder=SW001',token=viewer_token)[0]==200
        # Even a guessed file ID cannot cross the folder boundary.
        guessed=base64.urlsafe_b64encode(b'semester2/private.txt').decode().rstrip('=')
        S3Fixture.objects['/it-landing/semester2/private.txt']=(b'private','text/plain')
        assert request(gateway+'/api/v1/files/'+guessed+'/download',token=viewer_token)[0]==403
        assert request(gateway+'/api/v1/portal/files/'+result['fileId'],'DELETE',token=viewer_token)[0]==403
        assert request(admin_api+'/users/'+viewer_id+'/destinations','PUT',{'destination':'SW001','permission':'WRITE'},admin_token)[0]==200
        assert request(gateway+'/api/v1/portal/files/'+result['fileId'],'DELETE',token=viewer_token)[0]==403
        assert request(gateway+'/api/v1/portal/folders?folder=SW001','DELETE',token=viewer_token)[0]==403
        def personal_history():
            data=json.loads(request(gateway+'/api/history',token=viewer_token)[1]);return data if any(e['action']=='Download' for e in data['entries']) else False
        history=wait(personal_history,60);assert all(e['action']!='Upload' for e in history['entries']);assert 'migrationId' not in json.dumps(history)
        print('PASS folder browsing, shared READ downloads, owner-scoped reports, WRITE isolation and scoped dashboards',flush=True)

        assert request(admin_api + '/users/' + user_id + '/destinations', 'PUT',
                       {'destination': 'SW001', 'permission': 'READ'}, external_token)[0] == 200
        assert request(gateway + '/api/v1/transfers', 'POST', {'metadataId': plan['metadataId']}, token)[0] == 403
        assert request(admin_api + '/users/' + user_id + '/destinations?destination=SW001', 'DELETE', token=external_token)[0] == 200
        assert request(gateway + '/api/v1/files/' + result['fileId'] + '/content', token=token)[0] == 403
        # Revoking storage access does not erase the owner's historical report.
        owned_report = request(gateway + '/api/reports/' + plan['migrationId'], token=token)
        assert owned_report[0] == 200, owned_report
        print('PASS permission revocation applies to existing JWTs', flush=True)
        # Restore WRITE for cleanup, and prove an unrelated plan/object survives.
        assert request(admin_api + '/users/' + user_id + '/destinations', 'PUT',
                       {'destination': 'SW001', 'permission': 'WRITE'}, external_token)[0] == 200
        status, body=request(gateway+'/api/metadata/plans','POST',plan_request,token)
        assert status==200
        unrelated=json.loads(body)
        assert request(gateway+'/api/metadata/plans/'+unrelated['metadataId']+'/transfer','POST',{},token)[0]==200
        # The same run ID under another account is a separate scope.
        assert request(admin_api+'/users/'+external_id+'/destinations','PUT',
                       {'destination':'SW001','permission':'WRITE'},external_token)[0]==200
        status,body=request(gateway+'/api/metadata/plans','POST',plan_request,external_token,headers=run_header)
        assert status==200
        foreign=json.loads(body)
        cleanup_url=gateway+'/api/metadata/verification/'+verification_run+'/cleanup'
        status,body=request(cleanup_url,'POST',{},token)
        assert status==200,(status,body)
        assert json.loads(body)['deletedPlans']==1
        assert '/it-landing/'+plan['destinationKey'] not in S3Fixture.objects
        assert '/it-landing/'+unrelated['destinationKey'] in S3Fixture.objects
        assert request(gateway+'/api/metadata/plans/'+plan['metadataId'],token=token)[0]==404
        assert request(gateway+'/api/metadata/plans/'+unrelated['metadataId'],token=token)[0]==200
        assert request(gateway+'/api/metadata/plans/'+foreign['metadataId'],token=external_token)[0]==200
        assert request(gateway+'/api/metadata/plans','POST',plan_request,token,headers=run_header)[0]==409
        assert request(cleanup_url,'POST',{},token)[0]==200
        assert int(mongo("db.getSiblingDB('mmiv_reporting').migration_reports.countDocuments({_id:"+json.dumps(plan['metadataId'])+"})"))==0
        assert int(mongo("db.getSiblingDB('mmiv_reporting').audit_events.countDocuments({migrationId:"+json.dumps(plan['metadataId'])+"})"))==0
        # Temporary verification catalog entry is never visible and is removed last.
        temporary_run=str(uuid.uuid4());temporary_folder='mmiv-verification-'+temporary_run
        sql("INSERT INTO MMIV_FOLDERS(NAME) VALUES ('"+temporary_folder+"');\nINSERT INTO USER_DESTINATION_ACCESS(USER_ID,DESTINATION,PERMISSION) VALUES ('"+user_id+"','"+temporary_folder+"','WRITE');\nCOMMIT;")
        for actor in [token,admin_token]:
            assert temporary_folder not in json.dumps(json.loads(request(gateway+'/api/access/me',token=actor)[1]))
        assert request(gateway+'/api/metadata/allocations','POST',{'destinationId':temporary_folder,'fileName':'test.txt','sizeBytes':4},token)[0]==403
        status,body=request(gateway+'/api/metadata/plans','POST',{'destinationId':temporary_folder,'sourcePath':'test.txt'},token,headers={'X-MMIV-Verification-Run':temporary_run});assert status==200,(status,body)
        temporary_plan=json.loads(body)
        assert request(gateway+'/api/metadata/plans/'+temporary_plan['metadataId']+'/transfer','POST',{},token)[0]==200
        status,body=request(gateway+'/api/v1/verification/'+temporary_run+'/finish','POST',{},token);assert status==409,(status,body)
        temp_cleanup=gateway+'/api/metadata/verification/'+temporary_run+'/cleanup'
        status,body=request(temp_cleanup,'POST',{},token);assert status==200,(status,body)
        assert all(not k.startswith('/it-landing/'+temporary_folder+'/') for k in S3Fixture.objects)
        assert sql("SELECT COUNT(*) FROM MMIV_FOLDERS WHERE NAME='"+temporary_folder+"';").strip()=='0'
        assert sql("SELECT COUNT(*) FROM USER_DESTINATION_ACCESS WHERE DESTINATION='"+temporary_folder+"';").strip()=='0'
        assert request(temp_cleanup,'POST',{},token)[0]==200
        # Ownership deletion, nested folder boundaries and complete catalog/grant cleanup.
        assert request(gateway+'/api/v1/portal/files/'+base64.urlsafe_b64encode(unrelated['destinationKey'].encode()).decode().rstrip('='),'DELETE',token=token)[0]==200
        assert '/it-landing/'+unrelated['destinationKey'] not in S3Fixture.objects
        S3Fixture.objects['/it-landing/semester2/docs/a.txt']=(b'a','text/plain')
        S3Fixture.objects['/it-landing/semester2/docs-other/b.txt']=(b'b','text/plain')
        assert request(gateway+'/api/v1/portal/folders?folder=semester2&path=docs','DELETE',token=admin_token)[0]==200
        assert '/it-landing/semester2/docs/a.txt' not in S3Fixture.objects and '/it-landing/semester2/docs-other/b.txt' in S3Fixture.objects
        assert request(gateway+'/api/v1/portal/folders?folder=semester2','DELETE',token=admin_token)[0]==200
        assert all(not k.startswith('/it-landing/semester2/') for k in S3Fixture.objects)
        assert 'semester2' not in json.loads(request(gateway+'/api/v1/portal/admin/folders',token=admin_token)[1])
        print('PASS scoped verification cleanup removes S3, metadata, reports and migration audits; preserves unrelated data',flush=True)
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
        # Broker health can temporarily mark services DOWN in Eureka; wait for routing recovery.
        wait(lambda: request(gateway+'/api/access/me',token=admin_token)[0]==200,120)
        # One-use reset links revoke old JWTs; account deletion preserves protected admins.
        wait(lambda: request(gateway+'/reset-password.html')[0] == 200, 120)
        reset_url=gateway+'/api/v1/portal/admin/users/'+viewer_id+'/password-reset'
        status,body=request(reset_url,'POST',{},admin_token);assert status==200,(status,body);reset=json.loads(body)
        replacement=secrets.token_hex(16)
        public_reset=gateway+'/user-access/api/auth/password-reset'
        status,body=request(public_reset,'POST',{'token':reset['token'],'password':replacement});assert status==200,(status,body)
        assert request(public_reset,'POST',{'token':reset['token'],'password':replacement})[0]==400
        assert request(native+'/api/access/me',token=viewer_token)[0]==401
        assert request(native+'/api/auth/login','POST',viewer)[0]==401
        viewer['password']=replacement;viewer_token=json.loads(request(native+'/api/auth/login','POST',viewer)[1])['accessToken']
        assert request(gateway+'/api/access/me',token=viewer_token)[0]==200
        assert request(gateway+'/api/v1/portal/admin/users/'+external_id,'DELETE',token=admin_token)[0]==403
        assert request(gateway+'/api/v1/portal/admin/users/'+external_id+'/password-reset','POST',{},admin_token)[0]==403
        assert request(gateway+'/api/v1/portal/admin/users/'+viewer_id,'DELETE',token=token)[0]==403
        status,body=request(reset_url,'POST',{},admin_token);assert status==200;pending_reset=json.loads(body)['token']
        assert request(gateway+'/api/v1/portal/admin/users/'+viewer_id,'DELETE',token=admin_token)[0]==204
        assert request(native+'/api/auth/login','POST',viewer)[0]==401
        assert sql("SELECT COUNT(*) FROM USER_DESTINATION_ACCESS WHERE USER_ID='"+viewer_id+"';").strip()!='0'
        assert sql("SELECT ACTIVE FROM APP_USERS WHERE USER_ID='"+viewer_id+"';").strip()=='0'
        assert request(native+'/api/access/me',token=viewer_token)[0]==401
        assert sql("SELECT COUNT(*) FROM PASSWORD_RESETS WHERE USER_ID='"+viewer_id+"' AND EXPIRES_AT<=SYSTIMESTAMP;").strip()=='1'
        assert request(public_reset,'POST',{'token':pending_reset,'password':replacement})[0]==400
        assert request(gateway+'/api/v1/portal/admin/users/'+external_id,'DELETE',token=external_token)[0]==204
        assert request(gateway+'/api/access/me',token=external_token)[0] in (401,403)
        print('PASS password-reset links, session revocation, account deletion and other-admin protection',flush=True)
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
                                                         'checks': ['folder-browser', 'folder-permissions', 'shared-cookie-session', 'scoped-dashboard', 'verification-data-cleanup', 'default-admin', 'admin-invitations', 'admin-permission-management', 'login-redirects', 'permission-denial', 'assigned-destination', 'integrity', 'report', 'revocation', 'all-service-logs', 'broker-recovery', 'account-cleanup']}, indent=2) + '\n')
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
        for name in SERVICES + ['integrity-service-2']:
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
