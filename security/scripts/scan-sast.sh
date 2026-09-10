#!/bin/bash

# Static Application Security Testing script
# Requires Semgrep to be installed.

set -e

TARGET_DIRECTORY="${1:-src}"

echo "Starting SAST scan..."
echo "Target: $TARGET_DIRECTORY"

semgrep scan \
  --config security/semgrep/semgrep.yml \
  "$TARGET_DIRECTORY"

echo "SAST scan completed."
