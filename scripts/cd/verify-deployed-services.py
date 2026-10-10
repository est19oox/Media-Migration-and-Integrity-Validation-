#!/usr/bin/env python3
"""Verify after the deployment barrier; restore changed images after failure."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import yaml
from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[2]
spec = spec_from_file_location('docker_ssm', Path(__file__).with_name('docker-service-ssm.py'))
docker_ssm = module_from_spec(spec)
spec.loader.exec_module(docker_ssm)


def expected_services():
    jobs = yaml.safe_load((ROOT / '.github/workflows/cicd.yml').read_text())['jobs']
    return {'service-registry', 'api-gateway'} | {
        service['name'] for service in jobs['deploy-backends']['strategy']['matrix']['service']}


def states(directory, instance, commit):
    result = []
    for file in sorted(directory.glob('*.json')):
        state = docker_ssm.validate(json.loads(file.read_text()), instance, commit)
        if file.stem != state['SERVICE_NAME']:
            raise ValueError('Unexpected deployment state filename')
        for key in ('DEPLOYMENT_CHANGED', 'ROLLBACK_AVAILABLE', 'ROLLED_BACK'):
            if not isinstance(state.get(key), bool):
                raise ValueError('Missing deployment lifecycle flag: ' + key)
        result.append(state)
    if not result:
        raise ValueError('No deployment states were downloaded')
    return result


def verify(deployments, report_root):
    names = {state['SERVICE_NAME'] for state in deployments if not state['ROLLED_BACK']}
    if names != expected_services():
        raise ValueError('Deployment barrier incomplete; required=' + str(sorted(expected_services()))
                         + ', available=' + str(sorted(names)))
    results = []
    # Run scans sequentially. Rollback starts only after all scans finish.
    for state in sorted(deployments, key=lambda s: (
            s['SERVICE_NAME'] == 'api-gateway', int(s['SERVICE_PORT']))):
        name = state['SERVICE_NAME']
        directory = report_root / name
        directory.mkdir(parents=True, exist_ok=True)
        env = {**os.environ, 'INSTANCE_ID': state['INSTANCE_ID'],
               'DAST_SERVICE_NAME': name, 'DAST_PORT': state['SERVICE_PORT'],
               'DAST_REPORT_DIR': str(directory.resolve()),
               'DAST_SCAN_MODE': 'api' if name == 'transfer-service' else 'baseline',
               'DAST_PATH': '/' if name == 'service-registry' else '/upload.html' if name == 'api-gateway' else '/actuator/health',
               'DAST_READY_PATH': '/actuator/health'}
        print('Verifying ' + name, flush=True)
        with (directory / 'dast.log').open('w') as log:
            process = subprocess.Popen(['bash', str(ROOT / 'scripts/cd/run-dast-ssm.sh')],
                                       cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            for line in process.stdout:
                print(line, end='', flush=True)
                log.write(line)
            code = process.wait()
        results.append({'service': name, 'exit_code': code})
    (report_root / 'verification-summary.json').write_text(json.dumps(results, indent=2) + '\n')
    return int(any(result['exit_code'] for result in results))


def rollback(deployments, directory, report_root):
    results = []
    # Gateway restores first; the registry stays available until backends restore.
    order = {'api-gateway': 0, 'service-registry': 2}
    for state in sorted(deployments, key=lambda s: (order.get(s['SERVICE_NAME'], 1), -int(s['SERVICE_PORT']))):
        name = state['SERVICE_NAME']
        if state['ROLLED_BACK'] or not state['DEPLOYMENT_CHANGED']:
            status = 'UNCHANGED_OR_ALREADY_RESTORED'
        elif not state['ROLLBACK_AVAILABLE']:
            status = 'NO_PREVIOUS_DEPLOYMENT'
        else:
            try:
                restored = docker_ssm.lifecycle('rollback', state)
                (directory / (name + '.json')).write_text(json.dumps(restored, indent=2) + '\n')
                status = 'RESTORED'
            except Exception as error:
                print('Rollback failed for ' + name + ': ' + str(error), file=sys.stderr)
                status = 'RESTORE_FAILED'
        print(name + ': ' + status, flush=True)
        results.append({'service': name, 'status': status})
    (report_root / 'rollback-summary.json').write_text(json.dumps(results, indent=2) + '\n')
    return int(any(result['status'] == 'RESTORE_FAILED' for result in results))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('verify', 'rollback'))
    parser.add_argument('--instance-id', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--manifest-dir', type=Path, default=Path('.ci/deployments'))
    args = parser.parse_args()
    report_root = ROOT / '.ci/reports/verification'
    report_root.mkdir(parents=True, exist_ok=True)
    deployments = states(args.manifest_dir, args.instance_id, args.commit)
    return verify(deployments, report_root) if args.action == 'verify' else rollback(deployments, args.manifest_dir, report_root)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
