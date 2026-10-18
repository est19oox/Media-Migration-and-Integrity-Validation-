#!/usr/bin/env bash
# Read-only diagnostics for secondary MMIV gateway prerequisites.
set -euo pipefail
PRIMARY="${1:-10.0.1.169}"
case "$PRIMARY" in
  10.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|192.168.*) ;;
  *) echo "Expected a private IPv4 primary address" >&2; exit 2 ;;
esac
echo "Secondary -> primary TCP connectivity (no changes performed)"
for item in "8761:Eureka" "5672:RabbitMQ" "6379:Redis" "8083:UserAccess-JWKS"; do
  port="${item%%:*}"
  label="${item#*:}"
  if timeout 4 bash -c "echo > /dev/tcp/$PRIMARY/$port" 2>/dev/null; then
    echo "PASS $label ($PRIMARY:$port)"
  else
    echo "BLOCKED $label ($PRIMARY:$port)"
  fi
done
echo "If blocked: verify BOTH security groups and primary Docker listener bindings."
echo "Even open ports are insufficient until Eureka advertises cross-host-routable addresses."
