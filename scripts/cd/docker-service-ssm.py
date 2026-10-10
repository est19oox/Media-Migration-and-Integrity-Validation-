#!/usr/bin/env python3
"""Deploy/restore a Docker image on EC2; no Ansible service lifecycle required."""
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

REMOTE_TIMEOUT = 1800
RESULT_TIMEOUT = REMOTE_TIMEOUT + 50

PORTS = {'service-registry': '8761', 'api-gateway': '8080', 'transfer-service': '8081',
         'integrity-service': '8082', 'user-access-service': '8083',
         'reporting-service': '8084', 'discovery-service': '8085'}


def validate(state, instance, commit):
    name = state['SERVICE_NAME']
    if (name not in PORTS or state['SERVICE_PORT'] != PORTS[name]
            or state['INSTANCE_ID'] != instance or state['GITHUB_SHA'] != commit
            or not state['SERVICE_IMAGE'].endswith(':' + name + '-' + commit)
            or not state['SERVICE_IMAGE'].startswith(state['ECR_REGISTRY'] + '/')):
        raise ValueError('Deployment state does not match this service/instance/commit')
    return state


def aws(*args):
    return subprocess.run(['aws', 'ssm', *args, '--region', os.getenv('AWS_REGION', 'ap-southeast-1'),
                           '--cli-connect-timeout', '10', '--cli-read-timeout', '20'],
                          capture_output=True, text=True)


def lifecycle(action, state):
    source = Path(__file__).with_name('docker-service-remote.py').read_bytes()
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
    evidence = Path('.ci/reports/docker')
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / (state['SERVICE_NAME'] + '-' + action + '-command.json')).write_text(
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
            (evidence / (state['SERVICE_NAME'] + '-' + action + '-result.json')).write_text(
                json.dumps(invocation, indent=2) + '\n')
            if invocation['Status'] == 'Success':
                if invocation['ResponseCode'] != 0:
                    raise RuntimeError('SSM reported success with a nonzero response code')
                for line in invocation.get('StandardOutputContent', '').splitlines():
                    if line.startswith('MMIV_DEPLOYMENT_STATE='):
                        return json.loads(line.split('=', 1)[1])
                raise RuntimeError('Docker lifecycle succeeded without deployment state')
            if invocation['Status'] in ('Failed', 'Cancelled', 'TimedOut'):
                print(invocation.get('StandardErrorContent', ''), file=sys.stderr)
                raise RuntimeError('Remote Docker lifecycle failed')
        time.sleep(1 if attempt < 5 else 2 if attempt < 10 else 5)
        attempt += 1
    raise RuntimeError('Timed out waiting for Docker lifecycle; inspect SSM command ' + command_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('deploy', 'rollback'))
    parser.add_argument('--service', choices=PORTS, default=os.getenv('SERVICE_NAME'))
    args = parser.parse_args()
    if args.service not in PORTS:
        parser.error('Specify a known service with --service or SERVICE_NAME')
    if args.action == 'deploy' and args.service != os.getenv('SERVICE_NAME'):
        parser.error('--service must match SERVICE_NAME for deployment')
    path = Path('.ci/deployments') / (args.service + '.json')
    if args.action == 'deploy':
        state = {name: os.environ[name] for name in ('SERVICE_NAME', 'SERVICE_PORT', 'INSTANCE_ID',
                                                   'SERVICE_IMAGE', 'ECR_REGISTRY', 'GITHUB_SHA')}
        state.update(AWS_REGION=os.getenv('AWS_REGION', 'ap-southeast-1'),
                     MMIV_BUCKET_NAME=os.getenv('MMIV_BUCKET_NAME', 'media-migration-dev-landing'),
                     ORACLE_CONFIG_STAGED=os.getenv('ORACLE_CONFIG_STAGED', 'false') == 'true')
    else:
        state = json.loads(path.read_text())
    validate(state, os.environ['INSTANCE_ID'], os.environ['GITHUB_SHA'])
    state = lifecycle(args.action, state)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2) + '\n')
    if os.getenv('GITHUB_OUTPUT') and args.action == 'deploy':
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('deployment_changed=' + str(state['DEPLOYMENT_CHANGED']).lower() + '\n')
            output.write('rollback_available=' + str(state['ROLLBACK_AVAILABLE']).lower() + '\n')
    print(args.action + ' completed: ' + args.service)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
