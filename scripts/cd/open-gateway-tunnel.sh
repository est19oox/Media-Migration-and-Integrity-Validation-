#!/usr/bin/env bash
set -euo pipefail
INSTANCE_ID="${1:?Usage: bash scripts/cd/open-gateway-tunnel.sh <instance-id> [local-port]}"
LOCAL_PORT="${2:-8080}"
[[ "$INSTANCE_ID" =~ ^i-[a-f0-9]+$ ]] || { echo "Invalid EC2 instance ID" >&2; exit 1; }
[[ "$LOCAL_PORT" =~ ^[0-9]+$ ]] && ((10#$LOCAL_PORT >= 1 && 10#$LOCAL_PORT <= 65535)) || {
  echo "Local port must be between 1 and 65535" >&2; exit 1;
}
command -v session-manager-plugin >/dev/null
echo "Open http://localhost:$LOCAL_PORT/upload.html while this session remains open."
aws ssm start-session --region "${AWS_REGION:-ap-southeast-1}" --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession \
  --parameters "portNumber=8080,localPortNumber=$LOCAL_PORT"
