#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"

echo "Checking actuator health..."
curl --fail --silent --show-error "$BASE_URL/actuator/health" | grep -q '"status":"UP"'

echo "Checking transfer service endpoint..."
curl --fail --silent --show-error "$BASE_URL/api/v1/health" | grep -q '"status":"UP"'

echo "Smoke test passed."
