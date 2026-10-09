#!/usr/bin/env bash
# Query the private registry on EC2 through Run Command, without opening a port.
set -euo pipefail
export AWS_PAGER=""
INSTANCE_ID="${INSTANCE_ID:?INSTANCE_ID is required}"
SERVICE_NAME="${SERVICE_NAME:?SERVICE_NAME is required}"
REGION="${AWS_REGION:-ap-southeast-1}"
[[ "$SERVICE_NAME" =~ ^[a-z][a-z0-9-]*$ ]] || {
  echo 'Invalid service name' >&2; exit 1;
}
PARAMETERS=$(python3 - "$SERVICE_NAME" <<'PY'
import json, shlex, sys
code = '''import json, sys, time
from urllib.request import Request, urlopen
name = sys.argv[1].upper()
for attempt in range(30):
    try:
        request = Request("http://127.0.0.1:8761/eureka/apps/" + name,
                          headers={"Accept": "application/json"})
        with urlopen(request, timeout=3) as response:
            application = json.load(response)["application"]
        instances = application.get("instance", [])
        if isinstance(instances, dict):
            instances = [instances]
        if any(instance.get("status") == "UP" for instance in instances):
            print(name + " is registered UP")
            sys.exit(0)
    except (OSError, ValueError, KeyError, TypeError):
        pass
    if attempt < 29:
        time.sleep(5)
print(name + " did not register UP before the deadline", file=sys.stderr)
sys.exit(1)
'''
command = "python3 -c " + shlex.quote(code) + " " + shlex.quote(sys.argv[1])
print(json.dumps({"commands": [command], "executionTimeout": ["300"]}))
PY
)
COMMAND_ID=$(aws ssm send-command --region "$REGION" \
  --instance-ids "$INSTANCE_ID" --document-name AWS-RunShellScript \
  --parameters "$PARAMETERS" --query 'Command.CommandId' --output text)
WAIT_STATUS=0
aws ssm wait command-executed --region "$REGION" \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" || WAIT_STATUS=$?
RESULT=$(aws ssm get-command-invocation --region "$REGION" \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --output json)
printf '%s' "$RESULT" | python3 -c '
import json, sys
result = json.load(sys.stdin)
print(result.get("StandardOutputContent", ""))
print(result.get("StandardErrorContent", ""), file=sys.stderr)
sys.exit(0 if result.get("Status") == "Success" and result.get("ResponseCode") == 0 else 1)
'
exit "$WAIT_STATUS"
