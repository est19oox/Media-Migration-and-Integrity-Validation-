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
    (cd "$ROOT" && mvn --batch-mode clean verify)

    REPORT_DIR="$ROOT/target/surefire-reports"
    total_tests=0
    total_failures=0
    total_errors=0
    if [ -d "$REPORT_DIR" ]; then
        for report in "$REPORT_DIR"/TEST-*.xml; do
            [ -f "$report" ] || continue
            report_counts=$(awk '
                function attribute_value(name, pattern) {
                    pattern = name "=\"[0-9][0-9]*\""
                    if (match(suite_tag, pattern)) {
                        return substr(suite_tag, RSTART + length(name) + 2, RLENGTH - length(name) - 3) + 0
                    }
                    return 0
                }
                {
                    remaining = $0
                    if (!capturing) {
                        if (!match(remaining, /<testsuite[[:space:]>]/)) {
                            next
                        }
                        remaining = substr(remaining, RSTART)
                        capturing = 1
                    }
                    suite_tag = suite_tag remaining " "
                    tag_end = index(suite_tag, ">")
                    if (tag_end > 0) {
                        suite_tag = substr(suite_tag, 1, tag_end)
                        print attribute_value("tests"), attribute_value("failures"), attribute_value("errors")
                        found = 1
                        exit
                    }
                }
                END {
                    if (!found) {
                        print "0 0 0"
                    }
                }
            ' "$report")
            set -- $report_counts
            report_tests=${1:-0}
            report_failures=${2:-0}
            report_errors=${3:-0}
            total_tests=$((total_tests + report_tests))
            total_failures=$((total_failures + report_failures))
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

if [ -f "$ROOT/services/transfer-service/pom.xml" ]; then
    (cd "$ROOT/services/transfer-service" && mvn --batch-mode clean verify)
fi
pass 'Application unit tests passed.' 
