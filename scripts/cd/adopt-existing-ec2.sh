#!/usr/bin/env bash
# Run after terraform init, from infrastructure/terraform. Never creates/deletes EC2.
set -euo pipefail
STATE_RESOURCES=$(terraform state list)
MANAGED_ID=''
if printf '%s\n' "$STATE_RESOURCES" | grep -Fxq aws_instance.mmiv; then
  MANAGED_ID=$(terraform show -json | python3 -c '
import json,sys
root = json.load(sys.stdin)["values"]["root_module"]
resource = next(r for r in root.get("resources", []) if r["address"] == "aws_instance.mmiv")
print(resource["values"]["id"])
')
  test -n "$MANAGED_ID"
fi

INSTANCES=$(aws ec2 describe-instances \
  --region "${AWS_REGION:-ap-southeast-1}" \
  --filters 'Name=tag:Name,Values=MMIV-EC2' \
    'Name=instance-state-name,Values=pending,running,stopping,stopped' \
  --query 'Reservations[].Instances[].InstanceId' --output json)
INSTANCE_LINES=$(python3 -c 'import json,sys; sys.stdout.write("\n".join(json.load(sys.stdin)))' <<< "$INSTANCES")
INSTANCE_IDS=()
if [ -n "$INSTANCE_LINES" ]; then
  mapfile -t INSTANCE_IDS <<< "$INSTANCE_LINES"
fi
case "${#INSTANCE_IDS[@]}" in
  0) echo 'No existing MMIV-EC2 host; Terraform may create it.' ;;
  1)
    if [ -z "$MANAGED_ID" ]; then
      terraform import -input=false aws_instance.mmiv "${INSTANCE_IDS[0]}"
    elif [ "$MANAGED_ID" != "${INSTANCE_IDS[0]}" ]; then
      echo 'Terraform state points to another instance; reconcile state before deployment.' >&2
      exit 1
    fi
    ;;
  *) echo 'Multiple MMIV-EC2 instances found; refusing to select or create a host.' >&2; exit 1 ;;
esac
