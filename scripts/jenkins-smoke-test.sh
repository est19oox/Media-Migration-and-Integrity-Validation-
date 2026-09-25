#!/bin/sh
set -eu

SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIRECTORY/.." && pwd)
ENVIRONMENT_FILE=${MMIV_JENKINS_ENV_FILE:-"$REPOSITORY_ROOT/jenkins/.env"}
TIMEOUT_SECONDS=${1:-180}

case $TIMEOUT_SECONDS in
    ''|*[!0-9]*)
        echo 'ERROR: Timeout must be an integer from 10 to 600 seconds.' >&2
        exit 1
        ;;
esac
if [ "$TIMEOUT_SECONDS" -lt 10 ] || [ "$TIMEOUT_SECONDS" -gt 600 ]; then
    echo 'ERROR: Timeout must be an integer from 10 to 600 seconds.' >&2
    exit 1
fi

if [ ! -f "$ENVIRONMENT_FILE" ]; then
    echo 'ERROR: Create jenkins/.env before running the Jenkins smoke test.' >&2
    exit 1
fi

PORT_MAPPING=$(docker port mmiv-jenkins 8080/tcp 2>/dev/null | sed -n '1p' || true)
JENKINS_PORT=${PORT_MAPPING##*:}
case $JENKINS_PORT in
    ''|*[!0-9]*)
        echo 'ERROR: Cannot determine the published Jenkins port from the mmiv-jenkins container.' >&2
        exit 1
        ;;
esac
if [ "$JENKINS_PORT" -lt 1 ] || [ "$JENKINS_PORT" -gt 65535 ]; then
    echo 'ERROR: The published Jenkins port is outside the valid TCP port range.' >&2
    exit 1
fi
HEALTH_URL="http://localhost:$JENKINS_PORT/login"
JOB_URL="http://localhost:$JENKINS_PORT/job/mmiv-ci/api/json"

AUTHORIZATION=$(docker exec mmiv-jenkins sh -c '
    [ -n "$JENKINS_ADMIN_ID" ] && [ -n "$JENKINS_ADMIN_PASSWORD" ] || exit 1
    printf "%s" "$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD" | base64 | tr -d "\r\n"
' 2>/dev/null || true)
if [ -z "$AUTHORIZATION" ]; then
    echo 'ERROR: The running mmiv-jenkins container does not expose its configured administrator credentials.' >&2
    exit 1
fi

START_TIME=$(date +%s)
while :; do
    if curl --fail --silent --show-error --max-time 5 "$HEALTH_URL" >/dev/null 2>&1 \
        && printf 'header = "Authorization: Basic %s"\n' "$AUTHORIZATION" \
            | curl --fail --silent --show-error --max-time 5 --config - "$JOB_URL" >/dev/null 2>&1; then
        echo 'PASS: Jenkins health endpoint and mmiv-ci job are ready.'
        exit 0
    fi

    CURRENT_TIME=$(date +%s)
    if [ $((CURRENT_TIME - START_TIME)) -ge "$TIMEOUT_SECONDS" ]; then
        echo "ERROR: Jenkins health endpoint or mmiv-ci job did not become ready within $TIMEOUT_SECONDS seconds." >&2
        exit 1
    fi
    sleep 2
done
