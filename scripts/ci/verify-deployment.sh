#!/usr/bin/env sh

set -eu

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

is_full_sha() {
    value=$1
    [ "${#value}" -eq 40 ] || return 1
    case "$value" in
        *[!0123456789abcdef]*) return 1 ;;
    esac
}

if [ "$#" -ne 2 ]; then
    fail 'Usage: verify-deployment.sh <deployment-env-file> <expected-commit>'
fi

deployment_env=$1
expected_commit=$2

[ -f "$deployment_env" ] && [ -r "$deployment_env" ] || \
    fail 'Deployment environment file is missing or unreadable.'

deployed_count=$(grep -c '^DEPLOYED_COMMIT=' "$deployment_env" || :)
dast_url_count=$(grep -c '^DAST_TARGET_URL=' "$deployment_env" || :)

[ "$deployed_count" -eq 1 ] || fail 'DEPLOYED_COMMIT must occur exactly once.'
[ "$dast_url_count" -eq 1 ] || fail 'DAST_TARGET_URL must occur exactly once.'

deployed_commit=$(sed -n 's/^DEPLOYED_COMMIT=//p' "$deployment_env")
dast_target_url=$(sed -n 's/^DAST_TARGET_URL=//p' "$deployment_env")

is_full_sha "$expected_commit" || fail 'Expected commit must be a full SHA.'
is_full_sha "$deployed_commit" || fail 'Deployed commit must be a full SHA.'
[ "$deployed_commit" = "$expected_commit" ] || fail 'Deployed commit does not match the expected commit.'

if ! printf '%s\n' "$dast_target_url" | grep -Eq '^https?://[^[:space:]]+$'; then
    fail 'DAST target URL must use HTTP or HTTPS.'
fi

printf '%s\n' "$dast_target_url"
