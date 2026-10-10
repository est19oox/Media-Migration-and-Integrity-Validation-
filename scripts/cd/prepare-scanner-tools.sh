#!/usr/bin/env bash
# Prepare once per verification runner; safe to invoke again for manual scans.
set -euo pipefail
if ! command -v session-manager-plugin >/dev/null 2>&1; then
  PACKAGE=$(mktemp --suffix=.deb)
  trap 'rm -f "$PACKAGE"' EXIT
  curl -fsSL https://s3.amazonaws.com/session-manager-downloads/plugin/latest/ubuntu_64bit/session-manager-plugin.deb -o "$PACKAGE"
  sudo dpkg -i "$PACKAGE"
fi
session-manager-plugin --version
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}"
if ! docker image inspect "$ZAP_IMAGE" >/dev/null 2>&1; then
  timeout --kill-after=15s 300s docker pull "$ZAP_IMAGE"
fi
docker image inspect "$ZAP_IMAGE" >/dev/null
