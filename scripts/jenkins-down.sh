#!/bin/sh
set -eu

SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIRECTORY/.." && pwd)
COMPOSE_FILE="$REPOSITORY_ROOT/jenkins/docker-compose.yml"
ENVIRONMENT_FILE=${MMIV_JENKINS_ENV_FILE:-"$REPOSITORY_ROOT/jenkins/.env"}
PURGE=false

case ${1-} in
    '') ;;
    --purge) PURGE=true ;;
    *)
        echo 'ERROR: Usage: jenkins-down.sh [--purge]' >&2
        exit 1
        ;;
esac

if [ "$#" -gt 1 ]; then
    echo 'ERROR: Usage: jenkins-down.sh [--purge]' >&2
    exit 1
fi

if [ ! -f "$ENVIRONMENT_FILE" ]; then
    echo 'ERROR: jenkins/.env is required so Docker Compose can resolve the local configuration.' >&2
    exit 1
fi

if [ "$PURGE" = true ]; then
    echo 'WARNING: Purging the mmiv_jenkins_home volume and all local Jenkins configuration.' >&2
    docker compose --env-file "$ENVIRONMENT_FILE" -f "$COMPOSE_FILE" down --volumes
else
    docker compose --env-file "$ENVIRONMENT_FILE" -f "$COMPOSE_FILE" down
    echo 'Jenkins stopped. The mmiv_jenkins_home volume was preserved.'
fi
