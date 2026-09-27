#!/usr/bin/env sh

set -eu

pass() {
    printf 'PASS: %s\n' "$1"
}

skip() {
    printf 'SKIPPED: %s\n' "$1"
}

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    return 1
}

require_command() {
    command_name=$1
    command -v "$command_name" >/dev/null 2>&1 || fail "Required command is unavailable: $command_name"
}

find_repository_root() {
    requested_path=${1:-.}
    if command -v git >/dev/null 2>&1 && git -C "$requested_path" rev-parse --show-toplevel >/dev/null 2>&1; then
        git -C "$requested_path" rev-parse --show-toplevel
    else
        (cd "$requested_path" && pwd)
    fi
}

has_files() {
    search_root=$1
    file_pattern=$2
    find "$search_root" \
        -path "$search_root/.git" -prune -o \
        -path "$search_root/.ci" -prune -o \
        -type f -name "$file_pattern" -print -quit | grep -q .
}

prepare_reports_directory() {
    repository_root=$1
    mkdir -p "$repository_root/.ci/reports"
}
