#!/usr/bin/env python3
"""Provision/reuse a verification account, then obtain a real login JWT privately."""
import argparse
import base64
import json
import os
import re
import secrets
from pathlib import Path
import sys
import uuid
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


def private_write(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base')
    parser.add_argument('destination', type=Path)
    parser.add_argument('--account-env', type=Path)
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    parsed = urlsplit(args.base)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port
            or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
        raise ValueError('Verification login must use the local SSM tunnel')
    prefix = os.environ.get('VERIFICATION_AUTH_BASE_PATH', '/user-access')
    if prefix not in ('', '/user-access'):
        raise ValueError('Invalid verification authentication route')
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    opener = build_opener(NoRedirect())
    def post(action, credentials):
        request = Request(args.base + prefix + '/api/auth/' + action,
                          data=json.dumps(credentials).encode(),
                          headers={'Content-Type': 'application/json'}, method='POST')
        with opener.open(request, timeout=30) as response:
            if action == 'signup' and response.status != 201:
                raise ValueError('Verification signup did not create a new account')
            return json.load(response)

    username = os.environ.get('USER_ACCESS_TEST_USERNAME')
    password = os.environ.get('USER_ACCESS_TEST_PASSWORD')
    cache = Path(os.environ.get('MMIV_VERIFICATION_ACCOUNT_FILE', str(args.destination) + '.account.json'))
    created_id = None
    if args.cleanup:
        if not cache.exists():
            return
        saved = json.loads(cache.read_text())
        username, password, created_id = saved['username'], saved['password'], saved['userId']
        if not re.fullmatch(r'mmiv_ci_[a-f0-9]{32}', username):
            raise ValueError('Refusing cleanup of a non-generated account')
    elif username or password:
        if not username or not password:
            raise ValueError('Supply both verification username and password, or neither')
    elif cache.exists():
        saved = json.loads(cache.read_text())
        username, password, created_id = saved['username'], saved['password'], saved['userId']
    else:
        username, password = 'mmiv_ci_' + uuid.uuid4().hex, secrets.token_urlsafe(32)
        result = post('signup', {'username': username, 'password': password})
        created_id = result.get('userId')
        if not isinstance(created_id, str) or not created_id or '\n' in created_id or '\r' in created_id:
            raise ValueError('Verification signup did not return a valid user ID')
        private_write(cache, json.dumps({'username': username, 'password': password, 'userId': created_id}))
    if not all(isinstance(v, str) and v and '\n' not in v and '\r' not in v for v in (username, password)):
        raise ValueError('Invalid verification account credentials')
    result = post('login', {'username': username, 'password': password})
    token = result.get('accessToken')
    if (not isinstance(token, str)
            or not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token)
            or result.get('tokenType', '').lower() != 'bearer'):
        raise ValueError('Login did not return a valid Bearer JWT')
    try:
        payload = token.split('.')[1]
        user_id = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['sub']
    except (ValueError, KeyError, TypeError):
        raise ValueError('Login token did not contain a valid user ID') from None
    if not isinstance(user_id, str) or not user_id or '\n' in user_id or '\r' in user_id:
        raise ValueError('Login token did not contain a valid user ID')
    if created_id is not None and user_id != created_id:
        raise ValueError('Login token does not match the verification account')
    if args.cleanup:
        request = Request(args.base + prefix + '/api/auth/verification-account', method='DELETE',
                          headers={'Authorization': 'Bearer ' + token})
        with opener.open(request, timeout=30) as response:
            if response.status != 204:
                raise ValueError('Verification account cleanup did not succeed')
        cache.unlink()
        print('Generated verification account deleted.')
        return
    private_write(args.destination, 'API_TEST_TOKEN=' + token + '\nZAP_AUTH_HEADER_VALUE=Bearer ' + token
                  + '\nZAP_AUTH_HEADER_SITE=127.0.0.1\n')
    if args.account_env:
        private_write(args.account_env, 'USER_ACCESS_TEST_USERNAME=' + username
                      + '\nUSER_ACCESS_TEST_PASSWORD=' + password + '\nUSER_ACCESS_TEST_USER_ID=' + user_id + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Verification login failed: ' + (str(error) if isinstance(error, ValueError)
                                              else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
