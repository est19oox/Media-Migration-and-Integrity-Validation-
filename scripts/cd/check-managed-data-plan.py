#!/usr/bin/env python3
"""Permit the planned Oracle schema password rotation, but no data deletion."""

import json
import sys


SCHEMA_USERS = ("DISCOVERY", "INTEGRITY_SERVICE", "USER_ACCESS")
PASSWORD_RESOURCES = {
    f'{resource}["{user}"]'
    for resource in ("random_password.schema", "aws_secretsmanager_secret_version.schema")
    for user in SCHEMA_USERS
}


def forbidden_deletions(plan):
    forbidden = []
    for item in plan.get("resource_changes", []):
        actions = item.get("change", {}).get("actions", [])
        if "delete" not in actions:
            continue
        address = item.get("address", "")
        if address in PASSWORD_RESOURCES and len(actions) == 2 and set(actions) == {"create", "delete"}:
            continue
        forbidden.append(address)
    return forbidden


def main():
    with open(sys.argv[1], encoding="utf-8") as source:
        forbidden = forbidden_deletions(json.load(source))
    if forbidden:
        raise SystemExit("Managed-data plan would delete or replace: " + ", ".join(forbidden))


if __name__ == "__main__":
    main()
