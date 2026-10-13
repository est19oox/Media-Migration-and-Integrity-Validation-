#!/usr/bin/env bash

set -euo pipefail
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
PRETTIER="$ROOT/node_modules/.bin/prettier"
TERRAFORM_ROOT="$ROOT/infrastructure"

if [[ ! -x "$PRETTIER" ]]; then
    echo "Pinned Prettier is not installed. Run 'npm ci' from $ROOT." >&2
    exit 1
fi
command -v mvn >/dev/null 2>&1 || { echo 'Maven (mvn) is required to check Java sources.' >&2; exit 1; }
[[ -d "$ROOT/services" ]] || { echo "Services directory is missing: $ROOT/services" >&2; exit 1; }

shopt -s nullglob
service_poms=("$ROOT"/services/*/pom.xml)
if ((${#service_poms[@]} == 0)); then
    echo "No Maven services found under $ROOT/services." >&2
    exit 1
fi
has_terraform=false
if [[ -d "$TERRAFORM_ROOT" ]] && find "$TERRAFORM_ROOT" -type f -name '*.tf' -print -quit | grep -q .; then
    has_terraform=true
fi
if [[ "$has_terraform" == true ]]; then
    command -v terraform >/dev/null 2>&1 || { echo 'Terraform is required because infrastructure Terraform files exist.' >&2; exit 1; }
fi

cd "$ROOT"
"$PRETTIER" --check '**/*.html' '**/*.css' '**/*.js' '**/*.json' '**/*.md' '**/*.yml' '**/*.yaml'

for pom in "${service_poms[@]}"; do
    printf 'Checking Java formatting for %s\n' "$pom"
    mvn --file "$pom" spotless:check
done

if [[ "$has_terraform" == true ]]; then
    terraform -chdir="$TERRAFORM_ROOT" fmt -check -recursive
fi
