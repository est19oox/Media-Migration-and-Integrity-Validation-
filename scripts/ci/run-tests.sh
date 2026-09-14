#!/usr/bin/env sh

set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")
# Emits SKIPPED: when no supported application manifest exists.

if [ -f "$ROOT/pyproject.toml" ] || [ -f "$ROOT/requirements.txt" ]; then
    require_command python3
    (cd "$ROOT" && python3 -m pytest tests)
elif [ -f "$ROOT/package.json" ]; then
    require_command npm
    (cd "$ROOT" && npm test)
elif [ -f "$ROOT/pom.xml" ]; then
    require_command mvn
    JAVA_HOME=/opt/jdk-25
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
    (cd "$ROOT" && mvn --batch-mode test)
else
    skip 'No supported application manifest; unit tests are not active yet.'
    exit 0
fi

pass 'Application unit tests passed.'
