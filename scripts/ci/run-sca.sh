#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no supported dependency manifest exists.

if [ ! -f "$ROOT/pyproject.toml" ] && [ ! -f "$ROOT/requirements.txt" ] && [ ! -f "$ROOT/package.json" ] && [ ! -f "$ROOT/pom.xml" ]; then
    skip 'No supported dependency manifest; SCA is not active yet.'
    exit 0
fi

require_command trivy
prepare_reports_directory "$ROOT"
trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output "$ROOT/.ci/reports/trivy-sca.json" "$ROOT"
pass 'Trivy software composition analysis passed.'
