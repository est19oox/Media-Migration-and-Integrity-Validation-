#!/usr/bin/env bash
set -euo pipefail
export AWS_PAGER=""

INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
REGION="${AWS_REGION:-ap-southeast-1}"
PORT="${DAST_PORT:?DAST_PORT is required}"
PATH_SUFFIX="${DAST_PATH:-/}"
[[ "$PORT" =~ ^[0-9]+$ ]] && ((10#$PORT >= 1 && 10#$PORT <= 65535)) || {
  echo '::error::DAST_PORT must be a port between 1 and 65535.'; exit 1;
}
[[ "$PATH_SUFFIX" == /* ]] || { echo '::error::DAST_PATH must start with /.'; exit 1; }

aws_ssm() {
  aws ssm "$@" --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 20
}

# Check immediately before the scan, even if deployment checked SSM earlier.
CONNECTED=false
for attempt in $(seq 1 12); do
  CONNECTION=$(aws_ssm get-connection-status --target "$INSTANCE_ID" --query Status --output text)
  echo "SSM connectivity for $INSTANCE_ID: $CONNECTION (attempt $attempt/12)"
  if [[ "${CONNECTION,,}" == connected ]]; then CONNECTED=true; break; fi
  sleep 5
done
if [[ "$CONNECTED" != true ]]; then
  echo "::error::SSM is not connected to $INSTANCE_ID. DAST was not submitted."
  exit 1
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
# JSON and shell quoting prevent path characters from changing the remote command.
python3 - "$PORT" "$PATH_SUFFIX" > "$TEMP_DIR/parameters.json" <<'PY'
import json, shlex, sys
url = shlex.quote('http://127.0.0.1:' + sys.argv[1] + sys.argv[2])
script = '''set -eu
ZAP_CONTAINER="mmiv-dast-$$"
trap 'docker rm -f "$ZAP_CONTAINER" >/dev/null 2>&1 || true' EXIT HUP INT TERM
printf '%s\\n' 'DAST: checking the deployed endpoint'
curl --fail --show-error --max-time 15 --output /dev/null TARGET_URL
printf '%s\\n' 'DAST: pulling ZAP (maximum 180 seconds)'
timeout --kill-after=15s 180s docker pull ghcr.io/zaproxy/zaproxy:stable
printf '%s\\n' 'DAST: running ZAP baseline (maximum 360 seconds)'
set +e
timeout --kill-after=15s 360s docker run --rm --name "$ZAP_CONTAINER" --network host ghcr.io/zaproxy/zaproxy:stable zap-baseline.py -t TARGET_URL -l WARN -I
SCAN_CODE=$?
set -e
printf 'DAST scanner exit code: %s\\n' "$SCAN_CODE"
exit "$SCAN_CODE"
'''.replace('TARGET_URL', url)
json.dump({'commands': [script], 'executionTimeout': ['600']}, sys.stdout)
PY

COMMAND_ID=$(aws_ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "MMIV DAST port $PORT" \
  --timeout-seconds 60 \
  --parameters "file://$TEMP_DIR/parameters.json" \
  --query Command.CommandId --output text)
echo "DAST SSM command ID: $COMMAND_ID"
echo "Target: $INSTANCE_ID; URL: http://127.0.0.1:$PORT$PATH_SUFFIX"

# Poll one JSON response so status, response code and output describe the same result.
DEADLINE=$((SECONDS + 720))
MISSING_ATTEMPTS=0
while ((SECONDS < DEADLINE)); do
  if ! aws_ssm get-command-invocation --command-id "$COMMAND_ID" \
    --instance-id "$INSTANCE_ID" --output json \
    > "$TEMP_DIR/result.json" 2> "$TEMP_DIR/error.txt"; then
    # SSM is eventually consistent immediately after SendCommand.
    if grep -q 'InvocationDoesNotExist' "$TEMP_DIR/error.txt" && ((MISSING_ATTEMPTS < 12)); then
      MISSING_ATTEMPTS=$((MISSING_ATTEMPTS + 1))
      echo "Waiting for command invocation to appear ($MISSING_ATTEMPTS/12)."
      sleep 5
      continue
    fi
    cat "$TEMP_DIR/error.txt" >&2
    echo "::error::Cannot retrieve DAST command $COMMAND_ID. Check it in SSM Run Command."
    exit 1
  fi
  STATE=$(python3 - "$TEMP_DIR/result.json" <<'PY'
import json, sys
r=json.load(open(sys.argv[1]))
print(r.get('Status', 'Unknown') + '|' + r.get('StatusDetails', 'Unknown'))
PY
)
  STATUS="${STATE%%|*}"
  DETAILS="${STATE#*|}"
  echo "DAST status: $STATUS; details: $DETAILS"
  case "$DETAILS" in
    Undeliverable|Terminated|'Delivery Timed Out'|'Execution Timed Out') TERMINAL=true ;;
    *)
      case "$STATUS" in
        Pending|InProgress|Delayed|Cancelling) TERMINAL=false ;;
        *) TERMINAL=true ;;
      esac ;;
  esac
  if [[ "$TERMINAL" == true ]]; then
    python3 - "$TEMP_DIR/result.json" <<'PY'
import json, sys
r=json.load(open(sys.argv[1]))
for field in ['Status', 'StatusDetails', 'ResponseCode', 'StandardOutputContent', 'StandardErrorContent']:
    print(f"{field}:\n{r.get(field, '')}")
# Require both SSM success and a successful remote command.
sys.exit(0 if r.get('Status') == 'Success' and r.get('ResponseCode') == 0 else 1)
PY
    exit 0
  fi
  sleep 5
done

echo "::error::Stopped waiting for DAST command $COMMAND_ID after 720 seconds. Last status: ${STATE:-unavailable}."
if [[ -s "$TEMP_DIR/result.json" ]]; then cat "$TEMP_DIR/result.json"; fi
exit 1
