#!/usr/bin/env sh
set -eu
commit=${1:?Usage: trigger-deployment.sh <full-commit-sha>}
[ "$commit" = "$(git rev-parse HEAD)" ] || { echo "Commit mismatch" >&2; exit 1; }
[ "${#commit}" -eq 40 ] || exit 1
image=$(cat .ci/image-ref)
name="mmiv-test-${commit}-$(date +%s)-$$"
network=${MMIV_TEST_NETWORK:-mmiv-ci}
docker network inspect "$network" >/dev/null
container=$(docker run -d --name "$name" --network "$network" --label mmiv.test=true "$image")
printf '%s\n' "$container" > .ci/test-container
ip=$(docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" "$container")
url="http://$ip:8081/actuator/health"
ready=false
for attempt in $(seq 1 60); do
    if curl -fsS --max-time 5 "$url" >/dev/null; then ready=true; break; fi
    sleep 2
done
if [ "$ready" != true ]; then docker logs "$container"; exit 1; fi
printf 'DEPLOYED_COMMIT=%s\nDAST_TARGET_URL=%s\n' "$commit" "$url" > .ci/deployment.env
