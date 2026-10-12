#!/usr/bin/env python3
"""Obtain a real User Access JWT without recording credentials in reports."""
import json
import base64
import os
import re
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


def main():
    base, destination = sys.argv[1:]
    parsed = urlsplit(base)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port
            or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("Verification login must use the local SSM tunnel")
    username = os.environ.get("USER_ACCESS_TEST_USERNAME")
    password = os.environ.get("USER_ACCESS_TEST_PASSWORD")
    if not username or not password:
        raise ValueError("Set userAccessSmoke username/password in MMIV_RUNTIME_CONFIG to an existing test account")
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    opener = build_opener(NoRedirect())
    prefix = "/api/auth" if os.environ.get("DAST_SERVICE_NAME") == "user-access-service" else "/user-access/api/auth"
    def post(action):
        request = Request(base + prefix + "/" + action,
                          data=json.dumps({"username": username, "password": password}).encode(),
                          headers={"Content-Type": "application/json"}, method="POST")
        with opener.open(request, timeout=30) as response:
            if action == "signup" and response.status != 201:
                raise ValueError("Verification signup did not create an account")
            return json.load(response)
    try:
        result = post("login")
    except HTTPError as error:
        if error.code != 401 or os.environ.get("MMIV_SMOKE_AUTO_SIGNUP") != "true":
            raise
        # Generated credentials only: never modify or reset an existing account.
        post("signup")
        result = post("login")
    token = result.get("accessToken")
    if (not isinstance(token, str)
            or not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token)
            or result.get("tokenType", "").lower() != "bearer"):
        raise ValueError("Login did not return a valid Bearer JWT")
    try:
        payload = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        user_id = claims['sub']
        if not isinstance(user_id, str) or not user_id or any(c in user_id for c in "\r\n"):
            raise ValueError("Invalid subject")
    except Exception:
        raise ValueError("Login JWT did not contain a valid user ID") from None
    expected = os.environ.get("USER_ACCESS_TEST_USER_ID")
    if expected and expected != user_id:
        raise ValueError("Configured verification user ID does not match the login account")
    # The subject comes from the issuer's response through the authenticated SSM
    # tunnel. Transfer subsequently verifies the JWT signature on actual API calls.
    path = Path(destination)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write("API_TEST_TOKEN=" + token + "\nZAP_AUTH_HEADER_VALUE=Bearer " + token
                     + "\nZAP_AUTH_HEADER_SITE=127.0.0.1\nUSER_ACCESS_TEST_USER_ID=" + user_id + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Authentication failures must not print credentials or response bodies.
        print("Verification login failed: " + (str(error) if isinstance(error, ValueError)
                                              else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
