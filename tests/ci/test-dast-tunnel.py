#!/usr/bin/env python3
"""Exercise the actual DAST shell script with local sockets and fake AWS tools."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class TunnelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.env = {**os.environ, 'PATH': str(self.directory) + os.pathsep + os.environ['PATH'],
                    'INSTANCE_ID': 'i-test', 'DAST_PORT': '8080', 'DAST_SERVICE_NAME': 'api-gateway',
                    'SCANNER_TOOLS_PREPARED': 'true',
                    'USER_ACCESS_TEST_USERNAME': 'test-user', 'USER_ACCESS_TEST_PASSWORD': 'test-pass',
                    'DAST_REPORT_DIR': str(self.directory / 'report'), 'TEST_DIRECTORY': str(self.directory)}
        self.env.pop('DAST_LOCAL_PORT', None)
        self.tool('docker', '#!/bin/sh\nexit 0\n')
        self.tool('session-manager-plugin', '#!/bin/sh\nexit 0\n')
        self.tool('python3', f'''#!{sys.executable}
import os, sys
if len(sys.argv) > 1 and sys.argv[1] == 'scripts/cd/test-endpoints.py':
    open(os.environ['TEST_DIRECTORY'] + '/functional-args.json', 'w').write(__import__('json').dumps(sys.argv[2:]))
    sys.exit(23)  # Stop after readiness, exercising the EXIT cleanup.
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
            if self.path != '/user-access/api/auth/login' or body != {{'username': 'test-user', 'password': 'test-pass'}}:
                self.send_response(401)
                self.end_headers()
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{{"accessToken":"test.eyJzdWIiOiAiVTAwMSJ9.signature","tokenType":"Bearer"}}')
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
            args = json.loads((self.directory / 'functional-args.json').read_text())
            self.assertIn(f'http://127.0.0.1:{port}', args)
            self.assertEqual((self.directory / 'terminated').read_text(), 'test-owned-session')
            self.assert_port_closed(port)
            with socket.create_connection(occupied.getsockname(), timeout=1):
                pass

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

    def test_stubborn_plugin_child_is_killed_and_releases_its_listener(self):
        result = self.run_scan(TEST_STUBBORN_CHILD='1')
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertTrue((self.directory / 'child-pid').exists())
        self.assert_port_closed(self.selected_port())

    def test_readiness_requires_the_plugins_port_acknowledgement(self):
        result = self.run_scan(TEST_NO_ACK='1')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('SSM tunnel exited', result.stdout)
        self.assertFalse((self.directory / 'curl-called').exists())
        self.assertFalse((self.directory / 'functional-args.json').exists())


if __name__ == '__main__':
    unittest.main()
