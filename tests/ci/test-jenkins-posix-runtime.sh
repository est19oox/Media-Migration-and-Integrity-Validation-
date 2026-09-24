#!/bin/sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
UP_SCRIPT="$REPOSITORY_ROOT/scripts/jenkins-up.sh"
DOWN_SCRIPT="$REPOSITORY_ROOT/scripts/jenkins-down.sh"
SMOKE_SCRIPT="$REPOSITORY_ROOT/scripts/jenkins-smoke-test.sh"

for required_script in "$UP_SCRIPT" "$DOWN_SCRIPT" "$SMOKE_SCRIPT"; do
    if [ ! -f "$required_script" ]; then
        echo "FAIL: Missing POSIX Jenkins helper: ${required_script#$REPOSITORY_ROOT/}" >&2
        exit 1
    fi
done

FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mmiv-jenkins-posix.XXXXXX")
trap 'rm -rf "$FIXTURE_ROOT"' EXIT HUP INT TERM
FAKE_BIN="$FIXTURE_ROOT/bin"
DOCKER_LOG="$FIXTURE_ROOT/docker.log"
CURL_LOG="$FIXTURE_ROOT/curl.log"
CURL_CONFIG_LOG="$FIXTURE_ROOT/curl-config.log"
DATE_STATE="$FIXTURE_ROOT/date.state"
ENV_FILE="$FIXTURE_ROOT/jenkins.env"
mkdir -p "$FAKE_BIN"
REAL_DATE=$(command -v date)
REAL_SLEEP=$(command -v sleep)

cat > "$FAKE_BIN/docker" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$MMIV_TEST_DOCKER_LOG"
if [ "${1-}" = exec ] && [ "${2-}" = mmiv-jenkins ] && [ "${3-}" = sh ]; then
    printf '%s\n' "$MMIV_TEST_AUTHORIZATION"
fi
exit "${MMIV_TEST_DOCKER_EXIT:-0}"
EOF

cat > "$FAKE_BIN/curl" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$MMIV_TEST_CURL_LOG"
case " $* " in
    *' --config - '*) cat >> "$MMIV_TEST_CURL_CONFIG_LOG" ;;
esac
exit "${MMIV_TEST_CURL_EXIT:-0}"
EOF
cat > "$FAKE_BIN/date" <<'EOF'
#!/bin/sh
if [ "${MMIV_TEST_FAST_TIME:-0}" = 1 ]; then
    count=0
    [ ! -f "$MMIV_TEST_DATE_STATE" ] || count=$(cat "$MMIV_TEST_DATE_STATE")
    count=$((count + 1))
    printf '%s' "$count" > "$MMIV_TEST_DATE_STATE"
    if [ "$count" -eq 1 ]; then printf '0\n'; else printf '11\n'; fi
else
    "$MMIV_TEST_REAL_DATE" "$@"
fi
EOF
cat > "$FAKE_BIN/sleep" <<'EOF'
#!/bin/sh
[ "${MMIV_TEST_FAST_TIME:-0}" = 1 ] || "$MMIV_TEST_REAL_SLEEP" "$@"
EOF
chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/curl" "$FAKE_BIN/date" "$FAKE_BIN/sleep"

cat > "$ENV_FILE" <<'EOF'
JENKINS_ADMIN_ID=stale-admin
JENKINS_ADMIN_PASSWORD=stale-password
JENKINS_ADMIN_ID="local-admin"
JENKINS_ADMIN_PASSWORD='complex #$"quote'
GITHUB_USERNAME=example
GITHUB_TOKEN=read-only-token
EOF

