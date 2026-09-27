#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no image reference was produced by the build stage.

if [ ! -f "$ROOT/.ci/image-ref" ]; then
    skip 'No built image reference; container scanning is not active yet.'
    exit 0
fi

require_command trivy
prepare_reports_directory "$ROOT"
image_ref=$(sed -n '1p' "$ROOT/.ci/image-ref")
if [ -z "$image_ref" ]; then
    fail 'The image reference file is empty.'
    exit 1
fi

trivy image --severity HIGH,CRITICAL --exit-code 1 --format json --output "$ROOT/.ci/reports/trivy-image.json" "$image_ref"
pass "Trivy container scan passed for $image_ref."
