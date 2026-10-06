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
trap 'rm -f "$zap_output"' EXIT HUP INT TERM

if docker run --rm --user 0:0 --network "${MMIV_TEST_NETWORK:-mmiv-ci}" -v "$reports_directory:/zap/wrk" \
    "${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}" \
    zap-baseline.py -t "$target_url" -J zap-report.json -r zap-report.html \
    > "$zap_output" 2>&1; then
    zap_status=0
else
    zap_status=$?
fi

tee "$zap_log" < "$zap_output"
rm -f "$zap_output"
trap - EXIT HUP INT TERM

if [ "$zap_status" -eq 0 ]; then
    pass 'ZAP DAST baseline scan completed without blocking findings.'
else
    printf 'FAIL: ZAP DAST baseline scan failed with exit status %s.\n' "$zap_status" >&2
    exit "$zap_status"
fi