export PATH="$FAKE_BIN:$PATH"
export MMIV_TEST_DOCKER_LOG="$DOCKER_LOG"
export MMIV_TEST_CURL_LOG="$CURL_LOG"
export MMIV_TEST_CURL_CONFIG_LOG="$CURL_CONFIG_LOG"
export MMIV_TEST_DATE_STATE="$DATE_STATE"
export MMIV_TEST_ADMIN_ID='local-admin'
export MMIV_TEST_ADMIN_PASSWORD='complex #$"quote'
export MMIV_TEST_AUTHORIZATION=$(printf '%s' "$MMIV_TEST_ADMIN_ID:$MMIV_TEST_ADMIN_PASSWORD" | base64 | tr -d '\r\n')
export MMIV_TEST_REAL_DATE="$REAL_DATE"
export MMIV_TEST_REAL_SLEEP="$REAL_SLEEP"
export MMIV_JENKINS_ENV_FILE="$ENV_FILE"

sh "$UP_SCRIPT" >/dev/null
grep -F -- "info" "$DOCKER_LOG" >/dev/null
grep -F -- "compose --env-file $ENV_FILE -f $REPOSITORY_ROOT/jenkins/docker-compose.yml up --detach --build" "$DOCKER_LOG" >/dev/null
grep -F -- "http://localhost:8080/login" "$CURL_LOG" >/dev/null
grep -F -- "http://localhost:8080/job/mmiv-ci/api/json" "$CURL_LOG" >/dev/null
expected_authorization=$(printf '%s' "$MMIV_TEST_ADMIN_ID:$MMIV_TEST_ADMIN_PASSWORD" | base64 | tr -d '\r\n')
grep -F -- "Authorization: Basic $expected_authorization" "$CURL_CONFIG_LOG" >/dev/null
if grep -F -- "$MMIV_TEST_ADMIN_PASSWORD" "$CURL_LOG" "$DOCKER_LOG" >/dev/null; then
    echo 'FAIL: Jenkins administrator password was exposed in process arguments.' >&2
    exit 1
fi

: > "$DOCKER_LOG"
sh "$DOWN_SCRIPT" >/dev/null
grep -F -- "compose --env-file $ENV_FILE -f $REPOSITORY_ROOT/jenkins/docker-compose.yml down" "$DOCKER_LOG" >/dev/null
if grep -F -- "--volumes" "$DOCKER_LOG" >/dev/null; then
    echo 'FAIL: Default shutdown unexpectedly purged the Jenkins volume.' >&2
    exit 1
fi

: > "$DOCKER_LOG"
sh "$DOWN_SCRIPT" --purge >/dev/null 2>&1
grep -F -- "compose --env-file $ENV_FILE -f $REPOSITORY_ROOT/jenkins/docker-compose.yml down --volumes" "$DOCKER_LOG" >/dev/null

if sh "$DOWN_SCRIPT" --unknown >/dev/null 2>&1; then
    echo 'FAIL: Unknown shutdown option was accepted.' >&2
    exit 1
fi

cat > "$ENV_FILE" <<'EOF'
JENKINS_ADMIN_ID=admin
JENKINS_ADMIN_PASSWORD=replace-with-a-strong-local-password
EOF
if sh "$UP_SCRIPT" >/dev/null 2>&1; then
    echo 'FAIL: Placeholder Jenkins password was accepted.' >&2
    exit 1
fi

if sh "$SMOKE_SCRIPT" 9 >/dev/null 2>&1; then
    echo 'FAIL: Smoke-test timeout below 10 seconds was accepted.' >&2
    exit 1
fi

cat > "$ENV_FILE" <<'EOF'
JENKINS_ADMIN_ID=ignored-by-runtime-inspection
JENKINS_ADMIN_PASSWORD=ignored-by-runtime-inspection
EOF
export MMIV_TEST_CURL_EXIT=1
export MMIV_TEST_FAST_TIME=1
rm -f "$DATE_STATE"
if sh "$SMOKE_SCRIPT" 10 >/dev/null 2>&1; then
    echo 'FAIL: Smoke test passed while both Jenkins endpoints were unreachable.' >&2
    exit 1
fi

echo 'PASS: POSIX Jenkins lifecycle helpers preserve the local runtime contract.'
