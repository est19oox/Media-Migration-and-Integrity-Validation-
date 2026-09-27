#!/usr/bin/env sh

set -eu

# Python's JSON encoder treats all CI values as data. Read only these six
# metadata inputs; never enumerate or serialize the process environment.
if command -v python3 >/dev/null 2>&1; then
    json_python=python3
elif command -v python >/dev/null 2>&1; then
    json_python=python
else
    printf 'FAIL: Python is required to encode build metadata.\n' >&2
    exit 1
fi

mkdir -p .ci/reports
metadata_temp=$(mktemp .ci/reports/.build-metadata.XXXXXX)
trap 'rm -f "$metadata_temp"' EXIT HUP INT TERM

"$json_python" - > "$metadata_temp" <<'PY'
import datetime
import json
import os
import sys

metadata = {
    "branch": os.environ.get("BRANCH_NAME", ""),
    "tested_commit": os.environ.get("TESTED_COMMIT", ""),
    "build_number": os.environ.get("BUILD_NUMBER", ""),
    "build_url": os.environ.get("BUILD_URL", ""),
    "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "result": os.environ.get("MMIV_BUILD_RESULT", "UNKNOWN"),
}
json.dump(metadata, sys.stdout, ensure_ascii=True, indent=2)
sys.stdout.write("\n")
PY

# Same-directory rename is atomic; failed encoding leaves previous evidence intact.
mv -f "$metadata_temp" .ci/reports/build-metadata.json
trap - EXIT HUP INT TERM
