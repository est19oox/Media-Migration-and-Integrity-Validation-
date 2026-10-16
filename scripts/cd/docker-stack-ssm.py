#!/usr/bin/env python3
"""Prepare/deploy/restore one Compose release on EC2 through SSM."""
import argparse
import base64
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time

import re
import yaml

REMOTE_TIMEOUT = 3600
RESULT_TIMEOUT = REMOTE_TIMEOUT + 50
PORTS = {'service-registry': '8761', 'api-gateway': '8080', 'transfer-service': '8081',
         'integrity-service': '8082', 'user-access-service': '8083',
         'reporting-service': '8084', 'discovery-service': '8085'}
ROOT = Path(__file__).resolve().parents[2]


def expected_services():
    jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
    return {service['name'] for service in jobs['build-images']['strategy']['matrix']['service']}


def validate(state, instance, commit):
    if (state['INSTANCE_ID'] != instance or state['GITHUB_SHA'] != commit
            or not re.fullmatch(r'[a-zA-Z0-9-]{1,120}', state['RELEASE_ID'])
            or set(state['services']) != expected_services()
            or not {'service-registry', 'api-gateway'} <= set(state['services'])):
        raise ValueError('Release does not match this instance/commit/enabled service set')
    for name, service in state['services'].items():
        if (name not in PORTS or service['port'] != PORTS[name]
                or not service['image'].startswith(state['ECR_REGISTRY'] + '/')
                or not service['image'].endswith(':' + name + '-' + commit)):
            raise ValueError('Release image or port does not match: ' + name)
    return state


def prepare(directory, instance, commit, release_id):
    images = {}
    registry = None
    for path in sorted(directory.glob('*.json')):
        image = json.loads(path.read_text())
        name = image['SERVICE_NAME']
        if path.stem != name or name in images or image['GITHUB_SHA'] != commit:
            raise ValueError('Unexpected/duplicate/stale image manifest')
        if registry is not None and registry != image['ECR_REGISTRY']:
            raise ValueError('Images must come from the same registry')
        registry = image['ECR_REGISTRY']
        images[name] = {'image': image['SERVICE_IMAGE'], 'port': image['SERVICE_PORT']}
    state = {'INSTANCE_ID': instance, 'GITHUB_SHA': commit, 'RELEASE_ID': release_id,
             'ECR_REGISTRY': registry, 'AWS_REGION': os.getenv('AWS_REGION', 'ap-southeast-1'),
             'MMIV_BUCKET_NAME': os.getenv('MMIV_BUCKET_NAME', 'media-migration-dev-landing'),
             'ORACLE_CONFIG_STAGED': os.getenv('ORACLE_CONFIG_STAGED', 'false') == 'true',
             'MANAGED_USER_ACCESS_ORACLE': os.getenv('MANAGED_USER_ACCESS_ORACLE', 'true') == 'true',
             'USER_ACCESS_JWT_ISSUER': os.getenv('USER_ACCESS_JWT_ISSUER', 'https://auth.mmiv.example'),
             'USER_ACCESS_JWT_KEY_ID': os.getenv('USER_ACCESS_JWT_KEY_ID', 'mmiv-user-access-key-1'),
             'JWT_COOKIE_SECURE': os.getenv('JWT_COOKIE_SECURE', 'false'),
             'services': images, 'READY': False, 'ROLLED_BACK': False}
    return validate(state, instance, commit)


def aws(*args):
    return subprocess.run(['aws', 'ssm', *args, '--region', os.getenv('AWS_REGION', 'ap-southeast-1'),
                           '--cli-connect-timeout', '10', '--cli-read-timeout', '20'],
                          capture_output=True, text=True)


