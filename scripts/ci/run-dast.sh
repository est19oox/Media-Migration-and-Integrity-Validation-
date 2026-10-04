#!/usr/bin/env bash
#
# DAST baseline wrapper (OWASP ZAP).
#
# Invokes an OWASP ZAP baseline scan against a reachable target URL and
# translates the scanner exit code into a stable CI gate result. Reports are
# always written to <repo-root>/.ci/reports/ so that a pipeline can archive
# them without extra configuration.
#
# This script is pipeline-independent: it requires only Docker and a reachable
# target URL. See docs/ci/dast-integration.md.

set -euo pipefail

PROG_NAME=$(basename "$0")
REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)

# Reports are fixed under the repository root so the path is valid inside the
# ZAP container bind mount regardless of the caller's working directory, and so
# existing pipeline archive rules keep matching.
REPORT_DIR="$REPO_ROOT/.ci/reports"

# Pinned by default for reproducible scans; ZAP_IMAGE is an ordinary override.
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:2.17.0}"

usage() {
    cat <<EOF
Usage:
  $PROG_NAME <target-url>
  DAST_TARGET_URL=<url> $PROG_NAME

Runs an OWASP ZAP baseline scan against <url> and writes the HTML and JSON
reports to .ci/reports.

Environment:
  DAST_TARGET_URL     Target URL when no positional argument is given.
  DAST_FAIL_ON_WARN   Set to true to make WARN (ZAP exit code 2) blocking.
  ZAP_IMAGE           Scanner image. Default: $ZAP_IMAGE

Wrapper exit codes:
  0  PASS   Scan completed and the result is non-blocking.
  1  FAIL   The baseline reported alerts that fail the scan.
  2  WARN   Warnings were reported and DAST_FAIL_ON_WARN=true.
  3  ERROR  Configuration, Docker, or scanner failure.
EOF
}

error() {
    printf 'ERROR: %s\n' "$1" >&2
}

# --- Target URL --------------------------------------------------------------

TARGET_URL="${DAST_TARGET_URL:-}"
if [ "$#" -gt 0 ]; then
    TARGET_URL="$1"
fi

if [ -z "$TARGET_URL" ]; then
    error "No DAST target URL was supplied."
    printf '\n' >&2
    usage >&2
    exit 3
fi

case "$TARGET_URL" in
    http://*|https://*) ;;
    *)
        error "The DAST target must be an http:// or https:// URL: $TARGET_URL"
        exit 3
        ;;
esac

# --- Options -----------------------------------------------------------------

DAST_FAIL_ON_WARN="${DAST_FAIL_ON_WARN:-false}"
[ "$DAST_FAIL_ON_WARN" = "true" ] || DAST_FAIL_ON_WARN=false

if [ -z "$ZAP_IMAGE" ]; then
    error "ZAP_IMAGE is set but empty."
    exit 3
fi

# --- Preconditions -----------------------------------------------------------

if ! command -v docker >/dev/null 2>&1; then
    error "Docker is required to run the ZAP baseline scan but was not found on PATH."
    exit 3
fi

if ! docker info >/dev/null 2>&1; then
    error "Docker is installed but the Docker daemon is not reachable."
    exit 3
fi

if ! mkdir -p "$REPORT_DIR"; then
    error "Unable to create the report directory: $REPORT_DIR"
    exit 3
fi

if [ ! -w "$REPORT_DIR" ]; then
    error "The report directory is not writable: $REPORT_DIR"
    exit 3
fi

# --- Prerequisite reporting --------------------------------------------------

printf 'OWASP ZAP baseline scan\n'
printf '  Target          : %s\n' "$TARGET_URL"
printf '  Scanner image   : %s\n' "$ZAP_IMAGE"
printf '  Report dir      : %s\n' "$REPORT_DIR"
printf '  Fail on warning : %s\n' "$DAST_FAIL_ON_WARN"
printf '\n'

echo "Starting OWASP ZAP baseline scan..."

# --- Scan --------------------------------------------------------------------

# The scanner exit code is captured rather than allowed to abort the script,
# so that the result summary is always printed before exiting.
#
# The container runs as the calling user so that it can write reports into the
# bind-mounted report directory, which the caller owns.
set +e
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$REPORT_DIR:/zap/wrk/:rw" \
    "$ZAP_IMAGE" \
    zap-baseline.py \
    -t "$TARGET_URL" \
    -r zap-report.html \
    -J zap-report.json
SCAN_EXIT=$?
set -e

# --- Result mapping ----------------------------------------------------------

case "$SCAN_EXIT" in
    0)
        RESULT="PASS"
        BLOCKING=false
        WRAPPER_EXIT=0
        ;;
    1)
        RESULT="FAIL"
        BLOCKING=true
        WRAPPER_EXIT=1
        ;;
    2)
        RESULT="WARN"
        if [ "$DAST_FAIL_ON_WARN" = "true" ]; then
            BLOCKING=true
            WRAPPER_EXIT=2
        else
            BLOCKING=false
            WRAPPER_EXIT=0
        fi
        ;;
    *)
        # Docker/CLI failures (for example 125, 126, 127) also land here. They
        # are reported with their raw code but normalized to a stable contract.
        RESULT="ERROR"
        BLOCKING=true
        WRAPPER_EXIT=3
        ;;
esac

printf '\n'
printf 'DAST result\n'
printf '  Target          : %s\n' "$TARGET_URL"
printf '  DAST result     : %s\n' "$RESULT"
printf '  Raw command exit: %s\n' "$SCAN_EXIT"
printf '  Wrapper exit    : %s\n' "$WRAPPER_EXIT"
printf '  HTML report     : %s\n' "$REPORT_DIR/zap-report.html"
printf '  JSON report     : %s\n' "$REPORT_DIR/zap-report.json"
printf '  Blocking        : %s\n' "$BLOCKING"

if [ "$BLOCKING" = "true" ]; then
    printf '\n'
    printf 'DAST gate failed.\n' >&2
    exit "$WRAPPER_EXIT"
fi

printf '\n'
printf 'DAST scan completed.\n'
exit 0
