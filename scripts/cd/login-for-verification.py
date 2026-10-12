#!/usr/bin/env python3
"""Obtain a real User Access JWT without recording credentials in reports."""
import json
import os
import re
from pathlib import Path
import sys
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
    body = json.dumps({"username": username, "password": password}).encode()
    request = Request(base + "/user-access/api/auth/login", data=body,
                      headers={"Content-Type": "application/json"}, method="POST")
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    with build_opener(NoRedirect()).open(request, timeout=30) as response:
        result = json.load(response)
    token = result.get("accessToken")
    if (not isinstance(token, str)
            or not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token)
            or result.get("tokenType", "").lower() != "bearer"):
        raise ValueError("Login did not return a valid Bearer JWT")
    path = Path(destination)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write("API_TEST_TOKEN=" + token + "\nZAP_AUTH_HEADER_VALUE=Bearer " + token
                     + "\nZAP_AUTH_HEADER_SITE=127.0.0.1\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Authentication failures must not print credentials or response bodies.
        print("Verification login failed: " + (str(error) if isinstance(error, ValueError)
                                              else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
