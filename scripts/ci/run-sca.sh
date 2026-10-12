#!/usr/bin/env sh

set -eu

# ============================================================
# MMIV SOFTWARE COMPOSITION ANALYSIS (SCA)
# ============================================================

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")

# ============================================================
# CHECK DEPENDENCY MANIFESTS
# ============================================================

# Emits SKIPPED when no supported dependency manifest exists.

if [ ! -f "$ROOT/pyproject.toml" ] \
    && [ ! -f "$ROOT/requirements.txt" ] \
    && [ ! -f "$ROOT/package.json" ] \
    && ! find "$ROOT/services" \
        -mindepth 2 \
        -maxdepth 2 \
        -name pom.xml \
        -print -quit 2>/dev/null | grep -q .; then

    skip 'No supported dependency manifest; SCA is not active yet.'
    exit 0
fi

# ============================================================
# VERIFY TRIVY
# ============================================================

require_command trivy

prepare_reports_directory "$ROOT"

REPORT="$ROOT/.ci/reports/trivy-sca.json"

# Remove previous report to prevent stale evidence.
rm -f "$REPORT"

# ============================================================
# RUN TRIVY VULNERABILITY SCAN
# ============================================================

scan_status=0

trivy fs \
    --scanners vuln \
    --skip-version-check \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --format json \
    --skip-dirs "$ROOT/.worktrees" \
    --skip-dirs "$ROOT/.superpowers" \
    --ignorefile "$ROOT/security/trivy/.trivyignore.yaml" \
    --output "$REPORT" \
    "$ROOT" || scan_status=$?

# ============================================================
# PROCESS TRIVY JSON REPORT
# ============================================================

if [ -f "$REPORT" ]; then

    if python3 - "$REPORT" <<'PY_REPORT'
import json
import sys

report_path = sys.argv[1]

with open(report_path, encoding="utf-8") as report:
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
            "installed=" + str(
                vuln.get("InstalledVersion", "")
            ),
            "fixed=" + str(
                vuln.get("FixedVersion") or "not available"
            )
        ]))

print("Total findings:", count)
PY_REPORT
    then
        :
    else
        printf '%s\n' \
            'ERROR: Could not parse the Trivy JSON report.' \
            'Inspect the scanner output and generated report.' >&2

        if [ "$scan_status" -eq 0 ]; then
            scan_status=1
        fi
    fi

else

    # ========================================================
    # HANDLE SCANNER / DEPENDENCY RESOLUTION FAILURE
    # ========================================================

    printf '%s\n' \
        'ERROR: Trivy did not produce a JSON report.' \
        'Possible causes:' \
        '  - Maven Central HTTP 429 rate limiting' \
        '  - Maven dependency resolution failure' \
        '  - Trivy vulnerability database download failure' \
        '  - Scanner execution or configuration error' \
        'Security validation has not completed.' >&2

    if [ "$scan_status" -eq 0 ]; then
        scan_status=1
    fi

fi

# ============================================================
# ENFORCE SECURITY GATE
# ============================================================

if [ "$scan_status" -ne 0 ]; then

    printf '%s\n' \
        "Trivy SCA failed with exit code $scan_status." \
        'Review the vulnerability findings or scanner errors above.' \
        'The CI/CD security gate remains enforced.' >&2

    exit "$scan_status"
fi

# ============================================================
# SUCCESS
# ============================================================

pass 'Trivy software composition analysis passed.'
