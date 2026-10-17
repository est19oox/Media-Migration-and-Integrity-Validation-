#!/usr/bin/env sh

set -eu

requirements=${1:?Usage: install-ansible-collections.sh <requirements.yml>}
max_attempts=${MMIV_ANSIBLE_GALAXY_MAX_ATTEMPTS:-3}
retry_delay=${MMIV_ANSIBLE_GALAXY_RETRY_DELAY_SECONDS:-5}
attempt=1

while [ "$attempt" -le "$max_attempts" ]; do
    status=0
    ansible-galaxy collection install -r "$requirements" || status=$?
    if [ "$status" -eq 0 ]; then
        exit 0
    fi

    if [ "$attempt" -ge "$max_attempts" ]; then
        printf 'Ansible Galaxy collection installation failed after %s attempts.\n' \
            "$max_attempts" >&2
        exit "$status"
    fi

    printf 'Ansible Galaxy collection installation attempt %s/%s failed; retrying in %ss.\n' \
        "$attempt" "$max_attempts" "$retry_delay" >&2
    sleep "$retry_delay"
    attempt=$((attempt + 1))
done
