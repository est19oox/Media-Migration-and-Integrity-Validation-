#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE=${MMIV_COMPOSE_FILE:-$REPO_ROOT/infrastructure/service-registry/compose.yml}
GATEWAY_BASE=${MMIV_GATEWAY_BASE:-http://127.0.0.1:8080}
RABBITMQ_BASE=${MMIV_RABBITMQ_BASE:-http://127.0.0.1:15672}
RABBITMQ_USER=${RABBITMQ_DEFAULT_USER:-mmiv}
EXAMPLES_DIR=$REPO_ROOT/contracts/events/v2/examples

: "${RABBITMQ_PASSWORD:?Set RABBITMQ_PASSWORD to the value used by the integrated stack}"
: "${MONGODB_PASSWORD:?Set MONGODB_PASSWORD to the value used by the integrated stack}"

for command in curl docker python3; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $command" >&2
    exit 1
  }
done

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/mmiv-reporting-outage.XXXXXX")
chmod 700 "$TEMP_DIR"
AUTH_ENV=$TEMP_DIR/auth.env
ACCOUNT_ENV=$TEMP_DIR/account.env
ACCOUNT_FILE=$TEMP_DIR/account.json
COOKIE_JAR=$TEMP_DIR/cookies.txt
MIGRATION_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
FILE_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
TRANSFER_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
TRANSFER_STOPPED=0
FIXTURE_PUBLISHED=0

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

wait_transfer_health() {
  attempt=1
  while [ "$attempt" -le 90 ]; do
    if curl --fail --silent --output /dev/null http://127.0.0.1:8081/actuator/health; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  return 1
}

cleanup_fixture() {
  [ "$FIXTURE_PUBLISHED" -eq 1 ] || return 0
  compose exec -T mongodb sh -eu -c '
    mongosh --quiet --username mmiv --password "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin mmiv_reporting --eval "$1"
  ' sh "db.audit_events.deleteMany({migrationId:{\$in:[UUID('$MIGRATION_ID'),'$MIGRATION_ID']}}); db.migration_reports.deleteMany({_id:{\$in:[UUID('$MIGRATION_ID'),'$MIGRATION_ID']}}); db.verification_cleanup.deleteMany({_id:'$MIGRATION_ID'});" >/dev/null
}

cleanup() {
  status=$?
  trap - EXIT
  set +e
  if [ "$TRANSFER_STOPPED" -eq 1 ]; then
    compose start transfer-service >/dev/null
    wait_transfer_health >/dev/null
    TRANSFER_STOPPED=0
  fi
  cleanup_fixture
  if [ -f "$ACCOUNT_FILE" ]; then
    MMIV_VERIFICATION_ACCOUNT_FILE=$ACCOUNT_FILE \
      python3 "$REPO_ROOT/scripts/cd/login-for-verification.py" \
      "$GATEWAY_BASE" "$AUTH_ENV" --account-env "$ACCOUNT_ENV" --cleanup >/dev/null
  fi
  rm -rf -- "$TEMP_DIR"
  exit "$status"
}
trap cleanup EXIT

http_status() {
  method=$1
  path=$2
  output=$3
  shift 3
  curl --silent --show-error --request "$method" --output "$output" \
    --write-out '%{http_code}' --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
    "$@" "$GATEWAY_BASE$path"
}

expect_status() {
  expected=$1
  method=$2
  path=$3
  output=$TEMP_DIR/response.json
  status=$(http_status "$method" "$path" "$output")
  if [ "$status" != "$expected" ]; then
    echo "Expected HTTP $expected from $path, received $status." >&2
    return 1
  fi
}

echo "Creating a private verification account through API Gateway..."
MMIV_VERIFICATION_ACCOUNT_FILE=$ACCOUNT_FILE \
  python3 "$REPO_ROOT/scripts/cd/login-for-verification.py" \
  "$GATEWAY_BASE" "$AUTH_ENV" --account-env "$ACCOUNT_ENV"
. "$AUTH_ENV"
. "$ACCOUNT_ENV"
VERIFICATION_USER_ID=$USER_ACCESS_TEST_USER_ID

session_status=$(curl --silent --show-error --request POST --output "$TEMP_DIR/session-create.json" \
  --write-out '%{http_code}' --cookie-jar "$COOKIE_JAR" \
  --header "Authorization: Bearer $API_TEST_TOKEN" "$GATEWAY_BASE/api/v1/session")
if [ "$session_status" != 204 ]; then
  echo "Gateway did not establish the browser session (HTTP $session_status)." >&2
  exit 1
fi
unset API_TEST_TOKEN ZAP_AUTH_HEADER_VALUE ZAP_AUTH_HEADER_SITE
chmod 600 "$COOKIE_JAR"
expect_status 200 GET /api/v1/session

python3 - "$EXAMPLES_DIR" "$TEMP_DIR" "$VERIFICATION_USER_ID" \
  "$MIGRATION_ID" "$FILE_ID" "$TRANSFER_ID" <<'PY'
import json
from pathlib import Path
import sys
import uuid

examples, output = Path(sys.argv[1]), Path(sys.argv[2])
owner, migration_id, file_id, transfer_id = sys.argv[3:]
old = {
    'user-123': owner,
    'c49f2f8f-ad97-45a8-8867-f3ca8a971d80': migration_id,
    '9eceb6d4-8a39-48da-8abe-1238ba8c7a67': file_id,
    '344cd216-dac8-4bd2-a350-1dc891c9f4cb': transfer_id,
}
fixtures = [
    ('01-migration-started.json', 'migration.started'),
    ('02-file-discovery-accepted.json', 'discovery.file.completed'),
    ('04-migration-discovery-completed.json', 'discovery.migration.completed'),
    ('05-file-transfer-completed.json', 'transfer.file.completed'),
    ('06-integrity-validation-completed.json', 'integrity.validation.completed'),
]

def replace(value):
    if isinstance(value, dict):
        return {key: replace(item) for key, item in value.items()}
    if isinstance(value, list):
        return [replace(item) for item in value]
    return old.get(value, value)

manifest = []
for index, (name, routing_key) in enumerate(fixtures):
    event = replace(json.loads((examples / name).read_text(encoding='utf-8')))
    event['eventId'] = str(uuid.uuid4())
    payload = json.dumps(event, separators=(',', ':'))
    request = {
        'properties': {
            'content_type': 'application/json',
            'delivery_mode': 2,
            'message_id': event['eventId'],
        },
        'routing_key': routing_key,
        'payload': payload,
        'payload_encoding': 'string',
    }
    path = output / f'publish-{index}.json'
    path.write_text(json.dumps(request), encoding='utf-8')
    manifest.append(str(path))
(output / 'publish-manifest.txt').write_text('\n'.join(manifest) + '\n', encoding='utf-8')
PY

echo "Publishing an owner-scoped report fixture..."
FIXTURE_PUBLISHED=1
while IFS= read -r request_file; do
  curl --fail --silent --show-error \
    --user "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
    --header 'content-type: application/json' \
    --data-binary "@$request_file" \
    "$RABBITMQ_BASE/api/exchanges/%2F/mmiv.events/publish" \
    --output "$TEMP_DIR/publish-response.json"
  python3 - "$TEMP_DIR/publish-response.json" <<'PY'
import json
from pathlib import Path
import sys
if not json.loads(Path(sys.argv[1]).read_text(encoding='utf-8')).get('routed'):
    raise SystemExit('RabbitMQ did not route the verification event')
PY
done < "$TEMP_DIR/publish-manifest.txt"
unset USER_ACCESS_TEST_USERNAME USER_ACCESS_TEST_PASSWORD USER_ACCESS_TEST_USER_ID

attempt=1
while [ "$attempt" -le 60 ]; do
  status=$(http_status GET "/api/reports/$MIGRATION_ID" "$TEMP_DIR/report.json")
  if [ "$status" = 200 ] && python3 - "$TEMP_DIR/report.json" "$VERIFICATION_USER_ID" <<'PY'
import json
from pathlib import Path
import sys
report = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
raise SystemExit(0 if report.get('status') == 'COMPLETED' and report.get('initiatedBy') == sys.argv[2] else 1)
PY
  then
    break
  fi
  attempt=$((attempt + 1))
  sleep 1
done
[ "$attempt" -le 60 ] || { echo "Owner-scoped report fixture did not complete." >&2; exit 1; }

echo "Stopping only Transfer Service..."
TRANSFER_STOPPED=1
compose stop transfer-service

expect_status 200 GET /pages/reports.html
expect_status 200 GET /api/v1/session
expect_status 200 GET '/api/reports/mine?page=0&size=20'
expect_status 200 GET "/api/reports/$MIGRATION_ID"
expect_status 200 GET "/api/reports/$MIGRATION_ID/files?page=0&size=20&failedOnly=true"
python3 - "$TEMP_DIR/response.json" <<'PY'
import json
from pathlib import Path
import sys
page = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
if page.get('totalElements') != 0 or page.get('content') != []:
    raise SystemExit('Successful fixture unexpectedly appeared in failed-only file results')
PY
expect_status 200 GET "/api/reports/$MIGRATION_ID/files?page=0&size=20&failedOnly=false"
python3 - "$TEMP_DIR/response.json" <<'PY'
import json
from pathlib import Path
import sys
page = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
if page.get('number') != 0 or page.get('totalElements') != 1 or len(page.get('content', [])) != 1:
    raise SystemExit('All-file page did not preserve pagination totals')
PY
expect_status 200 GET "/api/reports/$MIGRATION_ID/timeline?page=0&size=20"

echo "Waiting for the Gateway to report Transfer as unavailable..."
attempt=1
while [ "$attempt" -le 60 ]; do
  status=$(http_status GET "/api/v1/files/$FILE_ID/content" "$TEMP_DIR/transfer-down.json")
  [ "$status" = 503 ] && break
  attempt=$((attempt + 1))
  sleep 2
done
[ "$status" = 503 ] || { echo "Transfer route did not return clean HTTP 503 while stopped (last HTTP $status)." >&2; exit 1; }

echo "Restarting Transfer Service and verifying recovery..."
compose start transfer-service
wait_transfer_health || { echo "Transfer Service did not recover." >&2; exit 1; }
TRANSFER_STOPPED=0
attempt=1
while [ "$attempt" -le 60 ]; do
  status=$(http_status GET "/api/v1/files/$FILE_ID/content" "$TEMP_DIR/transfer-recovered.json")
  case "$status" in
    502|503|504) ;;
    *) break ;;
  esac
  attempt=$((attempt + 1))
  sleep 2
done
case "$status" in
  502|503|504) echo "Transfer route did not recover (last HTTP $status)." >&2; exit 1 ;;
esac

echo "PASS: Reporting UI and owner APIs remained available while Transfer was stopped, and Transfer recovered."
