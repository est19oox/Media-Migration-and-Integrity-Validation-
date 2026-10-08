#!/usr/bin/env bash
set -euo pipefail

INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
REGION="${AWS_REGION:-ap-southeast-1}"

# EC2 running does not mean the SSM agent is registered or cloud-init has finished.
CONNECTED=false
for attempt in $(seq 1 60); do
  STATUS=$(aws ssm get-connection-status \
    --region "$REGION" --target "$INSTANCE_ID" \
    --query Status --output text)
  if [ "${STATUS,,}" = connected ]; then
    CONNECTED=true
    break
  fi
  sleep 10
done
if [ "$CONNECTED" != true ]; then
  echo "::error::SSM did not become Connected for $INSTANCE_ID within 10 minutes."
  exit 1
fi

# Wait for user-data package installation before Ansible also invokes dnf.
COMMAND_ID=$(aws ssm send-command \
  --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --parameters '{"commands":["cloud-init status --wait"],"executionTimeout":["600"]}' \
  --query 'Command.CommandId' --output text)
STATUS=Pending
for attempt in $(seq 1 120); do
  STATUS=$(aws ssm get-command-invocation \
    --region "$REGION" --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in
    Success) exit 0 ;;
    Failed|Cancelled|TimedOut) break ;;
  esac
  sleep 5
done
aws ssm get-command-invocation \
  --region "$REGION" --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query '{Status:Status,Stdout:StandardOutputContent,Stderr:StandardErrorContent}' --output json || true
echo "::error::Host initialization did not succeed: $STATUS"
exit 1
