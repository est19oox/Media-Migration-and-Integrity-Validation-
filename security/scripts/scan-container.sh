#!/bin/bash

# Container vulnerability scanning script
# Requires Trivy to be installed.

set -e

IMAGE_NAME="${1:-media-migration-app:latest}"

echo "Starting container security scan..."
echo "Image: $IMAGE_NAME"

trivy image \
  --severity HIGH,CRITICAL \
  --exit-code 1 \
  "$IMAGE_NAME"

echo "Container security scan completed."
