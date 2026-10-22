#!/usr/bin/env python3
"""Verify after the deployment barrier; restore changed images after failure."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[2]
spec = spec_from_file_location('docker_ssm', Path(__file__).with_name('docker-stack-ssm.py'))
docker_ssm = module_from_spec(spec)
spec.loader.exec_module(docker_ssm)


def expected_services():
    return docker_ssm.expected_services()


def scan_path(service, run_id):
    """Select an endpoint routed to the service, not Gateway's own health URL."""
    paths = {
        'service-registry': '/',
        'api-gateway': '/pages/login.html',
        'transfer-service': '/api/v1/transfers',  # API scan uses the OpenAPI paths.
        'user-access-service': '/user-access/.well-known/jwks.json',
        'discovery-service': f'/api/metadata/verification/{run_id}/plans',
        'reporting-service': '/api/reports/mine',
        'integrity-service': '/integrity/actuator/health',
        'integrity-service-2': '/integrity/actuator/health',
    }
    return paths[service]


def states(directory, instance, commit):
    return docker_ssm.validate(json.loads((directory / 'release.json').read_text()), instance, commit)


def verify(deployments, report_root):
    if (deployments.get('READY') is not True or deployments.get('ROLLED_BACK') is not False
            or deployments.get('COMMAND_STATUS') != 'Success'
            or deployments.get('COMMAND_RESPONSE_CODE', 0) != 0
            or set(deployments['services']) != expected_services()):
        raise ValueError('Complete release readiness barrier failed; no scans started')
    with tempfile.TemporaryDirectory(prefix='mmiv-verification-') as private:
        return verify_scans(deployments, report_root, Path(private) / 'account.json')


def verify_scans(deployments, report_root, account_file):
    results = []
    run_id = str(uuid.uuid4())
    try:
        # Run scans sequentially. Rollback starts only after all scans finish.
        for name, item in sorted(deployments['services'].items(), key=lambda pair: (
                pair[0] == 'api-gateway', int(pair[1]['port']))):
            directory = report_root / name
            directory.mkdir(parents=True, exist_ok=True)
            env = {**os.environ, 'INSTANCE_ID': deployments['INSTANCE_ID'],
                   'DAST_SERVICE_NAME': 'integrity-service' if name == 'integrity-service-2' else name, 'DAST_PORT': item['port'],
                   'DAST_REPORT_DIR': str(directory.resolve()),
                   'DAST_SCAN_MODE': 'api' if name == 'transfer-service' else 'baseline',
                   'DAST_PATH': scan_path(name, run_id),
                   'DAST_READY_PATH': '/user-access/actuator/health' if name == 'user-access-service' else '/actuator/health',
                   'DAST_ENDPOINT_BASE_PATH': '/user-access' if name == 'user-access-service' else '',
                   'MMIV_VERIFICATION_ACCOUNT_FILE': str(account_file),
                   'MMIV_VERIFICATION_RUN_ID': run_id,
                   'MMIV_VERIFICATION_DESTINATION': os.environ.get('MMIV_VERIFICATION_DESTINATION', '') if os.environ.get('USER_ACCESS_TEST_USERNAME') else 'mmiv-verification-'+run_id,
                   'MMIV_MANAGED_VERIFICATION_DB': 'true' if deployments.get('MANAGED_USER_ACCESS_ORACLE', True) else 'false'}
            print('Verifying ' + name, flush=True)
            with (directory / 'dast.log').open('w') as log:
                process = subprocess.Popen(['bash', str(ROOT / 'scripts/cd/run-dast-ssm.sh')],
                                           cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                for line in process.stdout:
                    print(line, end='', flush=True)
                    if not line.startswith('::add-mask::'):
                        log.write(line)
                code = process.wait()
            results.append({'service': name, 'exit_code': code})
    finally:
        if account_file.exists() or (os.environ.get('USER_ACCESS_TEST_USERNAME') and os.environ.get('USER_ACCESS_TEST_PASSWORD')):
            directory=report_root/'data-cleanup';directory.mkdir(parents=True,exist_ok=True)
            env={**os.environ,'INSTANCE_ID':deployments['INSTANCE_ID'],'DAST_SERVICE_NAME':'discovery-service','DAST_PORT':'8085',
                 'DAST_PATH':'/actuator/health','DAST_READY_PATH':'/actuator/health','DAST_CLEANUP_ONLY':'true',
                 'DAST_DATA_CLEANUP_ONLY':'true','MMIV_VERIFICATION_ACCOUNT_FILE':str(account_file),
                 'MMIV_VERIFICATION_RUN_ID':run_id,'DAST_REPORT_DIR':str(directory.resolve())}
            with (directory/'cleanup.log').open('w') as log:
                data_cleanup=subprocess.run(['bash',str(ROOT/'scripts/cd/run-dast-ssm.sh')],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
            results.append({'service':'verification-data-cleanup','exit_code':data_cleanup.returncode,'runId':run_id})
            if data_cleanup.returncode:print('Verification data cleanup failed; see scoped run evidence.',file=sys.stderr)
        if account_file.exists():
            directory = report_root / 'account-cleanup'
            directory.mkdir(parents=True, exist_ok=True)
            env = {**os.environ, 'INSTANCE_ID': deployments['INSTANCE_ID'],
                   'DAST_SERVICE_NAME': 'user-access-service', 'DAST_PORT': '8083',
                   'DAST_PATH': '/actuator/health', 'DAST_READY_PATH': '/actuator/health',
                   'DAST_CLEANUP_ONLY': 'true', 'MMIV_VERIFICATION_ACCOUNT_FILE': str(account_file),
                   'DAST_REPORT_DIR': str(directory.resolve())}
            with (directory / 'cleanup.log').open('w') as log:
                cleanup = subprocess.run(['bash', str(ROOT / 'scripts/cd/run-dast-ssm.sh')],
                                         cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            results.append({'service': 'verification-account-cleanup', 'exit_code': cleanup.returncode})
            if cleanup.returncode:
                print('Verification account cleanup failed; see cleanup evidence.', file=sys.stderr)
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
