#!/usr/bin/env python3
"""Load one GitHub JSON secret without recording its values in build evidence."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


def section(config, name, required=False):
    value = config.get(name)
    if value is None and not required:
        return None
    if not isinstance(value, dict):
        raise ValueError('Invalid ' + name + ' configuration')
    return value


def text(settings, name, multiline=False):
    value = settings.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Missing configuration field: ' + name)
    if not multiline and ('\r' in value or '\n' in value):
        raise ValueError('Configuration must be single-line: ' + name)
    return value


def values(config, mode):
    if not isinstance(config, dict):
        raise ValueError('MMIV_RUNTIME_CONFIG must be a JSON object')
    output = {}
    if mode == 'deployment':
        access = section(config, 'userAccess')
        output['MANAGED_USER_ACCESS_ORACLE'] = 'false' if access is not None else 'true'
        integrity = section(config, 'integrity')
        output['ORACLE_CONFIG_REQUESTED'] = 'true' if integrity is not None else 'false'
        if access is None:
            if integrity is not None:
                for key, name in [('dbUrl', 'DB_URL'), ('dbUsername', 'DB_USERNAME'), ('dbPassword', 'DB_PASSWORD')]:
                    output['INTEGRITY_' + name] = text(integrity, key)
            return output
        for key, name in [('dbUrl', 'DB_URL'), ('dbUsername', 'DB_USERNAME'), ('dbPassword', 'DB_PASSWORD')]:
            output['USER_ACCESS_' + name] = text(access, key)
        private = text(access, 'jwtPrivateKey', multiline=True).strip()
        if not private.startswith('-----BEGIN PRIVATE KEY-----') or not private.endswith('-----END PRIVATE KEY-----'):
            raise ValueError('jwtPrivateKey must be unencrypted PKCS8 PEM')
        public = access.get('jwtPublicKey')
        if public is None:
            result = subprocess.run(['openssl', 'pkey', '-pubout'], input=private, text=True,
                                    capture_output=True, timeout=10)
            if result.returncode:
                raise ValueError('Could not derive the JWT public key')
            public = result.stdout.strip()
        if not isinstance(public, str) or not public.strip().startswith('-----BEGIN PUBLIC KEY-----') or not public.strip().endswith('-----END PUBLIC KEY-----'):
            raise ValueError('jwtPublicKey must be X509 PEM')
        output.update(USER_ACCESS_JWT_PRIVATE_KEY=private, USER_ACCESS_JWT_PUBLIC_KEY=public.strip())
        integrity = section(config, 'integrity')
        output['ORACLE_CONFIG_REQUESTED'] = 'true' if integrity is not None else 'false'
        if integrity is not None:
            for key, name in [('dbUrl', 'DB_URL'), ('dbUsername', 'DB_USERNAME'), ('dbPassword', 'DB_PASSWORD')]:
                output['INTEGRITY_' + name] = text(integrity, key)
    else:
        smoke = section(config, 'userAccessSmoke')
        if smoke is not None:
            for key, name in [('username', 'USERNAME'), ('password', 'PASSWORD')]:
                output['USER_ACCESS_TEST_' + name] = text(smoke, key)
            if smoke.get('destination') is not None:
                output['MMIV_VERIFICATION_DESTINATION'] = text(smoke, 'destination')
            if smoke.get('userId') is not None:
                output['USER_ACCESS_TEST_USER_ID'] = text(smoke, 'userId')
    return output


def export(settings, environment):
    # Mask every line too: multiline PEM values are rendered line-by-line by tools.
    for name, value in settings.items():
        if name in ("ORACLE_CONFIG_REQUESTED", "MANAGED_USER_ACCESS_ORACLE"):
            continue
        for item in [value, *value.splitlines()]:
            escaped = item.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::add-mask::' + escaped)
    with environment.open('a', encoding='utf-8') as destination:
        for name, value in settings.items():
            delimiter = 'MMIV_' + uuid.uuid4().hex
            destination.write(name + '<<' + delimiter + '\n' + value + '\n' + delimiter + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('deployment', 'verification'))
    args = parser.parse_args()
    try:
        raw = os.environ.get('MMIV_RUNTIME_CONFIG', '')
        config = json.loads(raw) if raw else {}
        settings = values(config, args.mode)
        export(settings, Path(os.environ['GITHUB_ENV']))
        if args.mode == 'deployment' and os.environ.get('GITHUB_OUTPUT'):
            with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
                output.write('managed_user_access_oracle=' + settings['MANAGED_USER_ACCESS_ORACLE'] + '\n')
    except Exception:
        # Parser/crypto errors can contain secret input; never print their details.
        print('::error::Invalid MMIV_RUNTIME_CONFIG; check the documented JSON fields and PEM key format.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