def lifecycle(action, state):
    source = Path(__file__).with_name('docker-stack-remote.py').read_bytes()
    encoded = base64.b64encode(source).decode()
    launch = 'import base64; exec(compile(base64.b64decode(' + repr(encoded) + "), '<docker-lifecycle>', 'exec'))"
    shell = 'python3 -c ' + shlex.quote(launch) + ' ' + shlex.quote(action) + ' ' + shlex.quote(json.dumps(state))
    # Temporary file contains only image/config identities, never Oracle secrets.
    with tempfile.TemporaryDirectory() as directory:
        parameters = Path(directory) / 'parameters.json'
        parameters.write_text(json.dumps({'commands': [shell], 'executionTimeout': [str(REMOTE_TIMEOUT)]}))
        parameters.chmod(0o600)
        result = aws('send-command', '--instance-ids', state['INSTANCE_ID'],
                     '--document-name', 'AWS-RunShellScript', '--parameters', 'file://' + str(parameters),
                     '--query', 'Command.CommandId', '--output', 'text')
        if result.returncode:
            raise RuntimeError('SSM could not submit the Docker lifecycle command')
        command_id = result.stdout.strip()
    print(f'SSM {action} command submitted: {command_id}; instance={state["INSTANCE_ID"]}', flush=True)
    evidence = Path('.ci/reports/docker')
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / ('release-' + action + '-command.json')).write_text(
        json.dumps({'command_id': command_id, 'instance_id': state['INSTANCE_ID'], 'action': action}) + '\n')
    deadline = time.monotonic() + RESULT_TIMEOUT
    attempt = 0
    while time.monotonic() < deadline:
        result = aws('get-command-invocation', '--command-id', command_id,
                     '--instance-id', state['INSTANCE_ID'], '--output', 'json')
        if result.returncode:
            if 'InvocationDoesNotExist' not in result.stderr:
                raise RuntimeError('SSM could not read the Docker lifecycle result')
        else:
            invocation = json.loads(result.stdout)
            (evidence / ('release-' + action + '-result.json')).write_text(
                json.dumps(invocation, indent=2) + '\n')
            if invocation['Status'] in ('Success', 'Failed', 'Cancelled', 'TimedOut'):
                print(f'SSM {action} completed: status={invocation["Status"]}; response_code={invocation.get("ResponseCode", -1)}', flush=True)
                for line in invocation.get('StandardOutputContent', '').splitlines():
                    if line.startswith('MMIV_RELEASE_STATE='):
                        outcome = json.loads(line.split('=', 1)[1])
                        outcome['COMMAND_STATUS'] = invocation['Status']
                        outcome['COMMAND_RESPONSE_CODE'] = invocation.get('ResponseCode', -1)
                        return outcome
                raise RuntimeError('SSM lifecycle ended without release state; command ' + command_id)
        time.sleep(1 if attempt < 5 else 2 if attempt < 10 else 5)
        attempt += 1
    raise RuntimeError('Timed out waiting for Docker lifecycle; inspect SSM command ' + command_id)



def report_outcome(action, state):
    print(f'{action}: ready={bool(state.get("READY"))}; rolled_back={bool(state.get("ROLLED_BACK"))}', flush=True)
    if state.get('error') or (action == 'deploy' and not state.get('READY')):
        # Selected fields only; never print the full SSM response, command or env.
        print('Deployment failure: ' + str(state.get('error', 'UnknownError')) +
              '; phase=' + str(state.get('failure_phase', 'unavailable')), file=sys.stderr)
        print(str(state.get('error_detail', 'Inspect the release deployment evidence for this run.')), file=sys.stderr)
        if 'failed_operation' in state:
            print('Failed operation: ' + str(state['failed_operation']) +
                  '; exit_code=' + str(state.get('command_exit_code', 'unknown')), file=sys.stderr)
        for name, details in state.get('container_states', {}).items():
            if name in PORTS or name == 'oracle':
                print(name + ': ' + json.dumps({key: details.get(key) for key in
                    ('status', 'health', 'exit_code', 'oom_killed')}), file=sys.stderr)
        if state.get('recovery_error'):
            print('Rollback failure: ' + str(state['recovery_error']), file=sys.stderr)
        print('See release-deployment-evidence artifact: release-' + action + '-result.json', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description='Prepare/deploy/restore the complete Docker release')
    parser.add_argument('action', choices=('prepare', 'deploy', 'rollback'))
    parser.add_argument('--image-dir', type=Path, default=Path('.ci/images'))
    parser.add_argument('--manifest', type=Path, default=Path('.ci/deployments/release.json'))
    args = parser.parse_args()
    instance, commit = os.environ['INSTANCE_ID'], os.environ['GITHUB_SHA']
    print(f'Starting {args.action} for instance={instance}; commit={commit}', flush=True)
    if args.action == 'prepare':
        release_id = commit + '-' + os.getenv('GITHUB_RUN_ID', 'local') + '-' + os.getenv('GITHUB_RUN_ATTEMPT', '1')
        state = prepare(args.image_dir, instance, commit, release_id)
    else:
        state = validate(json.loads(args.manifest.read_text()), instance, commit)
        state = lifecycle(args.action, state)
        validate(state, instance, commit)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(state, indent=2) + '\n')
    if args.action == 'prepare':
        print(f'Release prepared: {len(state["services"])} images; manifest={args.manifest}', flush=True)
    else:
        report_outcome(args.action, state)
    if args.action == 'deploy':
        return int(not state.get('READY') or state.get('COMMAND_STATUS') != 'Success'
                   or state.get('COMMAND_RESPONSE_CODE') != 0)
    if args.action == 'rollback':
        return int(state.get('COMMAND_STATUS') != 'Success' or bool(state.get('recovery_error')))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
