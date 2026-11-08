#!/usr/bin/env bash
set -euo pipefail

: "${TRIVY_VERSION:?Set TRIVY_VERSION}"
: "${TRIVY_SHA256:?Set TRIVY_SHA256}"

package="trivy_${TRIVY_VERSION}_Linux-64bit.deb"
directory=$(mktemp -d)
trap 'rm -rf "$directory"' EXIT
url="https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${package}"

# Release asset delivery occasionally returns a transient 504. Keep retries
# bounded; never install a package unless its pinned digest matches.
curl --fail --silent --show-error --location \
  --connect-timeout 15 --max-time 60 \
  --retry 5 --retry-all-errors --retry-delay 3 --retry-max-time 180 \
  --output "$directory/$package" "$url"
printf '%s  %s\n' "$TRIVY_SHA256" "$directory/$package" | sha256sum --check --strict
sudo apt-get install --yes "$directory/$package"
