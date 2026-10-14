#!/usr/bin/env python3
"""Exercise the actual DAST shell script with local sockets and fake AWS tools."""
import json
import importlib.util
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
VERIFY_SPEC = importlib.util.spec_from_file_location(
    'verify_deployed_services', ROOT / 'scripts/cd/verify-deployed-services.py')
VERIFY_DEPLOYED_SERVICES = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY_DEPLOYED_SERVICES)


class TunnelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.env = {**os.environ, 'PATH': str(self.directory) + os.pathsep + os.environ['PATH'],
                    'INSTANCE_ID': 'i-test', 'DAST_PORT': '8080', 'DAST_SERVICE_NAME': 'api-gateway',
                    'SCANNER_TOOLS_PREPARED': 'true',
                    'MMIV_VERIFICATION_DESTINATION':'test-folder', 'USER_ACCESS_TEST_USERNAME': 'test-user', 'USER_ACCESS_TEST_PASSWORD': 'test-pass',
                    'DAST_REPORT_DIR': str(self.directory / 'report'), 'TEST_DIRECTORY': str(self.directory)}
        self.env.pop('DAST_LOCAL_PORT', None)
        self.tool('docker', f'''#!{sys.executable}
import json, os, sys
args = sys.argv[1:]
directory = os.environ['TEST_DIRECTORY']
with open(directory + '/docker-calls.jsonl', 'a') as calls:
    calls.write(json.dumps(args) + '\\n')
if args[:2] == ['image', 'inspect']:
    sys.exit(0)
if args[0] == 'create':
    print('fake-zap-container')
    sys.exit(0)
if args[0] == 'start':
    mode = os.environ.get('TEST_ZAP_MODE', 'success')
    start_count = sum(1 for line in open(directory + '/docker-calls.jsonl')
                      if json.loads(line)[0] == 'start')
    if mode == 'collision-once' and start_count == 1 or mode == 'collision-always':
        print('java.net.BindException: Address already in use', file=sys.stderr)
        sys.exit(1)
    if mode == 'generic-failure':
        print('ZAP failed for an unrelated scanner error', file=sys.stderr)
        sys.exit(3)
    if mode == 'security-finding':
        print('FAIL-NEW: Security finding detected', file=sys.stderr)
        sys.exit(1)
    if mode == 'interrupted':
        open(directory + '/scanner-started', 'w').close()
        print('partial scanner output', flush=True)
        while True:
            import time
            time.sleep(1)
    print('fake ZAP output')
    sys.exit(int(os.environ.get('TEST_ZAP_EXIT_CODE', '0')))
if args[0] == 'logs':
    print('fake container logs')
    sys.exit(0)
if args[0] == 'inspect':
    print('{{}}')
    sys.exit(0)
if args[0] == 'cp':
    source, destination = args[1:3]
    if source.endswith('/root/.ZAP/zap.log'):
        open(destination, 'w').write('fake internal ZAP log')
    elif source.endswith('/openapi.json'):
        sys.exit(0)
    if source.endswith('/zap/wrk/zap-report.json'):
        if os.environ.get('TEST_MISSING_REPORT') == 'json':
            print('simulated missing JSON report', file=sys.stderr)
            sys.exit(1)
        open(destination, 'w').write('{{}}')
    elif source.endswith('/zap/wrk/zap-report.html'):
        if os.environ.get('TEST_MISSING_REPORT') == 'html':
            print('simulated missing HTML report', file=sys.stderr)
            sys.exit(1)
        open(destination, 'w').write('<html>fake report</html>')
    elif not source.endswith('/root/.ZAP/zap.log'):
        sys.exit(1)
    sys.exit(0)
if args[0] == 'rm':
    if os.environ.get('TEST_DOCKER_RM_FAIL'):
        print('simulated docker rm failure', file=sys.stderr)
        sys.exit(1)
    open(directory + '/zap-container-removed', 'w').write(args[-1])
    sys.exit(0)
sys.exit(2)
''')
        self.tool('session-manager-plugin', '#!/bin/sh\nexit 0\n')
        self.tool('python3', f'''#!{sys.executable}
import os, sys
if len(sys.argv) > 1 and sys.argv[1] == 'scripts/cd/test-endpoints.py':
    open(os.environ['TEST_DIRECTORY'] + '/functional-args.json', 'w').write(__import__('json').dumps(sys.argv[2:]))
    sys.exit(0 if os.environ.get('TEST_CONTINUE_TO_SCAN') else 23)
os.execv({sys.executable!r}, [{sys.executable!r}] + sys.argv[1:])
''')
        self.tool('curl', f'''#!{sys.executable}
import os, sys, urllib.request
open(os.environ['TEST_DIRECTORY'] + '/curl-called', 'w').write(sys.argv[-1])
try:
    with urllib.request.urlopen(sys.argv[-1], timeout=1) as response:
        sys.stdout.buffer.write(response.read())
except Exception:
    sys.exit(1)
''')
        self.tool('aws', f'''#!{sys.executable}
import http.server, json, os, signal, sys, time
from pathlib import Path
directory = Path(os.environ['TEST_DIRECTORY'])
operation = sys.argv[2]
if operation == 'get-connection-status':
    print('connected')
elif operation == 'terminate-session':
    (directory / 'terminated').write_text(sys.argv[sys.argv.index('--session-id') + 1])
elif operation == 'start-session':
    parameters = json.loads(sys.argv[sys.argv.index('--parameters') + 1])
    port = int(parameters['localPortNumber'][0])
    (directory / 'parameters.json').write_text(json.dumps(parameters))
    print('Starting session with SessionId: test-owned-session', flush=True)
    if os.environ.get('TEST_NO_ACK'):
        time.sleep(0.2)
        sys.exit(1)
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{{"status":"UP"}}')
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path.startswith('/api/metadata/verification/'):
                (directory / 'data-cleanup-called').write_text(self.path)
                self.send_response(503 if os.environ.get('TEST_CLEANUP_FAIL') else 200)
                self.end_headers()
                self.wfile.write(b'{{"deletedPlans":0}}')
                return
            if self.path != '/user-access/api/auth/login'  or body != {{'username': 'test-user', 'password': 'test-pass'}}:
                self.send_response(401)
                self.end_headers()
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{{"accessToken":"test.eyJzdWIiOiJ0ZXN0LXVzZXItaWQifQ.signature","tokenType":"Bearer"}}')
        def log_message(self, *args):
            pass
    server = http.server.HTTPServer(('127.0.0.1', port), Handler)
    if os.environ.get('TEST_STUBBORN_CHILD'):
        child = os.fork()
        if child:
            (directory / 'child-pid').write_text(str(child))
            while True:
                time.sleep(1)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    print(f'Port {{port}} opened for sessionId test-owned-session.', flush=True)
    server.serve_forever()
''')

    def tool(self, name, source):
        path = self.directory / name
        path.write_text(source)
        path.chmod(0o755)

    def run_scan(self, **settings):
        return subprocess.run(['bash', str(ROOT / 'scripts/cd/run-dast-ssm.sh')], cwd=ROOT,
                              env={**self.env, **settings}, capture_output=True, text=True, timeout=15)

    def docker_calls(self):
        return [json.loads(line) for line in (self.directory / 'docker-calls.jsonl').read_text().splitlines()]

    def start_scan_interrupted(self):
        process = subprocess.Popen(
            ['bash', str(ROOT / 'scripts/cd/run-dast-ssm.sh')],
            cwd=ROOT, env={**self.env, 'TEST_CONTINUE_TO_SCAN': '1',
                           'TEST_ZAP_MODE': 'interrupted'},
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            import time
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline and not (self.directory / 'scanner-started').exists():
                if process.poll() is not None:
                    break
                time.sleep(0.05)
            self.assertTrue((self.directory / 'scanner-started').exists(), 'scanner did not start')
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=12)
            return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=3)

    def selected_port(self):
        return int(json.loads((self.directory / 'parameters.json').read_text())['localPortNumber'][0])

    def assert_port_closed(self, port):
        with socket.socket() as probe:
            probe.settimeout(0.3)
            self.assertNotEqual(probe.connect_ex(('127.0.0.1', port)), 0)

    def test_auto_port_avoids_an_existing_listener_and_propagates_url(self):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            result = self.run_scan()
            self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
            port = self.selected_port()
            self.assertNotEqual(port, occupied.getsockname()[1])
            self.assertTrue((self.directory / 'data-cleanup-called').exists())
            args = json.loads((self.directory / 'functional-args.json').read_text())
            self.assertIn(f'http://127.0.0.1:{port}', args)
            self.assertEqual((self.directory / 'terminated').read_text(), 'test-owned-session')
            self.assert_port_closed(port)
            with socket.create_connection(occupied.getsockname(), timeout=1):
                pass

    def test_data_cleanup_failure_fails_scan_and_still_closes_tunnel(self):
        result=self.run_scan(TEST_CLEANUP_FAIL='1')
        self.assertEqual(result.returncode,1)
        self.assertTrue((self.directory/'data-cleanup-called').exists())
        self.assert_port_closed(self.selected_port())

    def test_explicit_busy_port_has_a_clear_error_and_preserves_listener(self):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            port = occupied.getsockname()[1]
            result = self.run_scan(DAST_LOCAL_PORT=str(port))
            self.assertEqual(result.returncode, 1)
            self.assertIn('Unset DAST_LOCAL_PORT', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertFalse((self.directory / 'parameters.json').exists())
            with socket.create_connection(('127.0.0.1', port), timeout=1):
                pass

    def test_explicit_free_port_is_honored(self):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        result = self.run_scan(DAST_LOCAL_PORT=str(port))
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertEqual(self.selected_port(), port)
        self.assert_port_closed(port)

    def test_user_access_scans_through_gateway_path(self):
        result = self.run_scan(DAST_SERVICE_NAME='user-access-service', DAST_PORT='8083',
                               DAST_PATH='/user-access/actuator/health',
                               DAST_READY_PATH='/user-access/actuator/health',
                               DAST_ENDPOINT_BASE_PATH='/user-access',
                               TEST_CONTINUE_TO_SCAN='1')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((self.directory / 'parameters.json').read_text())['portNumber'], ['8080'])
        args = json.loads((self.directory / 'functional-args.json').read_text())
        self.assertIn(f'http://127.0.0.1:{self.selected_port()}/user-access', args)
        create = next(call for call in self.docker_calls() if call[0] == 'create')
        self.assertEqual(create[create.index('-t') + 1],
                         f'http://127.0.0.1:{self.selected_port()}/user-access/actuator/health')

    def test_stubborn_plugin_child_is_killed_and_releases_its_listener(self):
        result = self.run_scan(TEST_STUBBORN_CHILD='1')
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertTrue((self.directory / 'child-pid').exists())
        self.assert_port_closed(self.selected_port())

    def test_zap_uses_a_selected_proxy_port_and_removes_container(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

        calls = self.docker_calls()
        create = next(args for args in calls if args[0] == 'create')
        config_index = create.index('-z') + 1
        config = create[config_index]
        self.assertRegex(config, r'-config network\.localServers\.mainProxy\.port=\d+')
        proxy_port = int(config.rsplit('=', 1)[1])
        self.assertGreaterEqual(proxy_port, 1)
        self.assertLessEqual(proxy_port, 65535)
        self.assertNotEqual(proxy_port, 8080)
        container = create[create.index('--name') + 1]
        self.assertIn(['rm', '-f', '-v', container], calls)
        self.assertTrue((self.directory / 'zap-container-removed').exists())
        self.assertTrue((self.directory / 'report/zap-report.json').exists())
        self.assertTrue((self.directory / 'report/zap-report.html').exists())

    def test_api_scan_uses_an_explicit_proxy_port(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', DAST_SCAN_MODE='api',
                               DAST_OPENAPI_FILE='tests/api/transfer-openapi.json')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        create = next(args for args in self.docker_calls() if args[0] == 'create')
        config = create[create.index('-z') + 1]
        self.assertRegex(config, r'-config network\.localServers\.mainProxy\.port=\d+')

    def test_success_collects_the_required_reports(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.directory / 'report/zap-report.json').is_file())
        self.assertTrue((self.directory / 'report/zap-report.html').is_file())

    def test_missing_report_fails_the_scan(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_MISSING_REPORT='json')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('without the required non-empty', result.stdout)

    def test_confirmed_startup_bind_collision_retries_with_a_new_port(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_MODE='collision-once')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('attempt 1/3', result.stdout)
        self.assertIn('attempt 2/3', result.stdout)
        calls = self.docker_calls()
        create_calls = [args for args in calls if args[0] == 'create']
        self.assertEqual(len(create_calls), 2)
        proxy_ports = [
            int(args[args.index('-z') + 1].rsplit('=', 1)[1])
            for args in create_calls
        ]
        self.assertNotEqual(proxy_ports[0], proxy_ports[1])
        self.assertTrue((self.directory / 'report/zap-attempt-1.log').is_file())
        self.assertIn('BindException', (self.directory / 'report/zap-attempt-1.log').read_text())
        removed = [args[-1] for args in calls if args[:2] == ['rm', '-f']]
        self.assertEqual(removed, [create_calls[0][create_calls[0].index('--name') + 1],
                                  create_calls[1][create_calls[1].index('--name') + 1]])

    def test_retry_exhaustion_fails_after_three_startup_conflicts(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_MODE='collision-always')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('after 3 attempts', result.stdout)
        self.assertEqual(sum(args[0] == 'start' for args in self.docker_calls()), 3)
        for attempt in range(1, 4):
            self.assertTrue((self.directory / f'report/zap-attempt-{attempt}.log').is_file())

    def test_cleanup_failure_stops_startup_retries(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_MODE='collision-once',
                               TEST_DOCKER_RM_FAIL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Stopping DAST retries', result.stderr)
        self.assertEqual(sum(args[0] == 'start' for args in self.docker_calls()), 1)

    def test_generic_scanner_failure_does_not_retry(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_MODE='generic-failure')
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertEqual(sum(args[0] == 'start' for args in self.docker_calls()), 1)
        self.assertIn('unrelated scanner error', (self.directory / 'report/zap-attempt-1.log').read_text())

    def test_security_finding_failure_does_not_retry(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_MODE='security-finding')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(sum(args[0] == 'start' for args in self.docker_calls()), 1)

    def test_zap_container_cleanup_failure_fails_the_job(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_DOCKER_RM_FAIL='1')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('Could not remove ZAP scan container', result.stderr)
        self.assertNotIn('DAST passed', result.stdout)

    def test_scanner_and_cleanup_failures_preserve_both_diagnostics(self):
        result = self.run_scan(TEST_CONTINUE_TO_SCAN='1', TEST_ZAP_EXIT_CODE='3',
                               TEST_DOCKER_RM_FAIL='1')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('ZAP failed with code 3', result.stdout)
        self.assertIn('Could not remove ZAP scan container', result.stderr)
        self.assertIn('simulated docker rm failure',
                      (self.directory / 'report/zap-container-cleanup.log').read_text())

    def test_interrupted_scan_preserves_logs_and_available_partial_reports(self):
        result = self.start_scan_interrupted()
        self.assertEqual(result.returncode, 143, result.stdout + result.stderr)
        calls = self.docker_calls()
        self.assertTrue(any(args[0] == 'logs' for args in calls))
        self.assertTrue(any(args[0] == 'cp' and args[1].endswith('/zap/wrk/zap-report.json')
                            for args in calls))
        evidence = self.directory / 'report/zap-interrupted-container.log'
        self.assertTrue(evidence.exists())
        self.assertIn('fake container logs', evidence.read_text())
        self.assertTrue((self.directory / 'report/zap-interrupted-zap-report.json').exists())
        self.assertTrue(any(args[:2] == ['rm', '-f'] for args in calls))

    def test_later_service_success_cannot_clear_an_earlier_failure(self):
        codes = iter((3, 0))

        def completed_scan(*args, **kwargs):
            return SimpleNamespace(stdout=iter(('mock scanner output\n',)),
                                   wait=lambda: next(codes))

        deployments = {
            'INSTANCE_ID': 'i-test',
            'services': {
                'api-gateway': {'port': '8080'},
                'service-registry': {'port': '8081'},
            },
        }
        with patch.dict(os.environ, {'USER_ACCESS_TEST_USERNAME': '',
                                     'USER_ACCESS_TEST_PASSWORD': ''}):
            with patch.object(VERIFY_DEPLOYED_SERVICES.subprocess, 'Popen',
                              side_effect=completed_scan):
                result = VERIFY_DEPLOYED_SERVICES.verify_scans(
                    deployments, self.directory / 'service-reports',
                    self.directory / 'account.json')

        self.assertEqual(result, 1)
        summary = json.loads(
            (self.directory / 'service-reports/verification-summary.json').read_text())
        self.assertEqual([entry['exit_code'] for entry in summary], [3, 0])

    def test_readiness_requires_the_plugins_port_acknowledgement(self):
        result = self.run_scan(TEST_NO_ACK='1')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('SSM tunnel exited', result.stdout)
        self.assertFalse((self.directory / 'curl-called').exists())
        self.assertFalse((self.directory / 'functional-args.json').exists())


if __name__ == '__main__':
    unittest.main()
