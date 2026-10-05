#!/usr/bin/env bash
set -euo pipefail

INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
REGION="${AWS_REGION:-ap-southeast-1}"

COMMAND_ID=$(aws ssm send-command \
  --region "$REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --parameters commands='curl -fsS http://127.0.0.1:8081/actuator/health' \
  --query 'Command.CommandId' \
  --output text)

aws ssm wait command-executed \
  --region "$REGION" \
  --command-id "$COMMAND_ID" \
  --instance-id "$INSTANCE_ID"

aws ssm get-command-invocation \
  --region "$REGION" \
  --command-id "$COMMAND_ID" \
  --instance-id "$INSTANCE_ID" \
  --query 'StandardOutputContent' \
  --output text
