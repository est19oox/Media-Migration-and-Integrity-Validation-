#!/bin/bash

# Infrastructure as Code security scanning script
# Requires Checkov to be installed.

set -e

TARGET_DIRECTORY="${1:-infrastructure}"

echo "Starting Infrastructure as Code security scan..."
echo "Target: $TARGET_DIRECTORY"

checkov \
  --directory "$TARGET_DIRECTORY" \
  --framework terraform

echo "Infrastructure as Code security scan completed."
