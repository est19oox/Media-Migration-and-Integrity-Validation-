#!/usr/bin/env python3
"""Exercise signup-load accounting and verification-account cleanup locally."""

import importlib.util
import json
import re
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("user_access_signup.py")
SPEC = importlib.util.spec_from_file_location("user_access_signup", SCRIPT)
load = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load)


class SignupHandler(BaseHTTPRequestHandler):
    accounts = {}
    lock = threading.Lock()

    def log_message(self, *_args):
        pass

    def respond(self, code, body=None):
        payload = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        size = int(self.headers["Content-Length"])
        body = json.loads(self.rfile.read(size))
        username = body["username"]
        if self.path == "/api/auth/signup":
            if not re.fullmatch(r"mmiv_ci_[0-9a-f]{32}", username):
                return self.respond(400)
            with self.lock:
                self.accounts[username] = body["password"]
            return self.respond(201, {"userId": username})
        if self.path == "/api/auth/login":
            with self.lock:
                valid = self.accounts.get(username) == body["password"]
            return self.respond(
                200 if valid else 401,
                {"accessToken": "token:" + username, "tokenType": "Bearer"} if valid else {},
            )
        self.respond(404)

    def do_DELETE(self):
        if self.path != "/api/auth/verification-account":
            return self.respond(404)
        username = self.headers.get("Authorization", "").removeprefix("Bearer token:")
        with self.lock:
            existed = self.accounts.pop(username, None) is not None
        self.respond(204 if existed else 403)


class SignupLoadTest(unittest.TestCase):
    def test_eureka_excludes_fargate_link_local_address(self):
        entries = {"application": {"instance": [
            {"status": "UP", "ipAddr": "169.254.172.2", "port": {"$": 8083}},
            {"status": "UP", "ipAddr": "10.0.1.25", "port": {"$": 8083}},
            {"status": "DOWN", "ipAddr": "10.0.1.26", "port": {"$": 8083}},
        ]}}
        self.assertEqual(load.parse_instances(entries), ["http://10.0.1.25:8083"])

    def test_accounts_are_deleted_after_load(self):
        SignupHandler.accounts = {}
        server = ThreadingHTTPServer(("127.0.0.1", 0), SignupHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with tempfile.TemporaryDirectory() as temporary, patch.object(
                load.EurekaTargets, "choose", autospec=True,
                side_effect=lambda catalog, _number: base,
            ):
                report = Path(temporary) / "report.json"
                load.run(SimpleNamespace(
                    eureka_url="unused", minutes=0.05, workers=2,
                    max_accounts=4, report=report,
                ))
                summary = json.loads(report.read_text())
                self.assertEqual(summary["status_counts"]["signup_201"], 4)
                self.assertGreater(summary["status_counts"].get("login_200", 0), 0)
                self.assertEqual(summary["cleanup_succeeded"], 4)
                self.assertEqual(summary["cleanup_failed"], 0)
                self.assertNotIn("password", report.read_text())
                self.assertFalse(SignupHandler.accounts)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_gateway_mode_uses_prefixed_paths_and_cleans_up(self):
        SignupHandler.accounts = {}
        server = ThreadingHTTPServer(("127.0.0.1", 0), SignupHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        original_send = load.send

        def gateway_send(target, path, method, data=None, token=None):
            self.assertEqual(target, base)
            self.assertTrue(path.startswith("/user-access/api/auth/"))
            return original_send(target, path.removeprefix("/user-access"), method, data, token)

        try:
            with tempfile.TemporaryDirectory() as temporary, patch.object(
                load, "send", side_effect=gateway_send,
            ):
                report = Path(temporary) / "report.json"
                load.run(SimpleNamespace(
                    gateway_url=base, eureka_url="unused", minutes=0.02,
                    workers=2, max_accounts=3, report=report,
                ))
                summary = json.loads(report.read_text())
                self.assertEqual(summary["status_counts"]["signup_201"], 3)
                self.assertEqual(summary["cleanup_failed"], 0)
                self.assertIsNone(summary["observed_instance_count"])
                self.assertFalse(SignupHandler.accounts)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
