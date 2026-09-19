#!/usr/bin/env bash

set -eu

DAST_TARGET_URL="${1:-}"
REPORT_DIR="${DAST_REPORT_DIR:-.ci/reports}"

if [ -z "$DAST_TARGET_URL" ]; then
    echo "ERROR: DAST target URL is required."
    echo "Usage: $0 <target-url>"
    exit 2
fi

case "$DAST_TARGET_URL" in
    http://*|https://*)
        ;;
    *)
        echo "ERROR: DAST target must use http:// or https://"
        exit 2
        ;;
esac

mkdir -p "$REPORT_DIR"

echo "Starting OWASP ZAP baseline scan..."
echo "Target: $DAST_TARGET_URL"

docker run --rm \
    -v "$(pwd)/$REPORT_DIR:/zap/wrk/:rw" \
    ghcr.io/zaproxy/zaproxy:stable \
    zap-baseline.py \
    -t "$DAST_TARGET_URL" \
    -r zap-report.html \
    -J zap-report.json

echo "DAST scan completed."
echo "Reports:"
echo "  $REPORT_DIR/zap-report.html"
echo "  $REPORT_DIR/zap-report.json"
