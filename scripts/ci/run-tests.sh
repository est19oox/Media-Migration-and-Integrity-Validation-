#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")

require_command mvn

if [ -d /opt/jdk-25 ]; then
    JAVA_HOME=/opt/jdk-25
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi

found_service=false

validate_surefire_reports() {
    service_dir=$1
    report_dir="$service_dir/target/surefire-reports"
    total_tests=0
    total_failures=0
    total_errors=0
    report_count=0

    if [ ! -d "$report_dir" ]; then
        fail "Missing Surefire report directory for service: $service_dir"
        return 1
    fi

    for report in "$report_dir"/TEST-*.xml; do
        [ -f "$report" ] || continue
        report_count=$((report_count + 1))
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
        total_tests=$((total_tests + ${1:-0}))
        total_failures=$((total_failures + ${2:-0}))
        total_errors=$((total_errors + ${3:-0}))
    done

    if [ "$report_count" -eq 0 ]; then
        fail "No Surefire TEST-*.xml reports found for service: $service_dir"
        return 1
    fi
    if [ "$total_tests" -eq 0 ]; then
        fail "No application tests were executed for service: $service_dir"
        return 1
    fi
    if [ "$total_failures" -ne 0 ] || [ "$total_errors" -ne 0 ]; then
        fail "Surefire reports for $service_dir contain $total_failures failure(s) and $total_errors error(s)"
        return 1
    fi
}

run_service() {
    pom=$1
    legacy_compatibility=$2
    service_dir=$(dirname "$pom")

    printf 'Testing Maven service: %s\n' "$service_dir"

    (
        cd "$service_dir"
        if [ "$legacy_compatibility" = true ]; then
            mvn --batch-mode test
        else
            mvn --batch-mode clean verify
        fi
    )
    validate_surefire_reports "$service_dir"
}

for pom in "$ROOT"/services/*/pom.xml; do
    [ -f "$pom" ] || continue

    found_service=true
    run_service "$pom" false
done

if [ "$found_service" = false ]; then
    if [ -f "$ROOT/pom.xml" ]; then
        run_service "$ROOT/pom.xml" true
    else
        skip 'No Maven microservices found.'
        exit 0
    fi
fi

pass 'Application unit tests passed.'