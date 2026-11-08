#!/usr/bin/env bash
set -euo pipefail

: "${TRIVY_VERSION:?Set TRIVY_VERSION}"
: "${TRIVY_SHA256:?Set TRIVY_SHA256}"

package="trivy_${TRIVY_VERSION}_Linux-64bit.deb"
directory=$(mktemp -d)
container=''
cleanup() {
  if [ -n "$container" ]; then docker rm "$container" >/dev/null 2>&1 || true; fi
  rm -rf "$directory"
}
trap cleanup EXIT
url="https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${package}"

# The official OCI release is independently pinned to its linux/amd64 manifest
# and extracted binary. GitHub's release-asset endpoint can return persistent 504s.
image='ghcr.io/aquasecurity/trivy@sha256:ee940acbf1f58ebadb42d01434ce4609530bf1b52536afbd1eee66cd7123c5c9'
binary_sha256='d89bcc6510a267f11b773398cbf1be5520ce39f9e8b6633178c4487f05b7d791'
if [ "$TRIVY_VERSION" = '0.74.0' ] && docker pull "$image"; then
  container=$(docker create --entrypoint sh "$image")
  docker cp "$container:/usr/local/bin/trivy" "$directory/trivy"
  printf '%s  %s\n' "$binary_sha256" "$directory/trivy" | sha256sum --check --strict
  sudo install -m 0755 "$directory/trivy" /usr/local/bin/trivy
  trivy --version
  exit 0
fi

# If GHCR is unavailable, retry the original release asset with its .deb hash.
curl --fail --silent --show-error --location \
  --connect-timeout 15 --max-time 60 \
  --retry 5 --retry-all-errors --retry-delay 3 --retry-max-time 180 \
  --output "$directory/$package" "$url"
printf '%s  %s\n' "$TRIVY_SHA256" "$directory/$package" | sha256sum --check --strict
sudo apt-get install --yes "$directory/$package"
