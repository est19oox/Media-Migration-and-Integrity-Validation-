#!/usr/bin/env python3
"""Reject destructive ECS plans, allowing only immutable task-definition revisions."""

import json
import re
import sys


def forbidden_deletions(plan):
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
        forbidden.append(address)
    return forbidden


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check-ecs-release-plan.py PLAN_JSON")
    with open(sys.argv[1], encoding="utf-8") as source:
        plan = json.load(source)
    forbidden = forbidden_deletions(plan)
    if forbidden:
        raise SystemExit("ECS plan would delete or replace resources: " + ", ".join(forbidden))


if __name__ == "__main__":
    main()
