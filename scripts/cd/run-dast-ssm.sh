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
# Separate commands expose completed stage output without new IAM permissions.
# SSM invocation stdout/stderr are not a live log stream.
python3 - "$PORT" "$PATH_SUFFIX" "$TEMP_DIR" <<'PY'
import json, pathlib, shlex, sys
url = shlex.quote('http://127.0.0.1:' + sys.argv[1] + sys.argv[2])
stages = [
    ('endpoint', 60, '''set -eu
printf '%s\\n' 'DAST: checking Docker and the deployed endpoint'
docker version --format '{{.Server.Version}}'
curl --fail --show-error --max-time 15 TARGET_URL
printf '\\n%s\\n' 'DAST: endpoint check passed'
'''),
    ('pull', 210, '''set -eu
printf '%s\\n' 'DAST: pulling ZAP (maximum 180 seconds)'
timeout --kill-after=15s 180s docker pull ghcr.io/zaproxy/zaproxy:stable
printf '%s\\n' 'DAST: ZAP image ready'
'''),
    ('scan', 390, '''set -eu
ZAP_CONTAINER="mmiv-dast-$$"
trap 'docker rm -f "$ZAP_CONTAINER" >/dev/null 2>&1 || true' EXIT HUP INT TERM
printf '%s\\n' 'DAST: ZAP startup, one-minute spider, then passive scanning'
printf '%s\\n' 'DAST: scan limit 360 seconds; debug output is returned on completion'
set +e
timeout --kill-after=15s 360s docker run --rm --name "$ZAP_CONTAINER" --network host -e PYTHONUNBUFFERED=1 ghcr.io/zaproxy/zaproxy:stable zap-baseline.py -t TARGET_URL -m 1 -T 4 -d -l WARN -I
SCAN_CODE=$?
set -e
printf 'DAST scanner exit code: %s\\n' "$SCAN_CODE"
exit "$SCAN_CODE"
'''),
]
for name, seconds, script in stages:
    path = pathlib.Path(sys.argv[3]) / (name + '.json')
    path.write_text(json.dumps({'commands': [script.replace('TARGET_URL', url)],
                                'executionTimeout': [str(seconds)]}))
PY

echo "DAST target: $INSTANCE_ID; URL: http://127.0.0.1:$PORT$PATH_SUFFIX"
echo 'Detailed scanner logs appear when each SSM stage completes.'
TOTAL_STARTED=$SECONDS
for STAGE in endpoint pull scan; do
  case "$STAGE" in
    endpoint) LABEL='1/3 endpoint check'; EXECUTION_TIMEOUT=60 ;;
    pull) LABEL='2/3 ZAP image download'; EXECUTION_TIMEOUT=210 ;;
    scan) LABEL='3/3 ZAP baseline scan'; EXECUTION_TIMEOUT=390 ;;
  esac
  echo "::group::DAST $LABEL"
  COMMAND_ID=$(aws_ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --comment "MMIV DAST $STAGE port $PORT" \
    --timeout-seconds 120 \
    --parameters "file://$TEMP_DIR/$STAGE.json" \
    --query Command.CommandId --output text)
  echo "DAST SSM command ID: $COMMAND_ID"
  echo "AWS console: https://$REGION.console.aws.amazon.com/systems-manager/run-command/$COMMAND_ID?region=$REGION"
  echo "Delivery timeout: 120s; execution timeout: ${EXECUTION_TIMEOUT}s"
  STARTED=$SECONDS
  # Allow delivery, execution and API/reporting margin; avoid the old 720s cutoff.
  DEADLINE=$((STARTED + 120 + EXECUTION_TIMEOUT + 120))
  MISSING_ATTEMPTS=0
  TERMINAL=false
  STATE=unavailable
  while ((SECONDS < DEADLINE)); do
    if ! aws_ssm get-command-invocation --command-id "$COMMAND_ID" \
      --instance-id "$INSTANCE_ID" --output json \
      > "$TEMP_DIR/result.json" 2> "$TEMP_DIR/error.txt"; then
      if grep -q 'InvocationDoesNotExist' "$TEMP_DIR/error.txt" && ((MISSING_ATTEMPTS < 12)); then
        MISSING_ATTEMPTS=$((MISSING_ATTEMPTS + 1))
        echo "[$LABEL] Waiting for SSM invocation ($MISSING_ATTEMPTS/12); elapsed $((SECONDS - STARTED))s."
        sleep 5
        continue
      fi
      cat "$TEMP_DIR/error.txt" >&2
      echo "::error::Cannot retrieve DAST command $COMMAND_ID. Check SSM Run Command."
      exit 1
    fi
    STATE=$(python3 - "$TEMP_DIR/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print(r.get('Status', 'Unknown') + '|' + r.get('StatusDetails', 'Unknown'))
PY
)
    STATUS="${STATE%%|*}"
    DETAILS="${STATE#*|}"
    printf '[%s] elapsed %ss; total %ss; status=%s; details=%s\n' \
      "$LABEL" "$((SECONDS - STARTED))" "$((SECONDS - TOTAL_STARTED))" "$STATUS" "$DETAILS"
    python3 - "$TEMP_DIR/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print('SSM execution start: ' + (r.get('ExecutionStartDateTime') or 'not reported yet')
      + '; execution elapsed: ' + (r.get('ExecutionElapsedTime') or 'not reported yet')
      + '; response code: ' + str(r.get('ResponseCode', -1)))
if not r.get('ExecutionStartDateTime'):
    print('Progress: execution has not been reported yet; waiting for SSM delivery/start.')
else:
    print('Progress: execution start reported; waiting for stage completion and its output.')
print('Output currently available: '
      + str(len(r.get('StandardOutputContent', ''))) + ' stdout characters; '
      + str(len(r.get('StandardErrorContent', ''))) + ' stderr characters.')
PY
    case "$DETAILS" in
      Undeliverable|Terminated|DeliveryTimedOut|ExecutionTimedOut|'Delivery Timed Out'|'Execution Timed Out') TERMINAL=true ;;
      *)
        case "$STATUS" in
          Pending|InProgress|Delayed|Cancelling) TERMINAL=false ;;
          *) TERMINAL=true ;;
        esac ;;
    esac
    if [[ "$TERMINAL" == true ]]; then break; fi
    sleep 10
  done
  if [[ "$TERMINAL" != true ]]; then
    echo "::error::Stopped waiting for DAST $STAGE command $COMMAND_ID. Last status: $STATE."
    echo 'The remote command may still be running. Check SSM before rerunning.'
    if [[ -s "$TEMP_DIR/result.json" ]]; then cat "$TEMP_DIR/result.json"; fi
    exit 1
  fi
  python3 - "$TEMP_DIR/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
for field in ['Status', 'StatusDetails', 'ResponseCode', 'ExecutionStartDateTime',
              'ExecutionElapsedTime', 'ExecutionEndDateTime',
              'StandardOutputContent', 'StandardErrorContent']:
    print(f"{field}:\n{r.get(field, '')}")
sys.exit(0 if r.get('Status') == 'Success' and r.get('ResponseCode') == 0 else 1)
PY
  echo "DAST $LABEL completed in $((SECONDS - STARTED))s."
  echo '::endgroup::'
done
echo "DAST passed all three stages in $((SECONDS - TOTAL_STARTED))s."
