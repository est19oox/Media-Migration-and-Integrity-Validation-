#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no image reference was produced by the build stage.

if [ ! -f "$ROOT/.ci/image-ref" ]; then
    skip 'No built image reference; container scanning is not active yet.'
    exit 0
fi

require_command trivy
prepare_reports_directory "$ROOT"
image_ref=$(sed -n '1p' "$ROOT/.ci/image-ref")
if [ -z "$image_ref" ]; then
    fail 'The image reference file is empty.'
    exit 1
fi

scan_status=0
trivy image \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --format json \
    --ignorefile "$ROOT/security/trivy/.trivyignore.yaml" \
    --output "$ROOT/.ci/reports/trivy-image.json" \
    "$image_ref" || scan_status=$?
if [ "$scan_status" -ne 0 ]; then
    python3 - "$ROOT/.ci/reports/trivy-image.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
if not path.is_file():
    print('Trivy failed before producing a JSON report; inspect the scanner error above.', file=sys.stderr)
    raise SystemExit(0)
try:
    report = json.loads(path.read_text(encoding='utf-8'))
except (OSError, ValueError):
    print('Trivy report could not be parsed; download the report artifact.', file=sys.stderr)
    raise SystemExit(0)
findings = [
    (result.get('Target', ''), vulnerability)
    for result in report.get('Results', [])
    for vulnerability in result.get('Vulnerabilities') or []
]
if findings:
    print(f'Trivy blocked this image: {len(findings)} HIGH/CRITICAL vulnerability findings.', file=sys.stderr)
    for target, item in findings[:30]:
        print(
            f"{item.get('Severity', '?')} {item.get('VulnerabilityID', '?')} "
            f"{item.get('PkgName', '?')} {item.get('InstalledVersion', '?')} "
            f"fixed={item.get('FixedVersion') or 'unavailable'} target={target}",
            file=sys.stderr,
        )
    if len(findings) > 30:
        print(f'... {len(findings) - 30} more findings in the report artifact.', file=sys.stderr)
else:
    print('Trivy exited unsuccessfully without vulnerability findings; inspect the scanner log and report.', file=sys.stderr)
PY
    exit "$scan_status"
fi
pass "Trivy container scan passed for $image_ref."
