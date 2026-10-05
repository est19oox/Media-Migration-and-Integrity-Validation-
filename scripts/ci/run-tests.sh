#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")

require_command mvn

if [ -d /opt/jdk-25 ]; then
    JAVA_HOME=/opt/jdk-25
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi

found_service=false

for pom in "$ROOT"/services/*/pom.xml; do
    [ -f "$pom" ] || continue

    found_service=true
    service_dir=$(dirname "$pom")

    printf 'Testing Maven service: %s\n' "$service_dir"

    (
        cd "$service_dir"
        mvn --batch-mode clean verify
    )
done

if [ "$found_service" = false ]; then
    skip 'No Maven microservices found.'
    exit 0
fi

pass 'Application unit tests passed.'