#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no supported application manifest exists.

if [ -f "$ROOT/pyproject.toml" ] || [ -f "$ROOT/requirements.txt" ]; then
    require_command python3
    (cd "$ROOT" && python3 -m pytest tests)
elif [ -f "$ROOT/package.json" ]; then
    require_command npm
    (cd "$ROOT" && npm test)
elif [ -f "$ROOT/pom.xml" ]; then
    require_command mvn
    JAVA_HOME=/opt/jdk-25
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
    (cd "$ROOT" && mvn --batch-mode test)

    REPORT_DIR="$ROOT/target/surefire-reports"
    total_tests=0
    total_failures=0
    total_errors=0
    if [ -d "$REPORT_DIR" ]; then
        for report in "$REPORT_DIR"/TEST-*.xml; do
            [ -f "$report" ] || continue
            report_tests=$(awk '
                {
                    line = $0
                    while (match(line, /tests="[0-9][0-9]*"/)) {
                        total += substr(line, RSTART + 7, RLENGTH - 8)
                        line = substr(line, RSTART + RLENGTH)
                    }
                }
                END { print total + 0 }
            ' "$report")
            total_tests=$((total_tests + report_tests))
            report_failures=$(awk '
                {
                    line = $0
                    while (match(line, /failures="[0-9][0-9]*"/)) {
                        total += substr(line, RSTART + 10, RLENGTH - 11)
                        line = substr(line, RSTART + RLENGTH)
                    }
                }
                END { print total + 0 }
            ' "$report")
            total_failures=$((total_failures + report_failures))
            report_errors=$(awk '
                {
                    line = $0
                    while (match(line, /errors="[0-9][0-9]*"/)) {
                        total += substr(line, RSTART + 8, RLENGTH - 9)
                        line = substr(line, RSTART + RLENGTH)
                    }
                }
                END { print total + 0 }
            ' "$report")
            total_errors=$((total_errors + report_errors))
        done
    fi

    if [ "$total_tests" -eq 0 ]; then
        fail 'No application tests were executed'
        exit 1
    fi
    if [ "$total_failures" -ne 0 ] || [ "$total_errors" -ne 0 ]; then
        fail "Application test reports contain $total_failures failure(s) and $total_errors error(s)"
        exit 1
    fi
else
    skip 'No supported application manifest; unit tests are not active yet.'
    exit 0
fi

pass 'Application unit tests passed.'
