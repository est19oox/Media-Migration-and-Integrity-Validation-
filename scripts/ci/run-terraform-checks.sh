#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no Terraform configuration exists.

if ! has_files "$ROOT/infrastructure" '*.tf'; then
    skip 'No Terraform files; Terraform checks are not active yet.'
    exit 0
fi

require_command terraform
terraform -chdir="$ROOT/infrastructure" fmt -check -recursive

for terraform_root in "$ROOT/infrastructure/bootstrap" "$ROOT/infrastructure/terraform" "$ROOT/infrastructure/terraform/environments/dev"; do
    [ -d "$terraform_root" ] || continue
    terraform -chdir="$terraform_root" init -backend=false -input=false -no-color
    terraform -chdir="$terraform_root" validate -no-color
done

pass 'Terraform formatting and validation passed.'
