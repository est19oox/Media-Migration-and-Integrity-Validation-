#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
requested_image_ref=${2:-}
# Emits SKIPPED: when no image reference was produced by the build stage.

if [ ! -f "$ROOT/.ci/image-ref" ]; then
    skip 'No built image reference; container scanning is not active yet.'
    exit 0
fi

require_command trivy
prepare_reports_directory "$ROOT"
if [ -n "$requested_image_ref" ]; then
    image_ref=$requested_image_ref
else
    image_ref=$(sed -n '1p' "$ROOT/.ci/image-ref")
fi
if [ -z "$image_ref" ]; then
    fail 'The image reference file is empty.'
    exit 1
fi

trivy image \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --format json \
    --ignorefile "$ROOT/security/trivy/.trivyignore.yaml" \
    --output "$ROOT/.ci/reports/trivy-image.json" \
    "$image_ref"
pass "Trivy container scan passed for $image_ref."
