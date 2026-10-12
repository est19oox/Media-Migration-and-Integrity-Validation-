#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SERVICE_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SERVICE_DIR/../.." && pwd)
COMPOSE_FILE="$SERVICE_DIR/compose.yml"
MAVEN_WRAPPER="$REPO_ROOT/services/user-access-service/mvnw"
EXAMPLES_DIR="$REPO_ROOT/contracts/events/v2/examples"

REPORTING_PORT=${REPORTING_PORT:-8084}
RABBITMQ_MANAGEMENT_PORT=${RABBITMQ_MANAGEMENT_PORT:-15672}
RABBITMQ_DEFAULT_USER=${RABBITMQ_DEFAULT_USER:-mmiv}
RABBITMQ_DEFAULT_PASS=${RABBITMQ_DEFAULT_PASS:-mmiv-local}
MIGRATION_ID=c49f2f8f-ad97-45a8-8867-f3ca8a971d80

wait_for_health() {
  attempt=1
  while [ "$attempt" -le 90 ]; do
    if curl --fail --silent \
      "http://127.0.0.1:${REPORTING_PORT}/actuator/health" \
      | grep -q '"status":"UP"'; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  echo "Reporting Service did not become healthy." >&2
  return 1
}

publish_event() {
  fixture=$1
  routing_key=$2
  request_file=$(mktemp)

  python3 - "$EXAMPLES_DIR/$fixture" "$routing_key" "$request_file" <<'PY'
import json
import pathlib
import sys

fixture = pathlib.Path(sys.argv[1])
routing_key = sys.argv[2]
request_file = pathlib.Path(sys.argv[3])
payload = fixture.read_text(encoding="utf-8")
event = json.loads(payload)
request_file.write_text(
    json.dumps({
        "properties": {
            "content_type": "application/json",
            "delivery_mode": 2,
            "message_id": event["eventId"],
        },
        "routing_key": routing_key,
        "payload": payload,
        "payload_encoding": "string",
    }),
    encoding="utf-8",
)
PY

  response=$(curl --fail --silent --show-error \
    --user "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}" \
    --header 'content-type: application/json' \
    --data-binary "@$request_file" \
    "http://127.0.0.1:${RABBITMQ_MANAGEMENT_PORT}/api/exchanges/%2F/mmiv.events/publish")
  rm -f "$request_file"

  python3 - "$response" <<'PY'
import json
import sys

if not json.loads(sys.argv[1]).get("routed"):
    raise SystemExit("RabbitMQ did not route the event")
PY
}

wait_for_completed_report() {
  attempt=1
  while [ "$attempt" -le 60 ]; do
    report=$(curl --fail --silent \
      "http://127.0.0.1:${REPORTING_PORT}/api/reports/${MIGRATION_ID}" \
      2>/dev/null || true)
    if [ -n "$report" ] && python3 - "$report" <<'PY'
import json
import sys

report = json.loads(sys.argv[1])
raise SystemExit(0 if report.get("status") == "COMPLETED" else 1)
PY
    then
      printf '%s' "$report"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "Migration report did not complete." >&2
  return 1
}

assert_completed_report() {
  python3 - "$1" <<'PY'
import json
import sys

report = json.loads(sys.argv[1])
summary = report.get("summary", {})
expected = (
    report.get("status") == "COMPLETED"
    and summary.get("totalFiles") == 1
    and summary.get("successfulFiles") == 1
    and float(summary.get("successRate", -1)) == 100.0
)
if not expected:
    raise SystemExit(f"Unexpected report result: {report}")
PY
}

cd "$REPO_ROOT"
"$MAVEN_WRAPPER" -B -f services/reporting-service/pom.xml clean package
docker compose -f "$COMPOSE_FILE" up --build --detach

echo "Waiting for Reporting Service health..."
if ! wait_for_health; then
  docker compose -f "$COMPOSE_FILE" logs --no-color reporting-service rabbitmq mongodb
  exit 1
fi
echo "Reporting Service is healthy."

echo "Publishing successful-path events..."
publish_event 01-migration-started.json migration.started
publish_event 02-file-discovery-accepted.json discovery.file.completed
publish_event 04-migration-discovery-completed.json discovery.migration.completed
publish_event 05-file-transfer-completed.json transfer.file.completed
publish_event 06-integrity-validation-completed.json integrity.validation.completed

echo "Waiting for the completed migration report..."
first_report=$(wait_for_completed_report)
assert_completed_report "$first_report"

echo "Replaying the Integrity event..."
publish_event 06-integrity-validation-completed.json integrity.validation.completed
sleep 2
duplicate_report=$(curl --fail --silent \
  "http://127.0.0.1:${REPORTING_PORT}/api/reports/${MIGRATION_ID}")
assert_completed_report "$duplicate_report"

python3 - "$first_report" "$duplicate_report" <<'PY'
import json
import sys

before = json.loads(sys.argv[1])["summary"]
after = json.loads(sys.argv[2])["summary"]
for field in ("totalFiles", "successfulFiles"):
    if before[field] != after[field]:
        raise SystemExit("Duplicate delivery changed report totals")
PY

echo "PASS: Reporting smoke test completed and duplicate delivery was idempotent."
echo "Stack is still running. Stop it with:"
echo "docker compose -f services/reporting-service/compose.yml down"
echo "Use 'down --volumes' only when you intentionally want to delete local data."
