#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

command -v docker >/dev/null 2>&1 || { echo 'Docker is not installed.'; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker is not running.'; exit 1; }
docker compose version >/dev/null

if [ -z "${ORACLE_PWD:-}" ]; then
  read -r -s -p 'Enter Oracle development password: ' ORACLE_PWD
  echo
fi
[ -n "$ORACLE_PWD" ] || { echo 'Password cannot be empty.'; exit 1; }
export ORACLE_PWD

docker compose -f "$COMPOSE_FILE" config --quiet
docker compose -f "$COMPOSE_FILE" up -d
docker compose -f "$COMPOSE_FILE" ps

echo 'Oracle container started. Database initialization may take several minutes.'
echo 'Connection: localhost:1521/FREEPDB1'
echo 'Check readiness: docker inspect --format="{{.State.Health.Status}}" media-migration-oracle'
echo 'Stop: docker stop media-migration-oracle'
echo 'Start again: docker start media-migration-oracle'
