#!/usr/bin/env python3
"""Verify after the deployment barrier; restore changed images after failure."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[2]
spec = spec_from_file_location('docker_ssm', Path(__file__).with_name('docker-stack-ssm.py'))
docker_ssm = module_from_spec(spec)
spec.loader.exec_module(docker_ssm)


def expected_services():
    return docker_ssm.expected_services()


def states(directory, instance, commit):
    return docker_ssm.validate(json.loads((directory / 'release.json').read_text()), instance, commit)


def verify(deployments, report_root):
    if (deployments.get('READY') is not True or deployments.get('ROLLED_BACK') is not False
            or deployments.get('COMMAND_STATUS') != 'Success'
            or deployments.get('COMMAND_RESPONSE_CODE', 0) != 0
            or set(deployments['services']) != expected_services()):
        raise ValueError('Complete release readiness barrier failed; no scans started')
    results = []
    # Run scans sequentially. Rollback starts only after all scans finish.
    for name, item in sorted(deployments['services'].items(), key=lambda pair: (
            pair[0] == 'api-gateway', int(pair[1]['port']))):
        directory = report_root / name
        directory.mkdir(parents=True, exist_ok=True)
        env = {**os.environ, 'INSTANCE_ID': deployments['INSTANCE_ID'],
               'DAST_SERVICE_NAME': name, 'DAST_PORT': item['port'],
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
    if deployments.get('ROLLED_BACK'):
        result = deployments
        code = 0
    elif 'changes' in deployments and not any(c['changed'] for c in deployments['changes'].values()):
        result = {**deployments, 'recovery': {'release': 'UNCHANGED'}}
        code = 0
    else:
        result = docker_ssm.lifecycle('rollback', deployments)
        (directory / 'release.json').write_text(json.dumps(result, indent=2) + '\n')
        code = int(result.get('COMMAND_STATUS') != 'Success' or bool(result.get('recovery_error')))
    (report_root / 'rollback-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return code


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
