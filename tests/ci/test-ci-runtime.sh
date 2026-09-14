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

printf 'PASS: CI runtime skeleton behavior is valid.\n'
