#!/usr/bin/env sh

set -eu

ROOT=${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}
FIXTURE_ROOT=$(mktemp -d)
trap 'rm -rf "$FIXTURE_ROOT"' EXIT

assert_fails() {
    description=$1
    shift
    if "$@"; then
        printf 'FAIL: expected %s to fail.\n' "$description" >&2
        exit 1
    fi
}

assert_status() {
    expected_status=$1
    description=$2
    shift 2
    if "$@"; then
        actual_status=0
    else
        actual_status=$?
    fi
    if [ "$actual_status" -ne "$expected_status" ]; then
        printf 'FAIL: expected %s to exit %s, got %s.\n' \
            "$description" "$expected_status" "$actual_status" >&2
        exit 1
    fi
}

assert_succeeds() {
    description=$1
    shift
    if ! "$@"; then
        printf 'FAIL: expected %s to succeed.\n' "$description" >&2
        exit 1
    fi
}

for script in "$ROOT"/scripts/ci/*.sh; do
    sh -n "$script"
done

"$ROOT/scripts/ci/validate-repository.sh" "$ROOT"

DEPLOYMENT_COMMIT=0123456789abcdef0123456789abcdef01234567
DEPLOYMENT_URL=https://dast.example.test/health
DEPLOYMENT_VALID_ENV="$FIXTURE_ROOT/deployment-valid.env"
printf 'DEPLOYED_COMMIT=%s\nDAST_TARGET_URL=%s\n' \
    "$DEPLOYMENT_COMMIT" "$DEPLOYMENT_URL" > "$DEPLOYMENT_VALID_ENV"

"$ROOT/scripts/ci/verify-deployment.sh" "$DEPLOYMENT_VALID_ENV" "$DEPLOYMENT_COMMIT" \
    > "$FIXTURE_ROOT/deployment-url.out" \
    2> "$FIXTURE_ROOT/deployment-url.err"
printf '%s\n' "$DEPLOYMENT_URL" > "$FIXTURE_ROOT/deployment-url.expected"
cmp "$FIXTURE_ROOT/deployment-url.expected" "$FIXTURE_ROOT/deployment-url.out"
test ! -s "$FIXTURE_ROOT/deployment-url.err"

assert_fails "missing deployment environment file" \
    "$ROOT/scripts/ci/verify-deployment.sh" "$FIXTURE_ROOT/missing-deployment.env" "$DEPLOYMENT_COMMIT"

printf 'DEPLOYED_COMMIT=%s\n' "$DEPLOYMENT_COMMIT" > "$FIXTURE_ROOT/deployment-missing-key.env"
assert_fails "deployment environment missing DAST target URL" \
    "$ROOT/scripts/ci/verify-deployment.sh" "$FIXTURE_ROOT/deployment-missing-key.env" "$DEPLOYMENT_COMMIT"

printf 'DEPLOYED_COMMIT=%s\nDEPLOYED_COMMIT=%s\nDAST_TARGET_URL=%s\n' \
    "$DEPLOYMENT_COMMIT" "$DEPLOYMENT_COMMIT" "$DEPLOYMENT_URL" > "$FIXTURE_ROOT/deployment-duplicate-key.env"
assert_fails "deployment environment with duplicate deployed commit" \
    "$ROOT/scripts/ci/verify-deployment.sh" "$FIXTURE_ROOT/deployment-duplicate-key.env" "$DEPLOYMENT_COMMIT"

printf 'DEPLOYED_COMMIT=%s\nDAST_TARGET_URL=ftp://dast.example.test/health\n' \
    "$DEPLOYMENT_COMMIT" > "$FIXTURE_ROOT/deployment-ftp-url.env"
assert_fails "deployment environment with FTP DAST target URL" \
    "$ROOT/scripts/ci/verify-deployment.sh" "$FIXTURE_ROOT/deployment-ftp-url.env" "$DEPLOYMENT_COMMIT"

printf 'DEPLOYED_COMMIT=abcdef0123456789abcdef0123456789abcdef01\nDAST_TARGET_URL=%s\n' \
    "$DEPLOYMENT_URL" > "$FIXTURE_ROOT/deployment-mismatched-commit.env"
assert_fails "deployment environment with mismatched full commit SHA" \
    "$ROOT/scripts/ci/verify-deployment.sh" "$FIXTURE_ROOT/deployment-mismatched-commit.env" "$DEPLOYMENT_COMMIT"

mkdir -p "$FIXTURE_ROOT/bin" "$FIXTURE_ROOT/services/test-service"
: > "$FIXTURE_ROOT/services/test-service/pom.xml"
cat > "$FIXTURE_ROOT/bin/mvn" <<'EOF'
#!/usr/bin/env sh
set -eu
printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-}" > "$MMIV_MAVEN_LOG"
printf 'ARGS=%s\n' "$*" >> "$MMIV_MAVEN_LOG"
rm -rf target
case "${FAKE_MVN_MODE:-success-two-tests}" in
    success-two-tests)
        mkdir -p target/surefire-reports
        printf '<testsuite name="unit" tests="1"/>\n' > target/surefire-reports/TEST-unit.xml
        printf '<testsuite name="integration" tests="1"/>\n' > target/surefire-reports/TEST-integration.xml
        ;;
    success-output-mentions-error-attribute)
        mkdir -p target/surefire-reports
        printf '%s\n' \
            '<testsuite name="unit"' \
            ' tests="1"' \
            ' failures="0"' \
            ' errors="0">' \
            '<system-out><![CDATA[downstream parser observed errors="1" and failures="2"]]></system-out>' \
            '</testsuite>' > target/surefire-reports/TEST-unit.xml
        ;;
    failed-test)
        exit 1
        ;;
    ignored-failed-test)
        mkdir -p target/surefire-reports
        printf '<testsuite name="unit" tests="1" failures="1" errors="0"/>\n' > target/surefire-reports/TEST-unit.xml
        ;;
    ignored-test-error)
        mkdir -p target/surefire-reports
        printf '<testsuite name="unit" tests="1" failures="0" errors="1"/>\n' > target/surefire-reports/TEST-unit.xml
        ;;
    missing-report)
        ;;
    zero-tests)
        mkdir -p target/surefire-reports
        printf '<testsuite tests="0"/>\n' > target/surefire-reports/TEST-app.xml
        printf '<testsuite tests="100"/>\n' > target/surefire-reports/dependency-check-junit.xml
        ;;
    *)
        printf 'FAIL: unknown fake Maven mode: %s\n' "$FAKE_MVN_MODE" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/mvn"

MMIV_MAVEN_LOG="$FIXTURE_ROOT/maven.log"
export MMIV_MAVEN_LOG
assert_succeeds "two application tests" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=success-two-tests "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_succeeds "passing Surefire summary despite attribute-like captured output" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=success-output-mentions-error-attribute "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "Maven test failure" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=failed-test "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "ignored Maven test failure in Surefire XML" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=ignored-failed-test "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "ignored Maven test error in Surefire XML" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=ignored-test-error "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "missing Surefire report" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=missing-report "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "zero application tests" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=zero-tests "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"

grep -qx 'ARGS=--batch-mode clean verify' "$MMIV_MAVEN_LOG"

mkdir -p "$FIXTURE_ROOT/services/test-service/src/main/java" "$FIXTURE_ROOT/security/semgrep" "$FIXTURE_ROOT/security/trivy"
printf 'class Example {}\n' > "$FIXTURE_ROOT/services/test-service/src/main/java/Example.java"
printf 'rules: []\n' > "$FIXTURE_ROOT/security/semgrep/semgrep.yml"
printf 'vulnerabilities: []\n' > "$FIXTURE_ROOT/security/trivy/.trivyignore.yaml"
cat > "$FIXTURE_ROOT/bin/semgrep" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" > "$MMIV_SEMGREP_LOG"
output=''
while [ "$#" -gt 0 ]; do
    if [ "$1" = '--output' ]; then
        shift
        output=$1
    fi
    shift
done
case "${MMIV_SEMGREP_MODE:-clean}" in
    clean)
        printf '%s\n' '{"results":[]}' > "$output"
        exit 0
        ;;
    finding)
        printf '%s\n' '{"results":[{"check_id":"test-rule"}]}' > "$output"
        exit 1
        ;;
    scanner-error)
        printf '%s\n' '{"error":"scanner-error"}' > "$output"
        exit 2
        ;;
    *)
        printf 'FAIL: unknown fake Semgrep mode: %s\n' "$MMIV_SEMGREP_MODE" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/semgrep"

MMIV_SEMGREP_LOG="$FIXTURE_ROOT/semgrep.log"
export MMIV_SEMGREP_LOG
assert_succeeds "clean Semgrep scan" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_SEMGREP_MODE=clean "$ROOT/scripts/ci/run-sast.sh" "$FIXTURE_ROOT"
test -f "$FIXTURE_ROOT/.ci/reports/semgrep.json"
grep -F '"results":[]' "$FIXTURE_ROOT/.ci/reports/semgrep.json"

assert_semgrep_invocation() {
    grep -F -- "scan --config $FIXTURE_ROOT/security/semgrep/semgrep.yml" "$MMIV_SEMGREP_LOG"
    grep -F -- '--error' "$MMIV_SEMGREP_LOG"
    grep -F -- '--json' "$MMIV_SEMGREP_LOG"
    grep -F -- "--output $FIXTURE_ROOT/.ci/reports/semgrep.json" "$MMIV_SEMGREP_LOG"
}

assert_semgrep_invocation

rm -f "$MMIV_SEMGREP_LOG" "$FIXTURE_ROOT/.ci/reports/semgrep.json"
assert_status 1 "Semgrep blocking finding" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_SEMGREP_MODE=finding "$ROOT/scripts/ci/run-sast.sh" "$FIXTURE_ROOT"
assert_semgrep_invocation
test -f "$FIXTURE_ROOT/.ci/reports/semgrep.json"
grep -F '"check_id":"test-rule"' "$FIXTURE_ROOT/.ci/reports/semgrep.json"

rm -f "$MMIV_SEMGREP_LOG" "$FIXTURE_ROOT/.ci/reports/semgrep.json"
assert_status 2 "Semgrep scanner error" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_SEMGREP_MODE=scanner-error "$ROOT/scripts/ci/run-sast.sh" "$FIXTURE_ROOT"
assert_semgrep_invocation
test -f "$FIXTURE_ROOT/.ci/reports/semgrep.json"
grep -F '"error":"scanner-error"' "$FIXTURE_ROOT/.ci/reports/semgrep.json"

cat > "$FIXTURE_ROOT/bin/trivy" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" > "$MMIV_TRIVY_LOG"
output=''
while [ "$#" -gt 0 ]; do
    if [ "$1" = '--output' ]; then
        shift
        output=$1
    fi
    shift
done
case "${MMIV_TRIVY_MODE:-clean}" in
    clean)
        printf '%s\n' '{"Results":[]}' > "$output"
        exit 0
        ;;
    vulnerable)
        printf '%s\n' '{"Results":[{"Vulnerabilities":[{"Severity":"HIGH"}]}]}' > "$output"
        exit 1
        ;;
    scanner-error)
        printf '%s\n' '{"error":"scanner-error"}' > "$output"
        exit 2
        ;;
    *)
        printf 'FAIL: unknown fake Trivy mode: %s\n' "$MMIV_TRIVY_MODE" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/trivy"

MMIV_TRIVY_LOG="$FIXTURE_ROOT/trivy.log"
export MMIV_TRIVY_LOG
assert_succeeds "clean Trivy filesystem SCA scan" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_TRIVY_MODE=clean "$ROOT/scripts/ci/run-sca.sh" "$FIXTURE_ROOT"
test -f "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"
grep -F '"Results":[]' "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"

assert_trivy_sca_invocation() {
    grep -F -- "fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --skip-dirs $FIXTURE_ROOT/.worktrees --skip-dirs $FIXTURE_ROOT/.superpowers --ignorefile $FIXTURE_ROOT/security/trivy/.trivyignore.yaml --output $FIXTURE_ROOT/.ci/reports/trivy-sca.json $FIXTURE_ROOT" \
        "$MMIV_TRIVY_LOG"
}

assert_trivy_sca_invocation

rm -f "$MMIV_TRIVY_LOG" "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"
assert_status 1 "Trivy blocking vulnerability" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_TRIVY_MODE=vulnerable "$ROOT/scripts/ci/run-sca.sh" "$FIXTURE_ROOT"
assert_trivy_sca_invocation
test -f "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"
grep -F '"Severity":"HIGH"' "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"

rm -f "$MMIV_TRIVY_LOG" "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"
assert_status 2 "Trivy scanner error" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_TRIVY_MODE=scanner-error "$ROOT/scripts/ci/run-sca.sh" "$FIXTURE_ROOT"
assert_trivy_sca_invocation
test -f "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"
grep -F '"error":"scanner-error"' "$FIXTURE_ROOT/.ci/reports/trivy-sca.json"

printf 'mmiv:test-image\n' > "$FIXTURE_ROOT/.ci/image-ref"
assert_succeeds "clean Trivy image scan" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_TRIVY_MODE=clean "$ROOT/scripts/ci/scan-image.sh" "$FIXTURE_ROOT"
test -f "$FIXTURE_ROOT/.ci/reports/trivy-image.json"
grep -F -- "image --severity HIGH,CRITICAL --exit-code 1 --format json --ignorefile $FIXTURE_ROOT/security/trivy/.trivyignore.yaml --output $FIXTURE_ROOT/.ci/reports/trivy-image.json mmiv:test-image" \
    "$MMIV_TRIVY_LOG"

rm -f "$MMIV_TRIVY_LOG" "$FIXTURE_ROOT/.ci/reports/trivy-image.json"
assert_status 1 "Trivy blocking image vulnerability" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_TRIVY_MODE=vulnerable "$ROOT/scripts/ci/scan-image.sh" "$FIXTURE_ROOT"
test -f "$FIXTURE_ROOT/.ci/reports/trivy-image.json"
grep -F '"Severity":"HIGH"' "$FIXTURE_ROOT/.ci/reports/trivy-image.json"

printf 'FROM scratch\n' > "$FIXTURE_ROOT/dockerfile"
FIXTURE_ROOT_PATH=$(CDPATH= cd -- "$FIXTURE_ROOT" && pwd)
if [ -f "$FIXTURE_ROOT_PATH/Dockerfile" ]; then
    FIXTURE_DOCKERFILE="$FIXTURE_ROOT_PATH/Dockerfile"
else
    FIXTURE_DOCKERFILE="$FIXTURE_ROOT_PATH/dockerfile"
fi
cat > "$FIXTURE_ROOT/bin/docker" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" > "$MMIV_DOCKER_LOG"
EOF
chmod +x "$FIXTURE_ROOT/bin/docker"

MMIV_DOCKER_LOG="$FIXTURE_ROOT/docker.log"
export MMIV_DOCKER_LOG
MMIV_IMAGE=mmiv:contract PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/build-image.sh" "$FIXTURE_ROOT"

grep -Fx -- "build --file $FIXTURE_DOCKERFILE --tag mmiv:contract $FIXTURE_ROOT_PATH" \
    "$MMIV_DOCKER_LOG"
grep -qx 'mmiv:contract' "$FIXTURE_ROOT/.ci/image-ref"

TESTED_COMMIT=0123456789abcdef0123456789abcdef01234567 \
    GIT_COMMIT=ffffffffffffffffffffffffffffffffffffffff \
    BUILD_NUMBER=99 \
    PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/build-image.sh" "$FIXTURE_ROOT"

grep -Fx -- "build --file $FIXTURE_DOCKERFILE --tag mmiv:0123456789abcdef0123456789abcdef01234567 $FIXTURE_ROOT_PATH" \
    "$MMIV_DOCKER_LOG"
grep -qx 'mmiv:0123456789abcdef0123456789abcdef01234567' "$FIXTURE_ROOT/.ci/image-ref"

mkdir -p "$FIXTURE_ROOT/infrastructure"
printf 'resource "test_resource" "example" {}\n' > "$FIXTURE_ROOT/infrastructure/main.tf"
cat > "$FIXTURE_ROOT/bin/checkov" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' '{"summary":{"passed":1,"failed":0}}'
exit "${MMIV_CHECKOV_EXIT:-0}"
EOF
chmod +x "$FIXTURE_ROOT/bin/checkov"

PATH="$FIXTURE_ROOT/bin:$PATH" "$ROOT/scripts/ci/run-iac-scan.sh" "$FIXTURE_ROOT"
test -f "$FIXTURE_ROOT/.ci/reports/checkov.json"
grep -F '"failed":0' "$FIXTURE_ROOT/.ci/reports/checkov.json"

if MMIV_CHECKOV_EXIT=1 PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/run-iac-scan.sh" "$FIXTURE_ROOT"; then
    printf 'FAIL: IaC wrapper ignored the Checkov failure exit code.\n' >&2
    exit 1
fi

cat > "$FIXTURE_ROOT/bin/curl" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" > "$MMIV_ZAP_CURL_LOG"
case "${MMIV_CURL_MODE:-reachable}" in
    reachable)
        exit 0
        ;;
    unreachable)
        printf 'curl: target is unreachable\n' >&2
        exit 22
        ;;
    *)
        printf 'FAIL: unknown fake curl mode: %s\n' "$MMIV_CURL_MODE" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/curl"

cat > "$FIXTURE_ROOT/bin/docker" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" >> "$MMIV_ZAP_DOCKER_LOG"
command=$1
shift
case "$command" in
    create)
        printf 'fake-zap-container\n'
        ;;
    start)
        printf 'fake ZAP console output\n'
        case "${MMIV_ZAP_MODE:-clean}" in
            clean) exit 0 ;;
            policy-failure) exit 1 ;;
            warnings) exit 2 ;;
            execution-error) exit 3 ;;
            *)
                printf 'FAIL: unknown fake ZAP mode: %s\n' "$MMIV_ZAP_MODE" >&2
                exit 2
                ;;
        esac
        ;;
    cp)
        source_path=$1
        destination=$2
        case "$source_path" in
            *zap-report.html) printf '<html>fake ZAP report</html>\n' > "$destination" ;;
            *zap-report.json) printf '{"site":"fake-zap"}\n' > "$destination" ;;
            *) exit 2 ;;
        esac
        ;;
    rm)
        ;;
    *)
        exit 2
        ;;
esac
EOF
chmod +x "$FIXTURE_ROOT/bin/docker"

MMIV_ZAP_CURL_LOG="$FIXTURE_ROOT/zap-curl.log"
MMIV_ZAP_DOCKER_LOG="$FIXTURE_ROOT/zap-docker.log"
export MMIV_ZAP_CURL_LOG MMIV_ZAP_DOCKER_LOG

rm -f "$MMIV_ZAP_CURL_LOG" "$MMIV_ZAP_DOCKER_LOG"
assert_status 1 "empty DAST target URL" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" ''
test ! -e "$MMIV_ZAP_CURL_LOG"
test ! -e "$MMIV_ZAP_DOCKER_LOG"

rm -f "$MMIV_ZAP_CURL_LOG" "$MMIV_ZAP_DOCKER_LOG"
assert_status 1 "file DAST target URL" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'file:///tmp/not-a-target'
test ! -e "$MMIV_ZAP_CURL_LOG"
test ! -e "$MMIV_ZAP_DOCKER_LOG"

rm -f "$MMIV_ZAP_CURL_LOG" "$MMIV_ZAP_DOCKER_LOG"
assert_status 1 "unreachable DAST target URL" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_CURL_MODE=unreachable sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'https://dast.example.test/health'
grep -Fx -- '--fail --silent --show-error --max-time 10 https://dast.example.test/health' "$MMIV_ZAP_CURL_LOG"
test ! -e "$MMIV_ZAP_DOCKER_LOG"

assert_zap_invocation() {
    grep -Fx -- "create --user 0:0 --network mmiv-ci --volume /zap/wrk ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef zap-baseline.py -t https://dast.example.test/health -J zap-report.json -r zap-report.html" "$MMIV_ZAP_DOCKER_LOG"
    grep -Fx -- 'start --attach fake-zap-container' "$MMIV_ZAP_DOCKER_LOG"
    grep -Fx -- "cp fake-zap-container:/zap/wrk/zap-report.json $FIXTURE_ROOT/.ci/reports/zap-report.json" "$MMIV_ZAP_DOCKER_LOG"
    grep -Fx -- "cp fake-zap-container:/zap/wrk/zap-report.html $FIXTURE_ROOT/.ci/reports/zap-report.html" "$MMIV_ZAP_DOCKER_LOG"
    grep -Fx -- 'rm -f -v fake-zap-container' "$MMIV_ZAP_DOCKER_LOG"
}

rm -f "$MMIV_ZAP_CURL_LOG" "$MMIV_ZAP_DOCKER_LOG" "$FIXTURE_ROOT/.ci/reports/zap-report.html" \
    "$FIXTURE_ROOT/.ci/reports/zap-report.json" "$FIXTURE_ROOT/.ci/reports/zap.log"
assert_succeeds "clean ZAP baseline scan" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'https://dast.example.test/health'
grep -Fx -- '--fail --silent --show-error --max-time 10 https://dast.example.test/health' "$MMIV_ZAP_CURL_LOG"
assert_zap_invocation
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.html"
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.json"
test -f "$FIXTURE_ROOT/.ci/reports/zap.log"
grep -F 'fake ZAP console output' "$FIXTURE_ROOT/.ci/reports/zap.log"

rm -f "$MMIV_ZAP_DOCKER_LOG"
assert_succeeds "ZAP warnings are reported but non-blocking" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_ZAP_MODE=warnings sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'https://dast.example.test/health'
assert_zap_invocation
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.html"
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.json"
grep -F 'fake ZAP console output' "$FIXTURE_ROOT/.ci/reports/zap.log"

rm -f "$MMIV_ZAP_DOCKER_LOG"
assert_status 1 "ZAP policy failure" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_ZAP_MODE=policy-failure sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'https://dast.example.test/health'
assert_zap_invocation
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.html"
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.json"
grep -F 'fake ZAP console output' "$FIXTURE_ROOT/.ci/reports/zap.log"

rm -f "$MMIV_ZAP_DOCKER_LOG"
assert_status 3 "ZAP execution error" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    MMIV_ZAP_MODE=execution-error sh -c 'cd "$1" && exec "$2" "$3"' sh "$FIXTURE_ROOT" "$ROOT/scripts/ci/run-dast.sh" 'https://dast.example.test/health'
assert_zap_invocation
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.html"
test -f "$FIXTURE_ROOT/.ci/reports/zap-report.json"
grep -F 'fake ZAP console output' "$FIXTURE_ROOT/.ci/reports/zap.log"

# Evidence must serialize hostile strings as data, exclude all unrelated secrets,
# survive early failure with empty SCM fields, and preserve old output on error.
test -f "$ROOT/scripts/ci/collect-evidence.sh" || {
    printf 'FAIL: missing collect-evidence.sh.\n' >&2
    exit 1
}
JSON_PYTHON=python
cat > "$FIXTURE_ROOT/bin/python3" <<'EOF'
#!/usr/bin/env sh
exec python "$@"
EOF
chmod +x "$FIXTURE_ROOT/bin/python3"
(
    cd "$FIXTURE_ROOT"
    BRANCH_NAME='feature/"quoted"\path
$(touch metadata-injection)' TESTED_COMMIT="$DEPLOYMENT_COMMIT" \
        BUILD_NUMBER=17 BUILD_URL='https://ci.example.test/job/"gate"/17/' MMIV_BUILD_RESULT=FAILURE \
        PRIVATE_KEY=key-sentinel API_TOKEN=token-sentinel CLIENT_SECRET=secret-sentinel \
        DB_PASSWORD=password-sentinel OTHER_PRIVATE_VALUE=private-sentinel \
        PATH="$FIXTURE_ROOT/bin:$PATH" sh "$ROOT/scripts/ci/collect-evidence.sh"
    "$JSON_PYTHON" - <<'PY'
import json
import re
from pathlib import Path
raw = Path('.ci/reports/build-metadata.json').read_text(encoding='utf-8')
data = json.loads(raw)
assert set(data) == {'branch', 'tested_commit', 'build_number', 'build_url', 'timestamp', 'result'}
assert data['branch'] == 'feature/"quoted"\\path\n$(touch metadata-injection)'
assert data['tested_commit'] == '0123456789abcdef0123456789abcdef01234567'
assert data['build_number'] == '17'
assert data['build_url'] == 'https://ci.example.test/job/"gate"/17/'
assert data['result'] == 'FAILURE'
assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', data['timestamp'])
assert not re.search(r'KEY|TOKEN|SECRET|PASSWORD', raw, re.I)
assert 'sentinel' not in raw
assert not Path('metadata-injection').exists()
PY
    cp .ci/reports/build-metadata.json metadata-before.json
    mkdir -p broken-json
    printf '#!/usr/bin/env sh\nprintf "partial JSON"\nexit 23\n' > broken-json/python3
    chmod +x broken-json/python3
    assert_status 23 'metadata encoder failure' env PATH="$FIXTURE_ROOT/broken-json:$PATH" \
        sh "$ROOT/scripts/ci/collect-evidence.sh"
    cmp metadata-before.json .ci/reports/build-metadata.json
    test "$(find .ci/reports -name '.build-metadata.*' | wc -l | tr -d ' ')" = 0
    unset BRANCH_NAME TESTED_COMMIT BUILD_NUMBER BUILD_URL
    MMIV_BUILD_RESULT=FAILURE PATH="$FIXTURE_ROOT/bin:$PATH" \
        sh "$ROOT/scripts/ci/collect-evidence.sh"
    "$JSON_PYTHON" - <<'PY'
import json
from pathlib import Path
data = json.loads(Path('.ci/reports/build-metadata.json').read_text(encoding='utf-8'))
assert [data[k] for k in ('branch', 'tested_commit', 'build_number', 'build_url')] == ['', '', '', '']
assert data['result'] == 'FAILURE'
PY
)
printf 'PASS: metadata allow-list, JSON escaping, early failure, and atomic replacement.\n'

# Execute the actual Jenkins shell body; the external trigger is mocked only in
# the temporary workspace. Dropping fail-closed handling must break these tests.
awk '/label: '\''Trigger deployment'\''/ { block=1; next }
    block && /'\'''\'''\''/ { exit }
    block { print }' "$ROOT/Jenkinsfile" > "$FIXTURE_ROOT/jenkins-deployment.sh"
test -s "$FIXTURE_ROOT/jenkins-deployment.sh"
mkdir -p "$FIXTURE_ROOT/scripts/cd"
(
    cd "$FIXTURE_ROOT"
    export TESTED_COMMIT="$DEPLOYMENT_COMMIT"
    assert_status 1 'required missing deployment script' sh jenkins-deployment.sh
    grep -F 'Missing required deployment script' .ci/reports/deployment.log
    cat > scripts/cd/trigger-deployment.sh <<'EOF'
#!/usr/bin/env bash
set -eu
commit_args=("$@")
test "${#commit_args[@]}" -eq 1
test "$1" = '0123456789abcdef0123456789abcdef01234567'
printf 'deployment output\n'
printf 'deployment diagnostic\n' >&2
case "${DEPLOYMENT_MODE:-valid}" in
    failure) exit 29 ;;
    no-env) exit 0 ;;
    valid) printf 'DEPLOYED_COMMIT=%s\nDAST_TARGET_URL=https://dast.example.test/health\n' "$1" > .ci/deployment.env ;;
esac
EOF
    chmod +x scripts/cd/trigger-deployment.sh
    assert_status 29 'external deployment failure' env DEPLOYMENT_MODE=failure sh jenkins-deployment.sh
    grep -Fx 'deployment output' .ci/reports/deployment.log
    grep -Fx 'deployment diagnostic' .ci/reports/deployment.log
    printf 'stale deployment\n' > .ci/deployment.env
    assert_status 1 'deployment did not create its environment file' env DEPLOYMENT_MODE=no-env sh jenkins-deployment.sh
    test ! -e .ci/deployment.env
    assert_succeeds 'external deployment exact commit handoff' sh jenkins-deployment.sh
    "$ROOT/scripts/ci/verify-deployment.sh" .ci/deployment.env "$TESTED_COMMIT" > verified-url
    grep -Fx 'https://dast.example.test/health' verified-url
)
printf 'PASS: Jenkins deployment shell propagates failures and enforces fresh exact-commit evidence.\n'
printf 'PASS: CI runtime skeleton behavior is valid.\n'
