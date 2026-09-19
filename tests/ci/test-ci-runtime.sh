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
"$ROOT/scripts/ci/run-tests.sh" "$ROOT"
"$ROOT/scripts/ci/run-sast.sh" "$ROOT"
"$ROOT/scripts/ci/run-sca.sh" "$ROOT"
"$ROOT/scripts/ci/run-terraform-checks.sh" "$ROOT"
"$ROOT/scripts/ci/run-iac-scan.sh" "$ROOT"
"$ROOT/scripts/ci/build-image.sh" "$ROOT"
"$ROOT/scripts/ci/scan-image.sh" "$ROOT"

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

mkdir -p "$FIXTURE_ROOT/bin"
: > "$FIXTURE_ROOT/pom.xml"
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
    failed-test)
        exit 1
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
assert_fails "Maven test failure" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=failed-test "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "missing Surefire report" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=missing-report "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"
assert_fails "zero application tests" env PATH="$FIXTURE_ROOT/bin:$PATH" \
    FAKE_MVN_MODE=zero-tests "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"

grep -qx 'JAVA_HOME=/opt/jdk-25' "$MMIV_MAVEN_LOG"
grep -qx 'ARGS=--batch-mode test' "$MMIV_MAVEN_LOG"

mkdir -p "$FIXTURE_ROOT/src/main/java" "$FIXTURE_ROOT/security/semgrep"
printf 'class Example {}\n' > "$FIXTURE_ROOT/src/main/java/Example.java"
printf 'rules: []\n' > "$FIXTURE_ROOT/security/semgrep/semgrep.yml"
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
    grep -F -- "fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output $FIXTURE_ROOT/.ci/reports/trivy-sca.json $FIXTURE_ROOT" \
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

GIT_COMMIT=0123456789abcdef0123456789abcdef01234567 \
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

printf 'PASS: CI runtime skeleton behavior is valid.\n'
