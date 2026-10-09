#!/usr/bin/env sh

set -eu
# Reject unresolved Git merge/stash conflicts before any build or deployment.
python3 - "${1:-.}" <<'PY_CONFLICTS'
from pathlib import Path
import re, sys
root = Path(sys.argv[1])
errors = []
for path in root.rglob('*'):
    if not path.is_file() or any(part in {'.git', 'target', '.idea', '__pycache__', '.terraform'} for part in path.relative_to(root).parts):
        continue
    try:
        lines = path.read_text(encoding='utf-8').splitlines()
    except (UnicodeDecodeError, OSError):
        continue
    for number, line in enumerate(lines, 1):
        if re.match(r'^(<{7}|>{7}|\|{7})(?:\s|$)', line):
            errors.append(f'{path}:{number}: unresolved conflict marker')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
PY_CONFLICTS
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/common.sh"

ROOT=$(find_repository_root "${1:-.}")

for required_path in README.md services tests scripts infrastructure; do
    if [ ! -e "$ROOT/$required_path" ]; then
        fail "Required repository path is missing: $required_path"
        exit 1
    fi
done

if command -v git >/dev/null 2>&1 && git -c safe.directory="$ROOT" -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    tracked_files=$(git -c safe.directory="$ROOT" -C "$ROOT" ls-files)
else
    tracked_files=$(find "$ROOT" -type f -print)
fi

unsafe_files=$(printf '%s\n' "$tracked_files" | awk '
    /(^|\/)\.env$/ || /\.pem$/ || /\.tfstate(\.|$)/ { print }
')

if [ -n "$unsafe_files" ]; then
    printf '%s\n' "$unsafe_files" >&2
    fail 'Potential secret or Terraform state file is tracked.'
    exit 1
fi

pass 'Repository structure and tracked-file policy are valid.'
