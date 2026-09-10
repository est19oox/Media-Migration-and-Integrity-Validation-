#!/bin/bash

# Software Composition Analysis (SCA) script
# Requires OWASP Dependency-Check to be installed.

set -e

PROJECT_NAME="${1:-media-migration-app}"
TARGET_DIRECTORY="${2:-.}"

echo "Starting dependency security scan..."
echo "Project: $PROJECT_NAME"
echo "Target: $TARGET_DIRECTORY"

dependency-check \
  --project "$PROJECT_NAME" \
  --scan "$TARGET_DIRECTORY" \
  --format JSON \
  --out reports/dependency-check

echo "Dependency security scan completed."
