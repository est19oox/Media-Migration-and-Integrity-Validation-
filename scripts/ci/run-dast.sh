#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    fail 'Usage: run-dast.sh <target-url>'
fi

target_url=$1
if ! printf '%s\n' "$target_url" | grep -Eq '^https?://[^[:space:]]+$'; then
    fail 'DAST target URL must use HTTP or HTTPS.'
fi

require_command curl
if ! curl --fail --silent --show-error --max-time 10 "$target_url"; then
    fail 'DAST target URL is unreachable.'
fi

require_command docker
ROOT=$(find_repository_root .)
prepare_reports_directory "$ROOT"
reports_directory="$ROOT/.ci/reports"
chmod 777 "$reports_directory"
zap_log="$reports_directory/zap.log"
zap_output=$(mktemp "${TMPDIR:-/tmp}/mmiv-zap.XXXXXX")
zap_container=
cleanup() {
    if [ -n "$zap_container" ]; then
        docker rm -f -v "$zap_container" >/dev/null 2>&1 || true
    fi
    rm -f "$zap_output"
}
trap cleanup EXIT HUP INT TERM

if ! zap_container=$(docker create --user 0:0 --network "${MMIV_TEST_NETWORK:-mmiv-ci}" --volume /zap/wrk \
    "${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}" \
    zap-baseline.py -t "$target_url" -J zap-report.json -r zap-report.html); then
    fail 'Could not create the ZAP scan container.'
fi

if docker start --attach "$zap_container" \
    > "$zap_output" 2>&1; then
    zap_status=0
else
    zap_status=$?
fi

reports_copied=true
for report_name in zap-report.json zap-report.html; do
    if ! docker cp "$zap_container:/zap/wrk/$report_name" "$reports_directory/$report_name"; then
        reports_copied=false
    fi
done
docker rm -f -v "$zap_container" >/dev/null 2>&1 || true
zap_container=

tee "$zap_log" < "$zap_output"
rm -f "$zap_output"
trap - EXIT HUP INT TERM

if { [ "$zap_status" -eq 0 ] || [ "$zap_status" -eq 2 ]; } \
    && [ "$reports_copied" != true ]; then
    fail 'ZAP did not produce the required JSON and HTML reports.'
fi

if [ "$zap_status" -eq 0 ]; then
    pass 'ZAP DAST baseline scan completed without blocking findings.'
elif [ "$zap_status" -eq 2 ]; then
    pass 'ZAP DAST baseline scan completed with non-blocking warnings; review the archived report.'
else
    printf 'FAIL: ZAP DAST baseline scan failed with exit status %s.\n' "$zap_status" >&2
    exit "$zap_status"
fi
