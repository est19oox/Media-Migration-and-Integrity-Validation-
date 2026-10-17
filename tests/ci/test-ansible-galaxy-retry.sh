#!/usr/bin/env sh

set -eu

ROOT=${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}
ROOT=$(CDPATH= cd -- "$ROOT" && pwd)
FIXTURE_ROOT=$(mktemp -d)
trap 'rm -rf "$FIXTURE_ROOT"' EXIT

mkdir -p "$FIXTURE_ROOT/bin"
printf '%s\n' '---' 'collections: []' > "$FIXTURE_ROOT/requirements.yml"

cat > "$FIXTURE_ROOT/bin/ansible-galaxy" <<'EOF'
#!/usr/bin/env sh
set -eu

attempts_file=${MMIV_ANSIBLE_GALAXY_ATTEMPTS_FILE:?}
attempts=0
if [ -f "$attempts_file" ]; then
    attempts=$(cat "$attempts_file")
fi
attempts=$((attempts + 1))
printf '%s\n' "$attempts" > "$attempts_file"

case "${MMIV_ANSIBLE_GALAXY_MODE:-eventual-success}" in
    eventual-success)
        [ "$attempts" -ge 3 ]
        ;;
    always-fail)
        exit 42
        ;;
    *)
        exit 64
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/ansible-galaxy"

ATTEMPTS_FILE="$FIXTURE_ROOT/attempts"
MMIV_ANSIBLE_GALAXY_ATTEMPTS_FILE="$ATTEMPTS_FILE" \
    MMIV_ANSIBLE_GALAXY_RETRY_DELAY_SECONDS=0 \
    PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/install-ansible-collections.sh" "$FIXTURE_ROOT/requirements.yml"
test "$(cat "$ATTEMPTS_FILE")" = 3

rm -f "$ATTEMPTS_FILE"
if MMIV_ANSIBLE_GALAXY_ATTEMPTS_FILE="$ATTEMPTS_FILE" \
    MMIV_ANSIBLE_GALAXY_MODE=always-fail \
    MMIV_ANSIBLE_GALAXY_RETRY_DELAY_SECONDS=0 \
    PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/install-ansible-collections.sh" "$FIXTURE_ROOT/requirements.yml"; then
    printf 'FAIL: persistent Ansible Galaxy failure was ignored.\n' >&2
    exit 1
else
    status=$?
fi
test "$status" -eq 42
test "$(cat "$ATTEMPTS_FILE")" = 3

printf 'PASS: Ansible Galaxy collection installation retries transient failures and fails closed.\n'
