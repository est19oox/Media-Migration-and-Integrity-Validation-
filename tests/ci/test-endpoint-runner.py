#!/usr/bin/env python3
"""Exercise endpoint evidence retention through a real local HTTP server."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/cd/test-endpoints.py'
spec = importlib.util.spec_from_file_location('endpoint_runner', SCRIPT)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EndpointRunnerTest(unittest.TestCase):
    def run_fixture(self, response, case, http_status=200, headers=None):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(http_status)
                self.send_header('Content-Type', 'application/json')
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(json.dumps(response).encode())

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                config = Path(directory) / 'config.json'
                report = Path(directory) / 'report.json'
                config.write_text(json.dumps({'services': {'transfer-service': [case]}}))
                result = subprocess.run([
                    sys.executable, str(SCRIPT), '--service', 'transfer-service',
                    '--base-url', f'http://127.0.0.1:{server.server_port}',
                    '--config', str(config), '--report', str(report)
                ], capture_output=True, text=True)
                return result, json.loads(report.read_text())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_redirect_mismatch_records_location_without_sensitive_headers(self):
        result, report = self.run_fixture({}, {
            'name': 'redirect', 'path': '/upload.html', 'status': 302,
            'follow_redirects': False, 'header_equals': {'Location': '/login.html'}
        }, http_status=302, headers={'Location': 'http://public.example/login.html',
                                    'Set-Cookie': 'private-session'})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report['results'][0]['response_headers'],
                         {'Location': 'http://public.example/login.html'})
        self.assertNotIn('private-session', json.dumps(report))

    def test_origin_relative_redirect_passes(self):
        result, report = self.run_fixture({}, {
            'name': 'redirect', 'path': '/upload.html', 'status': 302,
            'follow_redirects': False, 'header_equals': {'Location': '/login.html'}
        }, http_status=302, headers={'Location': '/login.html'})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(report['results'][0]['response_headers']['Location'], '/login.html')

    def test_partial_folder_failure_retains_per_file_evidence(self):
        response = {'succeeded': 1, 'failed': 1, 'files': [
            {'relativePath': 'Office/slides.ppt', 'status': 'PASSED'},
            {'relativePath': 'Office/report.docx', 'status': 'UNAVAILABLE',
             'upload': {'success': False, 'cleanupStatus': 'DELETED'}}],
            'private': 'must not be captured'}
        result, report = self.run_fixture(response, {
            'name': 'folder', 'path': '/', 'status': 200,
            'json_equals': {'succeeded': 2},
            'report_fields': ['succeeded', 'failed', 'files']})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report['summary']['FAIL'], 1)
        item = report['results'][0]
        self.assertEqual(item['http_status'], 200)
        self.assertIn('expected 2, received 1', item['detail'])
        self.assertEqual(item['response']['files'][1]['status'], 'UNAVAILABLE')
        self.assertNotIn('private', item['response'])

    def test_http_failure_retains_available_configured_fields(self):
        result, report = self.run_fixture({'error': 'Transfer unavailable'}, {
            'name': 'folder', 'path': '/', 'status': 200,
            'report_fields': ['error', 'succeeded']}, http_status=503)
        self.assertEqual(result.returncode, 1)
        item = report['results'][0]
        self.assertEqual(item['http_status'], 503)
        self.assertEqual(item['response'], {'error': 'Transfer unavailable'})
        self.assertIn('received 503', item['detail'])

    def test_success_checks_nested_file_paths(self):
        result, report = self.run_fixture({'files': [{'relativePath': 'Office/docs/a.docx'}]}, {
            'name': 'folder', 'path': '/', 'status': 200,
            'json_equals': {'files.0.relativePath': 'Office/docs/a.docx'}})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(report['summary']['PASS'], 1)

    def test_wrong_nested_path_fails(self):
        result, report = self.run_fixture({'files': [{'relativePath': 'a.docx'}]}, {
            'name': 'folder', 'path': '/', 'status': 200,
            'json_equals': {'files.0.relativePath': 'Office/docs/a.docx'}})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report['summary']['FAIL'], 1)

    def test_numeric_one_does_not_pass_boolean_success_assertion(self):
        result, report = self.run_fixture({'success': 1}, {
            'name': 'upload', 'path': '/', 'status': 200,
            'json_equals': {'success': True}})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report['summary']['FAIL'], 1)

    def test_missing_array_entry_and_scalar_are_rejected(self):
        for name in ('files.2.relativePath', 'files.-1.relativePath', 'files.0.nope'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runner.field({'files': [{'relativePath': 'a.txt'}]}, name)


if __name__ == '__main__':
    unittest.main()
