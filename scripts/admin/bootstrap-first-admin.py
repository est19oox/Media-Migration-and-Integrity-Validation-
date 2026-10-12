#!/usr/bin/env python3
"""Issue the first-admin invitation via authorized SSM, without a public bootstrap endpoint."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tempfile
import time
from urllib.parse import urlsplit
import uuid


def invitation_sql(user_id, token_hash, replace_expired=False):
    user_id = str(uuid.UUID(user_id))
    if not re.fullmatch(r'[0-9a-f]{64}', token_hash):
        raise ValueError('Invalid token hash')
    replacement = '1' if replace_expired else '0'
    return f"""WHENEVER SQLERROR EXIT FAILURE ROLLBACK
SET HEADING OFF FEEDBACK OFF
DECLARE
  found NUMBER;
  previous_hash VARCHAR2(64);
  previous_expiry TIMESTAMP;
  previous_consumed TIMESTAMP;
BEGIN
  LOCK TABLE APP_USERS IN EXCLUSIVE MODE WAIT 10;
  LOCK TABLE ADMIN_BOOTSTRAP_STATE IN EXCLUSIVE MODE WAIT 10;
  SELECT COUNT(*) INTO found FROM APP_USERS WHERE ROLE='ADMIN';
  IF found != 0 THEN RAISE_APPLICATION_ERROR(-20001,'An administrator already exists'); END IF;
  SELECT COUNT(*) INTO found FROM APP_USERS WHERE USER_ID='{user_id}'
    AND ROLE='USER' AND ACTIVE=1 AND PASSWORD_HASH IS NOT NULL
    AND NOT REGEXP_LIKE(USERNAME,'^mmiv_ci_[0-9a-f]{{32}}$','c');
  IF found != 1 THEN RAISE_APPLICATION_ERROR(-20002,'Register an ordinary active account first'); END IF;
  SELECT COUNT(*) INTO found FROM ADMIN_BOOTSTRAP_STATE WHERE BOOTSTRAP_ID=1;
  IF found != 0 THEN
    IF {replacement} != 1 THEN RAISE_APPLICATION_ERROR(-20003,'First invitation was already issued'); END IF;
    SELECT TOKEN_HASH INTO previous_hash FROM ADMIN_BOOTSTRAP_STATE WHERE BOOTSTRAP_ID=1;
    SELECT EXPIRES_AT,CONSUMED_AT INTO previous_expiry,previous_consumed
      FROM ADMIN_INVITATIONS WHERE TOKEN_HASH=previous_hash FOR UPDATE;
    IF previous_consumed IS NOT NULL OR previous_expiry>SYSTIMESTAMP THEN
      RAISE_APPLICATION_ERROR(-20004,'Only an expired unused first invitation can be replaced');
    END IF;
    UPDATE ADMIN_BOOTSTRAP_STATE SET ADMIN_USER_ID='{user_id}',TOKEN_HASH='{token_hash}',CREATED_AT=SYSTIMESTAMP
      WHERE BOOTSTRAP_ID=1;
  ELSE
    INSERT INTO ADMIN_BOOTSTRAP_STATE (BOOTSTRAP_ID,ADMIN_USER_ID,TOKEN_HASH)
      VALUES (1,'{user_id}','{token_hash}');
  END IF;
  INSERT INTO ADMIN_INVITATIONS (TOKEN_HASH,USER_ID,EXPIRES_AT)
    VALUES ('{token_hash}','{user_id}',SYSTIMESTAMP+INTERVAL '24' HOUR);
  COMMIT;
END;
/
EXIT
"""


def remote_command(sql):
    payload = base64.b64encode(sql.encode()).decode()
    shell = 'exec sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost:1521/FREEPDB1"'
    # Only the hash and target ID reach SSM; the plaintext invitation stays with the operator.
    source = ("import base64,subprocess;subprocess.run(['docker','exec','-i','oracle','bash','-c',"
              + repr(shell) + "],input=base64.b64decode(" + repr(payload) + "),check=True,capture_output=True)")
    return 'python3 -c ' + shlex.quote(source)


def issue_over_ssm(instance_id, region, sql):
    if not re.fullmatch(r'i-[0-9a-f]{8,17}', instance_id):
        raise ValueError('Invalid EC2 instance ID')
    def aws(*args):
        return subprocess.run(['aws', 'ssm', *args, '--region', region], check=True,
                              capture_output=True, text=True, timeout=40).stdout
    with tempfile.TemporaryDirectory(prefix='mmiv-admin-') as directory:
        parameters = Path(directory) / 'parameters.json'
        parameters.write_text(json.dumps({'commands': [remote_command(sql)], 'executionTimeout': ['120']}))
        parameters.chmod(0o600)
        command_id = aws('send-command', '--instance-ids', instance_id, '--document-name', 'AWS-RunShellScript',
                         '--parameters', 'file://' + str(parameters), '--query', 'Command.CommandId', '--output', 'text').strip()
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            result = json.loads(aws('get-command-invocation', '--command-id', command_id,
                                   '--instance-id', instance_id, '--output', 'json'))
        except subprocess.CalledProcessError as error:
            if 'InvocationDoesNotExist' in error.stderr:
                time.sleep(2)
                continue
            raise RuntimeError('Could not read first-admin invitation status') from None
        if result['Status'] == 'Success' and result.get('ResponseCode') == 0:
            return
        if result['Status'] not in ('Pending', 'InProgress', 'Delayed'):
            raise RuntimeError('First-admin invitation rejected; check the account, existing administrators, or previous invitation')
        time.sleep(2)
    raise TimeoutError('First-admin invitation timed out; inspect SSM status before retrying')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance-id', required=True)
    parser.add_argument('--user-id', required=True, help='UUID returned by normal signup')
    parser.add_argument('--base-url', required=True, help='Gateway origin reachable by the invited administrator')
    parser.add_argument('--region', default='ap-southeast-1')
    parser.add_argument('--replace-expired', action='store_true', help='Replace an expired, unused first invitation only')
    args = parser.parse_args()
    origin = urlsplit(args.base_url)
    if origin.scheme not in ('https', 'http') or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ('', '/'):
        parser.error('--base-url must be a Gateway origin without credentials, path, query, or fragment')
    token = secrets.token_hex(32)
    sql = invitation_sql(args.user_id, hashlib.sha256(token.encode('ascii')).hexdigest(), args.replace_expired)
    issue_over_ssm(args.instance_id, args.region, sql)
    print('First-admin invitation issued for the selected account. Expires in 24 hours.')
    print(args.base_url.rstrip('/') + '/admin.html#invite=' + token)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
