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
# Share provider binaries across the sequential validation roots. The
# repository-local fallback is ignored by Git; CI supplies a job-local temp path.
TF_PLUGIN_CACHE_DIR=${TF_PLUGIN_CACHE_DIR:-"$ROOT/.ci/cache/terraform-providers"}
mkdir -p "$TF_PLUGIN_CACHE_DIR"
TF_PLUGIN_CACHE_DIR=$(CDPATH= cd -- "$TF_PLUGIN_CACHE_DIR" && pwd)
export TF_PLUGIN_CACHE_DIR
terraform -chdir="$ROOT/infrastructure" fmt -check -recursive

for terraform_root in "$ROOT/infrastructure/bootstrap" "$ROOT/infrastructure/terraform" "$ROOT/infrastructure/terraform/environments/dev" "$ROOT/infrastructure/ecs-fargate" "$ROOT/infrastructure/aws-managed-data"; do
    [ -d "$terraform_root" ] || continue
    terraform -chdir="$terraform_root" init -backend=false -input=false -no-color
    terraform -chdir="$terraform_root" validate -no-color
done

pass 'Terraform formatting and validation passed.'
