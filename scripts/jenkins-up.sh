#!/bin/sh
set -eu

SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIRECTORY/.." && pwd)
COMPOSE_FILE="$REPOSITORY_ROOT/jenkins/docker-compose.yml"
ENVIRONMENT_FILE=${MMIV_JENKINS_ENV_FILE:-"$REPOSITORY_ROOT/jenkins/.env"}
export MMIV_JENKINS_ENV_FILE="$ENVIRONMENT_FILE"

if ! docker info >/dev/null 2>&1; then
    echo 'ERROR: Docker Desktop or the Docker Engine is unavailable.' >&2
    exit 1
fi

if [ ! -f "$ENVIRONMENT_FILE" ]; then
    echo 'ERROR: Create jenkins/.env from jenkins/.env.example and set a strong local password.' >&2
    exit 1
fi

if grep -F 'replace-with-a-strong-local-password' "$ENVIRONMENT_FILE" >/dev/null 2>&1; then
    echo 'ERROR: Replace the example JENKINS_ADMIN_PASSWORD before starting Jenkins.' >&2
    exit 1
fi

if ! docker compose --env-file "$ENVIRONMENT_FILE" -f "$COMPOSE_FILE" up --detach --build; then
    echo 'ERROR: Docker Compose could not build or start Jenkins.' >&2
    exit 1
fi

MMIV_JENKINS_ENV_FILE="$ENVIRONMENT_FILE" sh "$SCRIPT_DIRECTORY/jenkins-smoke-test.sh"
PORT_MAPPING=$(docker port mmiv-jenkins 8080/tcp 2>/dev/null | sed -n '1p' || true)
JENKINS_PORT=${PORT_MAPPING##*:}
case $JENKINS_PORT in
    ''|*[!0-9]*)
        echo 'ERROR: Cannot determine the published Jenkins port from the mmiv-jenkins container.' >&2
        exit 1
        ;;
esac
echo "Jenkins is available at http://localhost:$JENKINS_PORT"
