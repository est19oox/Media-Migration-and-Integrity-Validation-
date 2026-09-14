#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no supported source file exists.

if ! find "$ROOT/src" -type f \( -name '*.py' -o -name '*.js' -o -name '*.jsx' -o -name '*.ts' -o -name '*.tsx' -o -name '*.java' -o -name '*.go' \) -print -quit | grep -q .; then
    skip 'No supported source files; SAST is not active yet.'
    exit 0
fi

require_command semgrep
prepare_reports_directory "$ROOT"
semgrep_config=p/java
if [ -f "$ROOT/security/semgrep/semgrep.yml" ]; then
    semgrep_config="$ROOT/security/semgrep/semgrep.yml"
fi

semgrep scan --config "$semgrep_config" --error --json --output "$ROOT/.ci/reports/semgrep.json" "$ROOT/src"
pass 'Semgrep SAST completed without blocking findings.'
