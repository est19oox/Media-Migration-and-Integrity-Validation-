#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import uuid

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('cleanup',ROOT/'scripts/cd/cleanup-verification-data.py')
cleanup=importlib.util.module_from_spec(spec);spec.loader.exec_module(cleanup)

class CleanupTest(unittest.TestCase):
    def test_cleanup_sends_run_and_bearer_privately_and_writes_secret_free_evidence(self):
        calls=[];run=str(uuid.uuid4())
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                calls.append((self.path,self.headers.get('Authorization')))
                self.send_response(200);self.end_headers();self.wfile.write(b'{"deletedPlans":4}')
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                auth=Path(tmp)/'auth';report=Path(tmp)/'report.json'
                auth.write_text('API_TEST_TOKEN=private-fixture-token\n')
                cleanup.cleanup('http://127.0.0.1:'+str(server.server_port),auth,run,report)
                self.assertEqual(calls,[('/api/metadata/verification/'+run+'/cleanup','Bearer private-fixture-token')])
                self.assertEqual(json.loads(report.read_text())['deletedPlans'],4)
                self.assertNotIn('private-fixture-token',report.read_text())
        finally:server.shutdown();server.server_close();worker.join()
    def test_nonlocal_or_redirect_capable_targets_are_rejected(self):
        for base in ('http://evil.example','http://127.0.0.1:80@evil.example','http://127.0.0.1:80/?x=1'):
            with self.subTest(base=base),self.assertRaises(ValueError):
                cleanup.cleanup(base,'unused',str(uuid.uuid4()),'unused')
    def test_invalid_run_is_rejected_before_request(self):
        with self.assertRaises(ValueError):cleanup.cleanup('http://127.0.0.1:80','unused','../other-user','unused')

if __name__=='__main__':unittest.main()
