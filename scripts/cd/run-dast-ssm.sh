#!/usr/bin/env bash
# ZAP runs on the GitHub runner; only HTTP traffic traverses SSM to EC2.
set -euo pipefail
export AWS_PAGER=""
INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
REGION="${AWS_REGION:-ap-southeast-1}"
PORT="${DAST_PORT:?DAST_PORT is required}"
LOCAL_PORT="${DAST_LOCAL_PORT:-18081}"
PATH_SUFFIX="${DAST_PATH:-/}"
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}"
for VALUE in "$PORT" "$LOCAL_PORT"; do
  [[ "$VALUE" =~ ^[0-9]+$ ]] && ((10#$VALUE >= 1 && 10#$VALUE <= 65535)) || {
    echo '::error::DAST ports must be between 1 and 65535.'; exit 1;
  }
done
[[ "$PATH_SUFFIX" == /* ]] || { echo '::error::DAST_PATH must start with /.'; exit 1; }
for COMMAND in aws session-manager-plugin docker curl python3 timeout setsid; do
  command -v "$COMMAND" >/dev/null || { echo "::error::Missing command: $COMMAND"; exit 1; }
done
REPORT_DIR="$PWD/.ci/reports/ec2-dast"
mkdir -p "$REPORT_DIR"
TEMP_DIR=$(mktemp -d)
TUNNEL_PID=
SCAN_PID=
ZAP_CONTAINER="mmiv-ec2-dast-$$"
TOTAL_STARTED=$SECONDS
aws_ssm() {
  aws ssm "$@" --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 20
}
cleanup() {
  EXIT_CODE=$?
  trap - EXIT
  set +e
  timeout 15s docker rm -f -v "$ZAP_CONTAINER" >/dev/null 2>&1
  if [[ -n "$SCAN_PID" ]]; then kill "$SCAN_PID" 2>/dev/null; wait "$SCAN_PID" 2>/dev/null; fi
  SESSION_ID=$(sed -n 's/.*Starting session with SessionId: \([^[:space:]]*\).*/\1/p' "$REPORT_DIR/tunnel.log" 2>/dev/null | head -n 1)
  if [[ -n "$SESSION_ID" ]]; then
    aws_ssm terminate-session --session-id "$SESSION_ID" >/dev/null 2>&1 || echo "Warning: could not explicitly terminate SSM session $SESSION_ID."
  fi
  if [[ -n "$TUNNEL_PID" ]]; then
    kill -TERM -- "-$TUNNEL_PID" 2>/dev/null
    wait "$TUNNEL_PID" 2>/dev/null
  fi
  rm -rf "$TEMP_DIR"
  exit "$EXIT_CODE"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

echo "DAST target: EC2 $INSTANCE_ID port $PORT$PATH_SUFFIX"
echo "Scanner location: GitHub runner; tunnel endpoint: 127.0.0.1:$LOCAL_PORT"
echo '[1/3] Checking SSM connectivity and pulling ZAP on runner'
CONNECTION=$(aws_ssm get-connection-status --target "$INSTANCE_ID" --query Status --output text)
[[ "${CONNECTION,,}" == connected ]] || { echo "::error::SSM target is not connected: $CONNECTION"; exit 1; }
timeout --kill-after=15s 300s docker pull "$ZAP_IMAGE"

# Fail rather than accidentally scan a pre-existing listener on the local port.
python3 - "$LOCAL_PORT" <<'PY'
import socket, sys
with socket.socket() as s:
    s.bind(('127.0.0.1', int(sys.argv[1])))
PY
PARAMETERS=$(python3 - "$PORT" "$LOCAL_PORT" <<'PY'
import json, sys
print(json.dumps({'portNumber': [sys.argv[1]], 'localPortNumber': [sys.argv[2]]}))
PY
)
echo '[2/3] Opening SSM port-forwarding tunnel to deployed service'
: > "$REPORT_DIR/tunnel.log"
setsid aws ssm start-session --region "$REGION" --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession --parameters "$PARAMETERS" \
  --cli-connect-timeout 10 --cli-read-timeout 20 \
  > "$REPORT_DIR/tunnel.log" 2>&1 < /dev/null &
TUNNEL_PID=$!
TARGET_URL="http://127.0.0.1:$LOCAL_PORT$PATH_SUFFIX"
READY=false
for ATTEMPT in $(seq 1 30); do
  if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
    cat "$REPORT_DIR/tunnel.log"
    echo '::error::SSM tunnel exited. Check StartSession document permissions and the Session Manager plugin.'
    exit 1
  fi
  if curl --fail --silent --show-error --max-time 3 "$TARGET_URL" \
    > "$REPORT_DIR/endpoint-response.txt" 2> "$TEMP_DIR/curl-error.txt"; then
    READY=true; break
  fi
  echo "Tunnel/endpoint readiness: attempt $ATTEMPT/30; total elapsed $((SECONDS - TOTAL_STARTED))s"
  sleep 2
done
cat "$REPORT_DIR/tunnel.log"
if [[ "$READY" != true ]]; then
  cat "$TEMP_DIR/curl-error.txt" >&2
  echo '::error::The deployed endpoint could not be reached through the SSM tunnel.'
  exit 1
fi
echo "Deployed endpoint reachable: $TARGET_URL"

echo '[3/3] Running ZAP on GitHub runner; scanner output streams below'
docker create --user 0:0 --name "$ZAP_CONTAINER" --network host \
  --volume /zap/wrk -e PYTHONUNBUFFERED=1 "$ZAP_IMAGE" \
  zap-baseline.py -t "$TARGET_URL" -m 1 -T 5 -d -l WARN -I \
  -J zap-report.json -r zap-report.html > "$REPORT_DIR/container-id.txt"
SCAN_STARTED=$SECONDS
# Save the scanner status separately so tee cannot hide a failure.
(
  set +e
  timeout --kill-after=15s 600s docker start --attach "$ZAP_CONTAINER" 2>&1 | tee "$REPORT_DIR/zap.log"
  PIPE_CODES=("${PIPESTATUS[@]}")
  SCAN_CODE=${PIPE_CODES[0]}
  if ((PIPE_CODES[1] != 0)); then SCAN_CODE=1; fi
  printf '%s\n' "$SCAN_CODE" > "$TEMP_DIR/scan-code"
) &
SCAN_PID=$!
while kill -0 "$SCAN_PID" 2>/dev/null; do
  if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
    echo '::error::SSM tunnel closed during the scan.'
    cat "$REPORT_DIR/tunnel.log"
    exit 1
  fi
  echo "ZAP running on runner: elapsed $((SECONDS - SCAN_STARTED))s; total $((SECONDS - TOTAL_STARTED))s"
  sleep 10
done
wait "$SCAN_PID"
SCAN_PID=
SCAN_CODE=$(cat "$TEMP_DIR/scan-code")
echo "ZAP exit code: $SCAN_CODE"
# Preserve evidence before container cleanup, including after timeout/failure.
timeout 15s docker inspect --format '{{json .State}}' "$ZAP_CONTAINER" > "$REPORT_DIR/container-state.json" || true
timeout 15s docker cp "$ZAP_CONTAINER:/root/.ZAP/zap.log" "$REPORT_DIR/zap-internal.log" 2> "$REPORT_DIR/zap-internal-copy.log" || true
REPORTS_OK=true
for NAME in zap-report.json zap-report.html; do
  timeout 15s docker cp "$ZAP_CONTAINER:/zap/wrk/$NAME" "$REPORT_DIR/$NAME" || REPORTS_OK=false
done
if [[ "$SCAN_CODE" != 0 ]]; then
  echo "::error::ZAP failed with code $SCAN_CODE. Review ec2-dast artifacts."
  exit "$SCAN_CODE"
fi
if [[ "$REPORTS_OK" != true ]]; then
  echo '::error::ZAP completed without the required HTML/JSON reports.'; exit 1
fi
echo "DAST passed against deployed EC2 service in $((SECONDS - TOTAL_STARTED))s."
