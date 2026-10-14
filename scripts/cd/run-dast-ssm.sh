#!/usr/bin/env bash
# ZAP runs on the GitHub runner; only HTTP traffic traverses SSM to EC2.
set -euo pipefail
export AWS_PAGER=""
INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
REGION="${AWS_REGION:-ap-southeast-1}"
PORT="${DAST_PORT:?DAST_PORT is required}"
# Zero asks the operating system for a free port for this scan.
LOCAL_PORT="${DAST_LOCAL_PORT:-0}"
PATH_SUFFIX="${DAST_PATH:-/}"
READY_PATH="${DAST_READY_PATH:-/actuator/health}"
SCAN_MODE="${DAST_SCAN_MODE:-baseline}"
SERVICE_NAME="${DAST_SERVICE_NAME:?DAST_SERVICE_NAME is required}"
OPENAPI_FILE="${DAST_OPENAPI_FILE:-tests/api/${SERVICE_NAME%-service}-openapi.json}"
# Backend functional checks and scans use the Gateway so login shares the tunnel.
if [[ "$SERVICE_NAME" == transfer-service || "$SERVICE_NAME" == user-access-service || "$SERVICE_NAME" == discovery-service || "$SERVICE_NAME" == reporting-service || "$SERVICE_NAME" == integrity-service ]]; then PORT=8080; fi
ENDPOINT_BASE_PATH="${DAST_ENDPOINT_BASE_PATH:-}"
[[ -z "$ENDPOINT_BASE_PATH" || "$ENDPOINT_BASE_PATH" == /* ]] || {
  echo '::error::DAST_ENDPOINT_BASE_PATH must be empty or start with /.'; exit 1;
}
[[ "$SCAN_MODE" == baseline || "$SCAN_MODE" == api ]] || {
  echo '::error::DAST_SCAN_MODE must be baseline or api.'; exit 1;
}
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}"
for VALUE in "$PORT"; do
  [[ "$VALUE" =~ ^[0-9]+$ ]] && ((10#$VALUE >= 1 && 10#$VALUE <= 65535)) || {
    echo '::error::DAST ports must be between 1 and 65535.'; exit 1;
  }
done
[[ "$LOCAL_PORT" =~ ^[0-9]+$ ]] && ((10#$LOCAL_PORT <= 65535)) || {
  echo '::error::DAST_LOCAL_PORT must be between 0 and 65535 (0 selects a free port).'; exit 1;
}
[[ "$PATH_SUFFIX" == /* && "$READY_PATH" == /* ]] || { echo '::error::DAST_PATH must start with /.'; exit 1; }
if [[ "${SCANNER_TOOLS_PREPARED:-false}" != true && "${DAST_CLEANUP_ONLY:-false}" != true ]]; then
  bash scripts/cd/prepare-scanner-tools.sh
fi
for COMMAND in aws session-manager-plugin docker curl python3 timeout setsid; do
  command -v "$COMMAND" >/dev/null || { echo "::error::Missing command: $COMMAND"; exit 1; }
done
REPORT_DIR="${DAST_REPORT_DIR:-$PWD/.ci/reports/ec2-dast}"
mkdir -p "$REPORT_DIR"
TEMP_DIR=$(mktemp -d)
TUNNEL_PID=
SCAN_PID=
ZAP_CONTAINER="mmiv-ec2-dast-$$"
ZAP_CONTAINER_CREATED=false
ZAP_CLEANUP_ATTEMPTED=false
ZAP_EVIDENCE_CAPTURED=false
CURRENT_ATTEMPT=0
SCAN_CODE=
# Never use a previous scan's session ID during cleanup of an early failure.
: > "$REPORT_DIR/tunnel.log"
OWNS_VERIFICATION_RUN=false
if [[ -z "${MMIV_VERIFICATION_RUN_ID:-}" ]]; then
  MMIV_VERIFICATION_RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
  OWNS_VERIFICATION_RUN=true
fi
export MMIV_VERIFICATION_RUN_ID
if [[ -z "${USER_ACCESS_TEST_USERNAME:-}" ]]; then
  export MMIV_VERIFICATION_DESTINATION="mmiv-verification-$MMIV_VERIFICATION_RUN_ID"
else
  export MMIV_VERIFICATION_DESTINATION="${MMIV_VERIFICATION_DESTINATION:-}"
  [[ -n "$MMIV_VERIFICATION_DESTINATION" ]] || { echo 'Supply a verification destination for the existing test account.' >&2; exit 1; }
fi
TOTAL_STARTED=$SECONDS
aws_ssm() {
  aws ssm "$@" --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 20
}
preserve_zap_evidence() {
  local PREFIX="$1"
  [[ "$ZAP_CONTAINER_CREATED" == true ]] || return 0

  if ! timeout 10s docker logs "$ZAP_CONTAINER" \
    > "$REPORT_DIR/${PREFIX}container.log" 2>&1; then
    echo "::warning::Could not collect ZAP container logs; see ${PREFIX}container.log." >&2
  fi
  if ! timeout 10s docker inspect --format '{{json .State}}' "$ZAP_CONTAINER" \
    > "$REPORT_DIR/${PREFIX}container-state.json" 2> "$REPORT_DIR/${PREFIX}container-inspect.log"; then
    echo "::warning::Could not inspect ZAP container; see ${PREFIX}container-inspect.log." >&2
  fi
  if ! timeout 10s docker cp "$ZAP_CONTAINER:/root/.ZAP/zap.log" \
    "$REPORT_DIR/${PREFIX}zap-internal.log" 2> "$REPORT_DIR/${PREFIX}zap-internal-copy.log"; then
    echo "::warning::Could not copy the internal ZAP log; see ${PREFIX}zap-internal-copy.log." >&2
  fi
  if [[ "$SCAN_MODE" == api ]]; then
    if ! timeout 10s docker cp "$ZAP_CONTAINER:/zap/wrk/openapi.json" \
      "$REPORT_DIR/${PREFIX}openapi.json" 2> "$REPORT_DIR/${PREFIX}openapi-copy.log"; then
      echo "::warning::Could not copy the scanner OpenAPI file; see ${PREFIX}openapi-copy.log." >&2
    fi
  fi
  for NAME in zap-report.json zap-report.html; do
    if ! timeout 10s docker cp "$ZAP_CONTAINER:/zap/wrk/$NAME" \
      "$REPORT_DIR/${PREFIX}$NAME" 2> "$REPORT_DIR/${PREFIX}${NAME}.copy.log"; then
      echo "::warning::Could not copy partial $NAME; see ${PREFIX}${NAME}.copy.log." >&2
    fi
  done
  if [[ "$SERVICE_NAME" == transfer-service && "$SCAN_MODE" == api ]]; then
    if ! timeout 10s docker cp "$ZAP_CONTAINER:/zap/wrk/upload-seed.json" \
      "$REPORT_DIR/${PREFIX}upload-seed.json" 2> "$REPORT_DIR/${PREFIX}upload-seed-copy.log"; then
      echo "::warning::Could not copy partial upload seed evidence; see ${PREFIX}upload-seed-copy.log." >&2
    fi
  fi
  ZAP_EVIDENCE_CAPTURED=true
}
remove_zap_container() {
  [[ "$ZAP_CONTAINER_CREATED" == true ]] || return 0
  ZAP_CLEANUP_ATTEMPTED=true
  if timeout 15s docker rm -f -v "$ZAP_CONTAINER" \
    > "$REPORT_DIR/zap-container-cleanup.log" 2>&1; then
    ZAP_CONTAINER_CREATED=false
    return 0
  fi
  cat "$REPORT_DIR/zap-container-cleanup.log" >&2
  echo "::error::Could not remove ZAP scan container $ZAP_CONTAINER; see zap-container-cleanup.log." >&2
  return 1
}
stop_scan_process() {
  [[ -n "$SCAN_PID" ]] || return 0
  kill -TERM -- "-$SCAN_PID" 2>/dev/null
  for _ in $(seq 1 50); do
    kill -0 -- "-$SCAN_PID" 2>/dev/null || break
    sleep 0.1
  done
  kill -KILL -- "-$SCAN_PID" 2>/dev/null
  wait "$SCAN_PID" 2>/dev/null
  SCAN_PID=
}
cleanup() {
  EXIT_CODE=$?
  trap - EXIT
  set +e
  if [[ "$ZAP_CONTAINER_CREATED" == true && "$ZAP_EVIDENCE_CAPTURED" != true ]]; then
    preserve_zap_evidence "zap-interrupted-"
  fi
  stop_scan_process
  if [[ "$ZAP_CONTAINER_CREATED" == true && "$ZAP_CLEANUP_ATTEMPTED" != true ]]; then
    if ! remove_zap_container; then EXIT_CODE=1; fi
  fi
  if [[ "$OWNS_VERIFICATION_RUN" == true && "$PORT" == 8080 && -f "$TEMP_DIR/auth.env" && "${DAST_CLEANUP_ONLY:-false}" != true ]]; then
    MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" VERIFICATION_AUTH_BASE_PATH=/user-access \
      python3 scripts/cd/login-for-verification.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" \
        > "$REPORT_DIR/data-cleanup-login.log" 2>&1 && \
      python3 scripts/cd/cleanup-verification-data.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" "$MMIV_VERIFICATION_RUN_ID" "$REPORT_DIR/data-cleanup.json" \
        > "$REPORT_DIR/data-cleanup.log" 2>&1 || EXIT_CODE=1
  fi
  if [[ "${ACCOUNT_FILE:-}" == "$TEMP_DIR/account.json" && -f "$TEMP_DIR/account.json" ]]; then
    MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" VERIFICATION_AUTH_BASE_PATH="${AUTH_PREFIX-/user-access}" \
      python3 scripts/cd/login-for-verification.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" --cleanup \
        > "$REPORT_DIR/account-cleanup.log" 2>&1 || EXIT_CODE=1
  fi
  SESSION_ID=$(sed -n 's/.*Starting session with SessionId: \([^[:space:]]*\).*/\1/p' "$REPORT_DIR/tunnel.log" 2>/dev/null | head -n 1)
  if [[ -n "$SESSION_ID" ]]; then
    aws_ssm terminate-session --session-id "$SESSION_ID" >/dev/null 2>&1 || echo "Warning: could not explicitly terminate SSM session $SESSION_ID."
  fi
  if [[ -n "$TUNNEL_PID" ]]; then
    kill -TERM -- "-$TUNNEL_PID" 2>/dev/null
    # The plugin is a child of aws; wait for the entire owned process group.
    for _ in $(seq 1 20); do
      kill -0 -- "-$TUNNEL_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -KILL -- "-$TUNNEL_PID" 2>/dev/null
    wait "$TUNNEL_PID" 2>/dev/null
  fi
  if [[ -n "${API_TEST_TOKEN:-}" ]]; then
    python3 scripts/cd/redact-verification-token.py "$REPORT_DIR" || true
  fi
  rm -rf "$TEMP_DIR"
  exit "$EXIT_CODE"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

echo "DAST target: EC2 $INSTANCE_ID port $PORT$PATH_SUFFIX"
echo '[1/3] Checking SSM connectivity and prepared ZAP image'
CONNECTION=$(aws_ssm get-connection-status --target "$INSTANCE_ID" --query Status --output text)
[[ "${CONNECTION,,}" == connected ]] || { echo "::error::SSM target is not connected: $CONNECTION"; exit 1; }
if [[ "${SCANNER_TOOLS_PREPARED:-false}" == true && "${DAST_CLEANUP_ONLY:-false}" != true ]]; then
  docker image inspect "$ZAP_IMAGE" >/dev/null || {
    echo '::error::Prepared ZAP image is missing on this runner.'; exit 1;
  }
fi

# Do not reuse a fixed port across scans or kill an unrelated listener.
LOCAL_PORT=$(python3 - "$LOCAL_PORT" <<'PY'
import socket, sys
with socket.socket() as s:
    try:
        s.bind(('127.0.0.1', int(sys.argv[1])))
    except OSError as exc:
        print(f'::error::Cannot bind runner tunnel port {sys.argv[1]}: {exc}. '
              'Unset DAST_LOCAL_PORT or set it to 0 for automatic selection.', file=sys.stderr)
        raise SystemExit(1)
    print(s.getsockname()[1])
PY
)
echo "Scanner location: GitHub runner; tunnel endpoint: 127.0.0.1:$LOCAL_PORT"
PARAMETERS=$(python3 - "$PORT" "$LOCAL_PORT" <<'PY'
import json, sys
print(json.dumps({'portNumber': [sys.argv[1]], 'localPortNumber': [sys.argv[2]]}))
PY
)
echo '[2/3] Opening SSM port-forwarding tunnel to deployed service'
setsid aws ssm start-session --region "$REGION" --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession --parameters "$PARAMETERS" \
  --cli-connect-timeout 10 --cli-read-timeout 20 \
  > "$REPORT_DIR/tunnel.log" 2>&1 < /dev/null &
TUNNEL_PID=$!
TARGET_URL="http://127.0.0.1:$LOCAL_PORT$PATH_SUFFIX"
READY_URL="http://127.0.0.1:$LOCAL_PORT$READY_PATH"
READY=false
: > "$TEMP_DIR/curl-error.txt"
for ATTEMPT in $(seq 1 30); do
  if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
    cat "$REPORT_DIR/tunnel.log"
    echo '::error::SSM tunnel exited. Check StartSession document permissions and the Session Manager plugin.'
    exit 1
  fi
  # Port selection releases its socket before the plugin starts. Require the
  # plugin's bind acknowledgement so a competing listener cannot pass readiness.
  if grep -Fq "Port $LOCAL_PORT opened for sessionId" "$REPORT_DIR/tunnel.log" && \
    curl --fail --silent --show-error --max-time 3 "$READY_URL" \
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
echo "Deployed health endpoint reachable: $READY_URL"
echo "ZAP target configured: $TARGET_URL"

ACCOUNT_FILE="${MMIV_VERIFICATION_ACCOUNT_FILE:-$TEMP_DIR/account.json}"
if [[ "${DAST_CLEANUP_ONLY:-false}" == true && "${DAST_DATA_CLEANUP_ONLY:-false}" == true ]]; then
  MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" VERIFICATION_AUTH_BASE_PATH=/user-access \
    python3 scripts/cd/login-for-verification.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env"
  python3 scripts/cd/cleanup-verification-data.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" "$MMIV_VERIFICATION_RUN_ID" "$REPORT_DIR/data-cleanup.json"
  exit 0
fi
if [[ "${DAST_CLEANUP_ONLY:-false}" == true ]]; then
  MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" VERIFICATION_AUTH_BASE_PATH=/user-access \
    python3 scripts/cd/login-for-verification.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" --cleanup
  exit 0
fi
AUTH_DOCKER_ARGS=()
prepare_auth() {
  if [[ "$SERVICE_NAME" == transfer-service || "$SERVICE_NAME" == api-gateway || "$SERVICE_NAME" == user-access-service || "$SERVICE_NAME" == discovery-service || "$SERVICE_NAME" == reporting-service || "$SERVICE_NAME" == integrity-service ]]; then
    AUTH_PREFIX=/user-access
    if [[ "$SERVICE_NAME" == user-access-service && "$PORT" == 8083 ]]; then AUTH_PREFIX=; fi
    MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" VERIFICATION_AUTH_BASE_PATH="$AUTH_PREFIX" \
      python3 scripts/cd/login-for-verification.py "http://127.0.0.1:$LOCAL_PORT" "$TEMP_DIR/auth.env" \
        --account-env "$TEMP_DIR/account.env"
    while IFS='=' read -r NAME VALUE; do
      MASK_VALUE=${VALUE//%/%25}
      printf '::add-mask::%s\n' "$MASK_VALUE"
      export "$NAME=$VALUE"
    done < "$TEMP_DIR/account.env"
    API_TEST_TOKEN=$(sed -n 's/^API_TEST_TOKEN=//p' "$TEMP_DIR/auth.env")
    printf '::add-mask::%s\n' "$API_TEST_TOKEN"
    export API_TEST_TOKEN
    printf '%s\n' "$API_TEST_TOKEN" >> "$TEMP_DIR/tokens.txt"
    chmod 600 "$TEMP_DIR/tokens.txt"
    export MMIV_VERIFICATION_TOKEN_FILE="$TEMP_DIR/tokens.txt"
    MMIV_VERIFICATION_ACCOUNT_FILE="$ACCOUNT_FILE" python3 scripts/cd/grant-verification-permission.py
    AUTH_DOCKER_ARGS=(--env-file "$TEMP_DIR/auth.env" -e MMIV_VERIFICATION_RUN_ID -e MMIV_VERIFICATION_DESTINATION)
  fi
}
prepare_auth

echo 'Running functional endpoint tests through the SSM tunnel'
ENDPOINT_TOKEN="${API_TEST_TOKEN:-}"
# User Access cases explicitly log in and select their own token. Keep their
# anonymous/invalid-token requests unauthenticated instead of replacing headers.
if [[ "$SERVICE_NAME" == user-access-service ]]; then ENDPOINT_TOKEN=; fi
API_TEST_TOKEN="$ENDPOINT_TOKEN" python3 scripts/cd/test-endpoints.py \
  --service "${DAST_SERVICE_NAME:?DAST_SERVICE_NAME is required}" \
  --base-url "http://127.0.0.1:$LOCAL_PORT$ENDPOINT_BASE_PATH" \
  --report "$REPORT_DIR/endpoint-tests.json"

# Do not spend the scanner's token lifetime on earlier functional tests.
prepare_auth
echo '[3/3] Running ZAP on GitHub runner; scanner output streams below'
echo "ZAP scan starts at: $TARGET_URL"
SCAN_TIMEOUT=600s
if [[ "$SCAN_MODE" == api ]]; then
  # JSON OpenAPI uses the standard library and needs no Swagger dependency.
  python3 - "$LOCAL_PORT" "$TEMP_DIR/openapi.json" "$OPENAPI_FILE" "$SERVICE_NAME" <<'PY'
import json, os, sys, uuid
from pathlib import Path
spec = json.loads(Path(sys.argv[3]).read_text())
if not spec.get('paths'):
    raise ValueError('OpenAPI definition must contain implemented API routes')
spec['servers'] = [{'url': 'http://127.0.0.1:' + sys.argv[1]}]
Path(sys.argv[2]).write_text(json.dumps(spec, indent=2) + '\n')
PY
  SCAN_TIMEOUT=1200s
  echo "Active API scan: $SERVICE_NAME using $OPENAPI_FILE; requests may change test data."
fi
ATTEMPT_LIMIT=3
SCAN_PASSED=false
USED_ZAP_PROXY_PORTS=()
for CURRENT_ATTEMPT in $(seq 1 "$ATTEMPT_LIMIT"); do
  ZAP_PROXY_PORT=
  for PORT_SELECTION_ATTEMPT in $(seq 1 10); do
    CANDIDATE_PROXY_PORT=$(python3 - <<'PY'
import socket

with socket.socket() as proxy:
    proxy.bind(('0.0.0.0', 0))
    print(proxy.getsockname()[1])
PY
)
    if [[ " ${USED_ZAP_PROXY_PORTS[*]} " != *" $CANDIDATE_PROXY_PORT "* ]]; then
      ZAP_PROXY_PORT="$CANDIDATE_PROXY_PORT"
      break
    fi
  done
  if [[ -z "$ZAP_PROXY_PORT" ]]; then
    echo "::error::Could not select a fresh ZAP proxy port after 10 candidates."
    exit 1
  fi
  USED_ZAP_PROXY_PORTS+=("$ZAP_PROXY_PORT")
  echo "ZAP startup attempt $CURRENT_ATTEMPT/$ATTEMPT_LIMIT using proxy port $ZAP_PROXY_PORT"
  ZAP_PROXY_CONFIG="-config network.localServers.mainProxy.port=$ZAP_PROXY_PORT"
  if [[ "$SCAN_MODE" == baseline ]]; then
    SCAN_ARGS=(zap-baseline.py -t "$TARGET_URL" -m 1 -T 5 -d -l WARN -I -z "$ZAP_PROXY_CONFIG")
  else
    SCAN_ARGS=(zap-api-scan.py -t /zap/wrk/openapi.json -f openapi -T 5 -d -l WARN -I
      -z "-config ascan.maxScanDurationInMins=10 -config ascan.maxRuleDurationInMins=1 $ZAP_PROXY_CONFIG")
    if [[ "$SERVICE_NAME" == transfer-service ]]; then
      SCAN_ARGS+=(--hook /zap/wrk/zap-upload-hook.py)
    fi
  fi
  ZAP_CONTAINER="mmiv-ec2-dast-$$-$CURRENT_ATTEMPT"
  ZAP_EVIDENCE_CAPTURED=false
  ZAP_CLEANUP_ATTEMPTED=false
  docker create --user 0:0 --name "$ZAP_CONTAINER" --network host \
    --volume /zap/wrk -e PYTHONUNBUFFERED=1 "${AUTH_DOCKER_ARGS[@]}" "$ZAP_IMAGE" \
    "${SCAN_ARGS[@]}" -J zap-report.json -r zap-report.html \
    > "$REPORT_DIR/container-id.txt"
  ZAP_CONTAINER_CREATED=true
  if [[ "$SCAN_MODE" == api ]]; then
    timeout 15s docker cp "$TEMP_DIR/openapi.json" "$ZAP_CONTAINER:/zap/wrk/openapi.json"
    cp "$TEMP_DIR/openapi.json" "$REPORT_DIR/openapi.json"
    if [[ "$SERVICE_NAME" == transfer-service ]]; then
      timeout 15s docker cp tests/api/zap-upload-hook.py "$ZAP_CONTAINER:/zap/wrk/zap-upload-hook.py"
    fi
  fi

  ATTEMPT_LOG="$REPORT_DIR/zap-attempt-$CURRENT_ATTEMPT.log"
  rm -f "$TEMP_DIR/scan-code"
  SCAN_STARTED=$SECONDS
  # The scanner may fail to bind after the port probe has released its socket.
  setsid bash -c '
    set +e
    timeout --kill-after=15s "$1" docker start --attach "$2" 2>&1 | tee "$3" | tee -a "$4"
    PIPE_CODES=("${PIPESTATUS[@]}")
    SCAN_RESULT=${PIPE_CODES[0]}
    if ((PIPE_CODES[1] != 0 || PIPE_CODES[2] != 0)); then SCAN_RESULT=1; fi
    printf "%s\n" "$SCAN_RESULT" > "$5"
  ' _ "$SCAN_TIMEOUT" "$ZAP_CONTAINER" "$ATTEMPT_LOG" "$REPORT_DIR/zap.log" "$TEMP_DIR/scan-code" &
  SCAN_PID=$!
  while [[ ! -f "$TEMP_DIR/scan-code" ]]; do
    if ! kill -0 "$SCAN_PID" 2>/dev/null; then
      wait "$SCAN_PID"
      echo '::error::ZAP scanner process exited without recording an exit code.'
      exit 1
    fi
    if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
      echo '::error::SSM tunnel closed during the scan.'
      cat "$REPORT_DIR/tunnel.log"
      exit 1
    fi
    if (( (SECONDS - SCAN_STARTED) % 10 == 0 )); then
      echo "ZAP running: attempt $CURRENT_ATTEMPT/$ATTEMPT_LIMIT; elapsed $((SECONDS - SCAN_STARTED))s; total $((SECONDS - TOTAL_STARTED))s"
    fi
    sleep 1
  done
  wait "$SCAN_PID"
  SCAN_PID=
  SCAN_CODE=$(cat "$TEMP_DIR/scan-code")
  echo "ZAP attempt $CURRENT_ATTEMPT exit code: $SCAN_CODE"
  preserve_zap_evidence "zap-attempt-$CURRENT_ATTEMPT-"

  if [[ "$SCAN_CODE" == 0 ]]; then
    SCAN_PASSED=true
    break
  fi
  if ! grep -Eiq '(BindException[^[:cntrl:]]*Address already in use|bind[^[:cntrl:]]*address already in use|address already in use[^[:cntrl:]]*bind|EADDRINUSE)' "$ATTEMPT_LOG"; then
    echo "::error::ZAP failed with code $SCAN_CODE. Review zap-attempt-$CURRENT_ATTEMPT.log and captured container evidence."
    exit "$SCAN_CODE"
  fi
  if [[ "$CURRENT_ATTEMPT" == "$ATTEMPT_LIMIT" ]]; then
    echo "::error::ZAP could not start because of a proxy-port binding conflict after $ATTEMPT_LIMIT attempts."
    echo "::error::Original ZAP exit code: $SCAN_CODE."
    exit "$SCAN_CODE"
  fi
  echo "::warning::ZAP startup reported a proxy-port binding conflict on attempt $CURRENT_ATTEMPT."
  if ! remove_zap_container; then
    echo "::error::Original ZAP exit code: $SCAN_CODE; container cleanup also failed."
    echo "::error::Stopping DAST retries because failed-attempt container cleanup failed." >&2
    exit "$SCAN_CODE"
  fi
done

if [[ "$SCAN_PASSED" != true ]]; then
  echo '::error::ZAP did not complete successfully.'
  exit 1
fi
cp "$REPORT_DIR/zap-attempt-$CURRENT_ATTEMPT-zap-report.json" "$REPORT_DIR/zap-report.json" 2>/dev/null || true
cp "$REPORT_DIR/zap-attempt-$CURRENT_ATTEMPT-zap-report.html" "$REPORT_DIR/zap-report.html" 2>/dev/null || true
REPORTS_OK=true
for NAME in zap-report.json zap-report.html; do
  if [[ ! -s "$REPORT_DIR/$NAME" ]]; then REPORTS_OK=false; fi
done
SEED_OK=true
if [[ "$SERVICE_NAME" == transfer-service && "$SCAN_MODE" == api ]]; then
  cp "$REPORT_DIR/zap-attempt-$CURRENT_ATTEMPT-upload-seed.json" "$REPORT_DIR/upload-seed.json" 2>/dev/null || true
  if [[ ! -s "$REPORT_DIR/upload-seed.json" ]]; then SEED_OK=false; fi
  if [[ "$SEED_OK" == true ]]; then
    python3 - "$REPORT_DIR/upload-seed.json" <<'PY' || SEED_OK=false
import json, sys
if json.load(open(sys.argv[1])).get('validated') is not True:
    raise SystemExit('ZAP multipart upload seed was not validated')
PY
  fi
fi
if [[ "$REPORTS_OK" != true ]]; then
  echo '::error::ZAP completed without the required non-empty HTML/JSON reports.'; exit 1
fi
if [[ "$SEED_OK" != true ]]; then
  echo '::error::ZAP did not capture a successful multipart upload. Review upload-seed.json and zap.log.'; exit 1
fi
if ! remove_zap_container; then exit 1; fi
echo "DAST passed against deployed EC2 service in $((SECONDS - TOTAL_STARTED))s."
