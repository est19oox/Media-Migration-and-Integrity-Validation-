#!/usr/bin/env python3
"""Reconcile only the existing deployment role's scoped SQS provisioning policy."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
ROLE = 'GitHubActions-MMIV-Terraform'
POLICY_NAME = 'MMIV-File-Log-Queue-Provisioning'


def aws(*args):
    return subprocess.run(['aws', *args, '--output', 'json'], capture_output=True,
                          text=True, timeout=60)


def ensure():
    identity_result = aws('sts', 'get-caller-identity')
    if identity_result.returncode:
        raise RuntimeError('Could not determine AWS identity; check OIDC authentication')
    identity = json.loads(identity_result.stdout)
    account = identity.get('Account', '')
    region = os.environ.get('AWS_REGION', os.environ.get('AWS_DEFAULT_REGION', 'ap-southeast-1'))
    if not re.fullmatch(r'[0-9]{12}', account) or region != 'ap-southeast-1':
        raise RuntimeError('This policy setup requires the MMIV ap-southeast-1 deployment region')
    if not re.fullmatch(r'arn:aws:sts::' + account + r':assumed-role/' + ROLE + r'/[^/]+',
                        identity.get('Arn', '')):
        raise RuntimeError('Policy setup must run as the existing GitHubActions-MMIV-Terraform role')
    template = (ROOT/'infrastructure/bootstrap/policies/file-log-queue-provisioning.json').read_text()
    desired = json.loads(template.replace('${aws_region}', region).replace('${aws_account_id}', account))
    existing = aws('iam', 'get-role-policy', '--role-name', ROLE, '--policy-name', POLICY_NAME)
    if existing.returncode == 0:
        document = json.loads(existing.stdout)['PolicyDocument']
        if isinstance(document, str):
            document = json.loads(unquote(document))
        if document == desired:
            print('SQS provisioning policy is already current.')
            return
    elif 'NoSuchEntity' not in existing.stderr:
        raise RuntimeError('Cannot read Terraform role policy; an AWS administrator must allow iam:GetRolePolicy')
    result = aws('iam', 'put-role-policy', '--role-name', ROLE, '--policy-name', POLICY_NAME,
                 '--policy-document', json.dumps(desired))
    if result.returncode:
        raise RuntimeError('Cannot attach SQS provisioning policy; an AWS administrator must allow iam:PutRolePolicy or apply the bootstrap policy')
    print('SQS provisioning policy updated for the two MMIV log queues.')
    print('IAM changes may need time to propagate; retry with the same Terraform backend if AWS still denies queue access.')


if __name__ == '__main__':
    try:
        ensure()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
