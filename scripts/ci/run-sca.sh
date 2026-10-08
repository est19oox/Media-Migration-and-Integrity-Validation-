#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no supported dependency manifest exists.

if [ ! -f "$ROOT/pyproject.toml" ] \
    && [ ! -f "$ROOT/requirements.txt" ] \
    && [ ! -f "$ROOT/package.json" ] \
    && ! find "$ROOT/services" -mindepth 2 -maxdepth 2 -name pom.xml -print -quit 2>/dev/null | grep -q .; then
    skip 'No supported dependency manifest; SCA is not active yet.'
    exit 0
fi

require_command trivy
prepare_reports_directory "$ROOT"
# Avoid displaying a stale report if a new scan fails before writing output.
rm -f "$ROOT/.ci/reports/trivy-sca.json"
scan_status=0
trivy fs \
    --scanners vuln \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --format json \
    --skip-dirs "$ROOT/.worktrees" \
    --skip-dirs "$ROOT/.superpowers" \
    --ignorefile "$ROOT/security/trivy/.trivyignore.yaml" \
    --output "$ROOT/.ci/reports/trivy-sca.json" \
    "$ROOT" || scan_status=$?

# Keep the JSON artifact and show actionable findings even when the security gate fails.
if [ -f "$ROOT/.ci/reports/trivy-sca.json" ]; then
    if python3 - "$ROOT/.ci/reports/trivy-sca.json" <<'PY_REPORT'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as report:
    results = json.load(report).get("Results", []) or []
count = 0
print("Trivy SCA findings (HIGH/CRITICAL):")
for result in results:
    for vuln in result.get("Vulnerabilities", []) or []:
        count += 1
        print(" | ".join(str(value) for value in [
            result.get("Target", ""),
            vuln.get("PkgName", ""),
            vuln.get("VulnerabilityID", ""),
            vuln.get("Severity", ""),
            "installed=" + str(vuln.get("InstalledVersion", "")),
            "fixed=" + str(vuln.get("FixedVersion") or "not available")
        ]))
print("Total findings:", count)
PY_REPORT
    then
        :
    else
        printf '%s\n' 'Could not render Trivy JSON; inspect the scan log and report.' >&2
        if [ "$scan_status" -eq 0 ]; then scan_status=1; fi
    fi
else
    printf '%s\n' 'Trivy did not produce a JSON report; inspect its error output.' >&2
    if [ "$scan_status" -eq 0 ]; then scan_status=1; fi
fi

if [ "$scan_status" -ne 0 ]; then
    printf 'Trivy SCA failed with exit code %s. See the findings above and trivy-sca.json.\n' "$scan_status" >&2
    exit "$scan_status"
fi
pass 'Trivy software composition analysis passed.'
