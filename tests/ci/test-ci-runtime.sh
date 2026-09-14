#!/usr/bin/env sh

set -eu

ROOT=${1:-/repo}
FIXTURE_ROOT=$(mktemp -d)
trap 'rm -rf "$FIXTURE_ROOT"' EXIT

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

mkdir -p "$FIXTURE_ROOT/bin"
: > "$FIXTURE_ROOT/pom.xml"
cat > "$FIXTURE_ROOT/bin/mvn" <<'EOF'
#!/usr/bin/env sh
set -eu
printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-}" > "$MMIV_MAVEN_LOG"
printf 'ARGS=%s\n' "$*" >> "$MMIV_MAVEN_LOG"
EOF
chmod +x "$FIXTURE_ROOT/bin/mvn"

MMIV_MAVEN_LOG="$FIXTURE_ROOT/maven.log"
export MMIV_MAVEN_LOG
PATH="$FIXTURE_ROOT/bin:$PATH" "$ROOT/scripts/ci/run-tests.sh" "$FIXTURE_ROOT"

grep -qx 'JAVA_HOME=/opt/jdk-25' "$MMIV_MAVEN_LOG"
grep -qx 'ARGS=--batch-mode test' "$MMIV_MAVEN_LOG"

mkdir -p "$FIXTURE_ROOT/src/main/java" "$FIXTURE_ROOT/security/semgrep"
printf 'class Example {}\n' > "$FIXTURE_ROOT/src/main/java/Example.java"
printf 'rules: []\n' > "$FIXTURE_ROOT/security/semgrep/semgrep.yml"
cat > "$FIXTURE_ROOT/bin/semgrep" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" > "$MMIV_SEMGREP_LOG"
exit "${MMIV_SEMGREP_EXIT:-0}"
EOF
chmod +x "$FIXTURE_ROOT/bin/semgrep"

MMIV_SEMGREP_LOG="$FIXTURE_ROOT/semgrep.log"
export MMIV_SEMGREP_LOG
PATH="$FIXTURE_ROOT/bin:$PATH" "$ROOT/scripts/ci/run-sast.sh" "$FIXTURE_ROOT"

grep -F -- "--config $FIXTURE_ROOT/security/semgrep/semgrep.yml" "$MMIV_SEMGREP_LOG"
grep -F -- '--error' "$MMIV_SEMGREP_LOG"
grep -F -- '--json' "$MMIV_SEMGREP_LOG"
grep -F -- "--output $FIXTURE_ROOT/.ci/reports/semgrep.json" "$MMIV_SEMGREP_LOG"

if MMIV_SEMGREP_EXIT=1 PATH="$FIXTURE_ROOT/bin:$PATH" \
    "$ROOT/scripts/ci/run-sast.sh" "$FIXTURE_ROOT"; then
    printf 'FAIL: SAST wrapper ignored the scanner failure exit code.\n' >&2
    exit 1
fi

printf 'FROM scratch\n' > "$FIXTURE_ROOT/dockerfile"
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

grep -Fx -- "build --file $FIXTURE_ROOT/dockerfile --tag mmiv:contract $FIXTURE_ROOT" \
    "$MMIV_DOCKER_LOG"
grep -qx 'mmiv:contract' "$FIXTURE_ROOT/.ci/image-ref"

printf 'PASS: CI runtime skeleton behavior is valid.\n'
