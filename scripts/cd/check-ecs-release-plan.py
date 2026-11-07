#!/usr/bin/env python3
"""Reject destructive ECS plans, allowing only immutable task-definition revisions."""

import json
from pathlib import Path
import re
import sys


REGISTRY_SERVICES = {
    "aws_service_discovery_service.registry",
    "aws_service_discovery_service.registry_peer",
}


def allow_registry_replacement(prepare_base, state_addresses):
    return prepare_base and not any(
        address.startswith('aws_ecs_service.service[') for address in state_addresses
    )


def forbidden_deletions(plan, allow_unstarted_registry=False):
    forbidden = []
    for item in plan.get("resource_changes", []):
        actions = item.get("change", {}).get("actions", [])
        if "delete" not in actions:
            continue
        address = item.get("address", "")
        task_definition = address == "aws_ecs_task_definition.oracle_bootstrap" or re.fullmatch(
            r'aws_ecs_task_definition\.service\["[^"\]]+"\]', address
        )
        if task_definition and len(actions) == 2 and set(actions) == {"create", "delete"}:
            continue
        if (allow_unstarted_registry and address in REGISTRY_SERVICES
                and len(actions) == 2 and set(actions) == {"create", "delete"}):
            continue
        forbidden.append(address)
    return forbidden


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in ("true", "false"):
        raise SystemExit("Usage: check-ecs-release-plan.py PLAN_JSON STATE_LIST PREPARE_BASE")
    with open(sys.argv[1], encoding="utf-8") as source:
        plan = json.load(source)
    state_addresses = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    allow_registry = allow_registry_replacement(sys.argv[3] == "true", state_addresses)
    forbidden = forbidden_deletions(plan, allow_registry)
    if forbidden:
        raise SystemExit("ECS plan would delete or replace resources: " + ", ".join(forbidden))


if __name__ == "__main__":
    main()
