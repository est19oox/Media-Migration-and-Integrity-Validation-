#!/usr/bin/env bash
# A Terraform root that has never been applied has no state object yet.
# Treat only that case as an empty state; preserve all other failures.
set -euo pipefail

root=${1:?Pass the Terraform root directory}
error_file=$(mktemp)
trap 'rm -f "$error_file"' EXIT

if terraform -chdir="$root" state list 2>"$error_file"; then
  exit 0
fi

if grep -Fq 'No state file was found!' "$error_file"; then
  exit 0
fi

cat "$error_file" >&2
exit 1
