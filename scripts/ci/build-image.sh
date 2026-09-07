#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no application Dockerfile exists.

if [ ! -f "$ROOT/Dockerfile" ]; then
    skip 'No application Dockerfile; container build is not active yet.'
    exit 0
fi

require_command docker
image_ref=${MMIV_IMAGE:-"mmiv:${BUILD_NUMBER:-local}"}
mkdir -p "$ROOT/.ci"
docker build --tag "$image_ref" "$ROOT"
printf '%s\n' "$image_ref" > "$ROOT/.ci/image-ref"
pass "Built application image $image_ref."
