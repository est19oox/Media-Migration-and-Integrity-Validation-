#!/bin/bash

# Security quality gate validation foundation
# Sprint 0 initial implementation

set -e

CONFIG_FILE="${1:-ci/quality-gates.yml}"

echo "Validating security quality gate configuration..."

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Error: quality gate configuration not found: $CONFIG_FILE"
  exit 1
fi

echo "Quality gate configuration found: $CONFIG_FILE"

required_sections=(
  "sast"
  "dependency_scanning"
  "container_scanning"
  "iac_scanning"
)

for section in "${required_sections[@]}"; do
  if ! grep -q "$section:" "$CONFIG_FILE"; then
    echo "Error: missing security gate: $section"
    exit 1
  fi
done

echo "Security quality gate configuration validation completed."
