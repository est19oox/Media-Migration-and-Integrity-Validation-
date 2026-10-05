#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no application Dockerfile exists.

build_context="$ROOT"
if [ -f "$ROOT/services/transfer-service/Dockerfile" ]; then
    build_context="$ROOT/services/transfer-service"
fi
dockerfile="$build_context/Dockerfile"
if [ ! -f "$dockerfile" ] && [ -f "$ROOT/dockerfile" ]; then
    dockerfile="$ROOT/dockerfile"
fi

if [ ! -f "$dockerfile" ]; then
    skip 'No application Dockerfile; container build is not active yet.'
    exit 0
fi

require_command docker
image_ref=${MMIV_IMAGE:-"mmiv:${TESTED_COMMIT:-${GIT_COMMIT:-${BUILD_NUMBER:-local}}}"}
mkdir -p "$ROOT/.ci"
docker build --file "$dockerfile" --tag "$image_ref" "$build_context"
printf '%s\n' "$image_ref" > "$ROOT/.ci/image-ref"
pass "Built application image $image_ref."
