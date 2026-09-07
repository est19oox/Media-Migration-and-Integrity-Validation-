#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no Terraform configuration exists.

if ! has_files "$ROOT/infrastructure" '*.tf'; then
    skip 'No Terraform files; IaC scanning is not active yet.'
    exit 0
fi

require_command checkov
prepare_reports_directory "$ROOT"
checkov --directory "$ROOT/infrastructure" --framework terraform --output json --output-file-path "$ROOT/.ci/reports/checkov.json"
pass 'Checkov IaC security scan passed.'
