#!/usr/bin/env python3
"""Grant only the cached, generated test account its configured demo destination over SSM."""
import base64
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import uuid


def grant_sql(account, destination):
    identifier = str(uuid.UUID(account['userId']))
    username = account['username']
    if not re.fullmatch(r'mmiv_ci_[a-f0-9]{32}', username):
        raise ValueError('Only a generated verification account can receive an automatic grant')
    if not re.fullmatch(r'mmiv-verification-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', destination):
        raise ValueError('Invalid verification destination')
    return f"""WHENEVER SQLERROR EXIT FAILURE ROLLBACK
SET HEADING OFF FEEDBACK OFF
DECLARE
  found NUMBER;
BEGIN
  SELECT COUNT(*) INTO found FROM APP_USERS
    WHERE USER_ID='{identifier}' AND USERNAME='{username}' AND ROLE='USER' AND ACTIVE=1;
  IF found != 1 THEN RAISE_APPLICATION_ERROR(-20001,'Generated verification account not found'); END IF;
  SELECT COUNT(*) INTO found FROM USER_DESTINATION_ACCESS WHERE DESTINATION='{destination}' AND USER_ID<>'{identifier}';
  IF found != 0 THEN RAISE_APPLICATION_ERROR(-20002,'Verification folder belongs to another account'); END IF;
  MERGE INTO MMIV_FOLDERS target
  USING (SELECT '{destination}' NAME FROM dual) source
  ON (target.NAME=source.NAME)
  WHEN NOT MATCHED THEN INSERT (NAME) VALUES (source.NAME);
  MERGE INTO USER_DESTINATION_ACCESS target
  USING (SELECT '{identifier}' USER_ID, '{destination}' DESTINATION FROM dual) source
  ON (target.USER_ID=source.USER_ID AND target.DESTINATION=source.DESTINATION)
  WHEN MATCHED THEN UPDATE SET PERMISSION='WRITE'
  WHEN NOT MATCHED THEN INSERT (USER_ID,DESTINATION,PERMISSION) VALUES (source.USER_ID,source.DESTINATION,'WRITE');
  COMMIT;
END;
/
EXIT
"""


def main():
    cache = Path(os.environ['MMIV_VERIFICATION_ACCOUNT_FILE'])
    if not cache.exists():  # Caller-supplied accounts are never modified.
        return
    marker = cache.with_suffix('.permission-granted')
    if marker.exists():
        return
    if os.getenv('MMIV_MANAGED_VERIFICATION_DB', 'true') != 'true':
        raise ValueError('External Oracle verification requires a supplied account with destination permission')
    sql = grant_sql(json.loads(cache.read_text()), os.environ['MMIV_VERIFICATION_DESTINATION'])
    payload = base64.b64encode(sql.encode()).decode()
    source = ("import base64,subprocess;subprocess.run(['docker','exec','-i','oracle','bash','-c',"
              + repr('exec sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost:1521/FREEPDB1"')
              + "],input=base64.b64decode(" + repr(payload) + "),check=True,capture_output=True)")
    command = 'python3 -c ' + shlex.quote(source)
    region = os.environ.get('AWS_REGION', 'ap-southeast-1')
    def aws(*args):
        return subprocess.run(['aws', 'ssm', *args, '--region', region], check=True, capture_output=True, text=True, timeout=40)
    with tempfile.TemporaryDirectory() as directory:
        parameters = Path(directory) / 'parameters.json'
        parameters.write_text(json.dumps({'commands': [command], 'executionTimeout': ['120']}))
        parameters.chmod(0o600)
        identifier = aws('send-command', '--instance-ids', os.environ['INSTANCE_ID'], '--document-name',
                         'AWS-RunShellScript', '--parameters', 'file://' + str(parameters), '--query', 'Command.CommandId', '--output', 'text').stdout.strip()
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            result = json.loads(aws('get-command-invocation', '--command-id', identifier,
                                   '--instance-id', os.environ['INSTANCE_ID'], '--output', 'json').stdout)
        except subprocess.CalledProcessError as error:
            if 'InvocationDoesNotExist' in error.stderr:
                time.sleep(2)
                continue
            raise RuntimeError('Could not read verification grant command status') from None
        if result['Status'] == 'Success' and result.get('ResponseCode') == 0:
            marker.write_text('granted\n'); marker.chmod(0o600); return
        if result['Status'] not in ('Pending', 'InProgress', 'Delayed'):
            raise RuntimeError('Could not grant the generated verification account its destination permission')
        time.sleep(2)
    raise TimeoutError('Verification destination grant timed out')


if __name__ == '__main__':
    main()
