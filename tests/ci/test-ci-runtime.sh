#!/usr/bin/env sh

set -eu

ROOT=${1:-/repo}

for script in "$ROOT"/scripts/ci/*.sh; do
    sh -n "$script"
done

"$ROOT/scripts/ci/validate-repository.sh" "$ROOT"
"$ROOT/scripts/ci/run-tests.sh" "$ROOT"
"$ROOT/scripts/ci/run-sast.sh" "$ROOT"
"$ROOT/scripts/ci/run-sca.sh" "$ROOT"
"$ROOT/scripts/ci/run-terraform-checks.sh" "$ROOT"
"$ROOT/scripts/ci/run-iac-scan.sh" "$ROOT"
"$ROOT/scripts/ci/build-image.sh" "$ROOT"
"$ROOT/scripts/ci/scan-image.sh" "$ROOT"

printf 'PASS: CI runtime skeleton behavior is valid.\n'
